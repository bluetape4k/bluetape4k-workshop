package io.bluetape4k.workshop.bucket4j.advanced.filter

import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedSuspendRateLimiter
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.trace
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.bucket4j.advanced.utils.HeaderConstants
import io.bluetape4k.workshop.bucket4j.advanced.utils.RequestUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/** 두 bucket OR 결정의 admission 결과입니다. */
internal enum class OrRateLimitDecision {
    /** 두 검사가 모두 성공해 요청을 계속 처리합니다. */
    ALLOW,

    /** 하나 이상의 bucket이 요청을 거절했습니다. */
    REJECT,

    /** 거절은 없지만 하나 이상의 limiter가 실패해 fail-open으로 통과시킵니다. */
    FAIL_OPEN,
}

internal fun decideOrRateLimit(
    ipResult: RateLimitResult,
    userResult: RateLimitResult,
): OrRateLimitDecision = when {
    ipResult.isRejected || userResult.isRejected -> OrRateLimitDecision.REJECT
    ipResult.isError || userResult.isError -> OrRateLimitDecision.FAIL_OPEN
    else -> OrRateLimitDecision.ALLOW
}

/**
 * `/api/sensitive-or/` 요청에 IP와 user ID rate limit을 각각 적용하는
 * [WebFilter]입니다.
 *
 * ## 동작 계약
 * - path가 `/api/sensitive-or/`로 시작하는 요청만 가로챕니다.
 * - IP와 `X-User-ID`를 서로 다른 bucket key로 소비합니다.
 * - 두 consume 호출은 동시에 실행되므로 요청마다 두 bucket에서 token이 소비될 수 있습니다.
 * - 두 bucket 중 하나라도 거절하면 HTTP 429와 `Retry-After`를 반환합니다.
 * - 거절 결과가 있으면 내부 오류보다 우선합니다.
 *   거절 없이 오류만 있으면 fail-open으로 통과시킵니다.
 * - user ID가 없으면 HTTP 400으로 응답하고 limiter를 호출하지 않습니다.
 * - limiter 내부 오류는 서비스 중단을 피하려고 fail-open으로 통과시킵니다.
 */
@Component
@Order(13)
class OrRateLimitWebFilter(
    @Qualifier("ipRateLimiter")
    private val ipRateLimiter: DistributedSuspendRateLimiter,
    @Qualifier("userRateLimiter")
    private val userRateLimiter: DistributedSuspendRateLimiter,
    @Value("\${ratelimit.trust-proxy:false}")
    private val trustProxy: Boolean = false,
) : WebFilter {

    companion object : KLoggingChannel() {
        private const val TARGET_PATH_PREFIX = "/api/sensitive-or/"
        private const val IP_KEY_PREFIX = "or:ip:"
        private const val USER_KEY_PREFIX = "or:user:"
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        mono(Dispatchers.IO) {
            val path = exchange.request.uri.path
            if (!path.startsWith(TARGET_PATH_PREFIX)) {
                chain.filter(exchange).awaitSingleOrNull()
                return@mono null
            }

            val ip = RequestUtils.extractIp(exchange, trustProxy)
            val userId = RequestUtils.extractUserId(exchange)
            log.trace { "OR rate limit: path=$path, ip=$ip, userId=$userId" }

            if (userId.isNullOrBlank()) {
                log.warn { "Missing X-User-ID for OR rate limit. path=$path" }
                exchange.response.statusCode = HttpStatus.BAD_REQUEST
                Mono.empty<Void>().awaitSingleOrNull()
                return@mono null
            }

            val effectiveIp = ip?.takeIf { it.isNotBlank() } ?: "unknown"
            val (ipResult, userResult) = try {
                consumeBoth(effectiveIp, userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) {
                    "OR rate limit check failed for ip=$effectiveIp userId=$userId; failing open"
                }
                chain.filter(exchange).awaitSingleOrNull()
                return@mono null
            }

            when (decideOrRateLimit(ipResult, userResult)) {
                OrRateLimitDecision.REJECT -> {
                    writeHeaders(exchange, ipResult, userResult)
                    reject(exchange, ipResult, userResult)
                }

                OrRateLimitDecision.FAIL_OPEN -> {
                    log.warn {
                        "OR rate limit check failed for ip=$effectiveIp userId=$userId; failing open"
                    }
                    chain.filter(exchange).awaitSingleOrNull()
                }

                OrRateLimitDecision.ALLOW -> {
                    writeHeaders(exchange, ipResult, userResult)
                    log.trace {
                        "OR rate limit consumed for ip=$effectiveIp userId=$userId " +
                            "remaining=${minOf(ipResult.availableTokens, userResult.availableTokens)}"
                    }
                    chain.filter(exchange).awaitSingleOrNull()
                }
            }
            return@mono null
        }

    private suspend fun consumeBoth(ip: String, userId: String): Pair<RateLimitResult, RateLimitResult> =
        coroutineScope {
            val ipDeferred = async { ipRateLimiter.consume("$IP_KEY_PREFIX$ip", 1L) }
            val userDeferred = async { userRateLimiter.consume("$USER_KEY_PREFIX$userId", 1L) }
            ipDeferred.await() to userDeferred.await()
        }

    private fun writeHeaders(
        exchange: ServerWebExchange,
        ipResult: RateLimitResult,
        userResult: RateLimitResult,
    ) {
        exchange.response.headers.set(
            HeaderConstants.X_RATELIMIT_REMAINING,
            minOf(ipResult.availableTokens, userResult.availableTokens).toString(),
        )
        val resetSecs = maxOf(
            ipResult.diagnostics.nanosToWaitForReset,
            userResult.diagnostics.nanosToWaitForReset,
        ).nanos().toSeconds().coerceAtLeast(0)
        exchange.response.headers.set(HeaderConstants.X_RATELIMIT_RESET, resetSecs.toString())
    }

    private suspend fun reject(
        exchange: ServerWebExchange,
        ipResult: RateLimitResult,
        userResult: RateLimitResult,
    ) {
        val retryAfterSecs = listOf(ipResult, userResult)
            .filter { it.isRejected }
            .mapNotNull { it.retryAfter?.toSeconds() }
            .maxOrNull()
            ?.coerceAtLeast(1)
            ?: 1L

        exchange.response.headers.set(HeaderConstants.RETRY_AFTER, retryAfterSecs.toString())
        log.warn {
            "OR rate limit exceeded. ipRemaining=${ipResult.availableTokens}, " +
                "userRemaining=${userResult.availableTokens}, retryAfter=${retryAfterSecs}s"
        }
        exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        Mono.empty<Void>().awaitSingleOrNull()
    }
}

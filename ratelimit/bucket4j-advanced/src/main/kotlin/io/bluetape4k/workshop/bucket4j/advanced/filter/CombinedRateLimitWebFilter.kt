package io.bluetape4k.workshop.bucket4j.advanced.filter

import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedSuspendRateLimiter
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.trace
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.bucket4j.advanced.utils.HeaderConstants
import io.bluetape4k.workshop.bucket4j.advanced.utils.RequestUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

/**
 * `/api/sensitive/` 요청에 combined IP+userId rate limit을 적용하는 [WebFilter]입니다.
 *
 * ## 전략
 * 단일 Redis bucket을 `"combined:$ip:$userId"` 복합 문자열로 식별합니다.
 * 따라서 각 (IP, user) 쌍이 독립 quota를 가지며, 한 사용자가 다른 사용자의 quota를 소진하지 못하고
 * NAT 같은 공유 IP도 뒤쪽의 모든 사용자에게 불이익을 주지 않습니다.
 *
 * ## 동작 계약
 * - path가 `/api/sensitive`로 시작하는 요청만 가로챕니다.
 * - IP와 `X-User-ID` header가 모두 필요하며, 어느 하나라도 없으면 HTTP 400으로 응답합니다.
 * - combined bucket이 소진되면 HTTP 429와 `Retry-After`로 응답합니다.
 * - 내부 오류가 나면 서비스 중단을 피하려고 fail-open으로 통과시킵니다.
 */
@Component
@Order(12)
class CombinedRateLimitWebFilter(
    @Qualifier("combinedRateLimiter")
    private val rateLimiter: DistributedSuspendRateLimiter,
    @Value("\${ratelimit.trust-proxy:false}")
    private val trustProxy: Boolean = false,
) : WebFilter {

    companion object : KLoggingChannel() {
        private const val TARGET_PATH_PREFIX = "/api/sensitive"
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        mono(Dispatchers.IO) {
            val path = exchange.request.uri.path
            if (!path.startsWith(TARGET_PATH_PREFIX)) {
                chain.filter(exchange).awaitSingleOrNull()
            } else {
                val ip = RequestUtils.extractIp(exchange, trustProxy)
                val userId = RequestUtils.extractUserId(exchange)
                log.trace { "Combined rate limit: path=$path, ip=$ip, userId=$userId" }

                if (userId.isNullOrBlank()) {
                    // userId는 항상 필요합니다. IP를 얻지 못하면 "unknown"으로 fallback합니다.
                    log.warn { "Missing X-User-ID for combined rate limit. path=$path" }
                    exchange.response.statusCode = HttpStatus.BAD_REQUEST
                    Mono.empty<Void>().awaitSingleOrNull()
                } else {
                    val effectiveIp = ip?.takeIf { it.isNotBlank() } ?: "unknown"
                    try {
                        val key = "combined:$effectiveIp:$userId"
                        val result = rateLimiter.consume(key, 1L)
                        exchange.response.headers.set(
                            HeaderConstants.X_RATELIMIT_REMAINING,
                            result.availableTokens.toString()
                        )
                        val resetSecs =
                            TimeUnit.NANOSECONDS.toSeconds(result.diagnostics.nanosToWaitForReset).coerceAtLeast(0)
                        exchange.response.headers.set(HeaderConstants.X_RATELIMIT_RESET, resetSecs.toString())
                        if (result.isConsumed) {
                            log.trace { "Combined bucket[$key] consumed, remaining=${result.availableTokens}" }
                            chain.filter(exchange).awaitSingleOrNull()
                        } else {
                            val retryAfterSecs = result.retryAfter
                                ?.let { TimeUnit.NANOSECONDS.toSeconds(it.toNanos()).coerceAtLeast(1) }
                                ?: 1L
                            exchange.response.headers.set(HeaderConstants.RETRY_AFTER, retryAfterSecs.toString())
                            log.warn { "Combined rate limit exceeded for $key. retryAfter=${retryAfterSecs}s" }
                            exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                            Mono.empty<Void>().awaitSingleOrNull()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "Combined rate limit check failed for ip=$effectiveIp userId=$userId; failing open" }
                        chain.filter(exchange).awaitSingleOrNull()
                    }
                }
            }
        }
}

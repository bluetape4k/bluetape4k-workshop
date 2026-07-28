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
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

/**
 * `/api/authenticated/` 요청에 user ID 기반 rate limiting을 적용하는 [WebFilter]입니다.
 *
 * ## 동작 계약
 * - path가 `/api/authenticated`로 시작하는 요청만 가로챕니다.
 * - user ID는 `X-User-ID` request header에서 읽습니다.
 * - header가 없으면 HTTP 401(authentication required)로 응답합니다.
 * - bucket이 소진되면 HTTP 429와 `Retry-After` header로 응답합니다.
 * - 내부 오류가 나면 서비스 중단을 피하려고 fail-open으로 통과시킵니다.
 */
@Component
@Order(11)
class UserRateLimitWebFilter(
    @Qualifier("userRateLimiter")
    private val rateLimiter: DistributedSuspendRateLimiter,
) : WebFilter {

    companion object : KLoggingChannel() {
        private const val TARGET_PATH_PREFIX = "/api/authenticated"
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        mono(Dispatchers.IO) {
            val path = exchange.request.uri.path
            if (!path.startsWith(TARGET_PATH_PREFIX)) {
                chain.filter(exchange).awaitSingleOrNull()
            } else {
                val userId = RequestUtils.extractUserId(exchange)
                log.trace { "User rate limit: path=$path, userId=$userId" }

                if (userId.isNullOrBlank()) {
                    log.warn { "Missing X-User-ID header for path=$path" }
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    Mono.empty<Void>().awaitSingleOrNull()
                } else {
                    try {
                        val key = "user:$userId"
                        val result = rateLimiter.consume(key, 1L)
                        exchange.response.headers.set(
                            HeaderConstants.X_RATELIMIT_REMAINING,
                            result.availableTokens.toString()
                        )
                        val resetSecs =
                            TimeUnit.NANOSECONDS.toSeconds(result.diagnostics.nanosToWaitForReset).coerceAtLeast(0)
                        exchange.response.headers.set(HeaderConstants.X_RATELIMIT_RESET, resetSecs.toString())
                        if (result.isConsumed) {
                            log.trace { "User bucket[$key] consumed, remaining=${result.availableTokens}" }
                            chain.filter(exchange).awaitSingleOrNull()
                        } else {
                            val retryAfterSecs = result.retryAfter
                                ?.let { TimeUnit.NANOSECONDS.toSeconds(it.toNanos()).coerceAtLeast(1) }
                                ?: 1L
                            exchange.response.headers.set(HeaderConstants.RETRY_AFTER, retryAfterSecs.toString())
                            log.warn { "User rate limit exceeded for $key. retryAfter=${retryAfterSecs}s" }
                            exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                            Mono.empty<Void>().awaitSingleOrNull()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "User rate limit check failed for userId=$userId; failing open" }
                        chain.filter(exchange).awaitSingleOrNull()
                    }
                }
            }
        }
}

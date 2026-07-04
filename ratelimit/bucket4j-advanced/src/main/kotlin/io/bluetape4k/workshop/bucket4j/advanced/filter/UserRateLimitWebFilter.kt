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
 * WebFilter that applies user ID-based rate limiting to `/api/authenticated/` requests.
 *
 * ## Behavior / Contract
 * - Only intercepts requests whose path starts with `/api/authenticated`.
 * - User ID is read from the `X-User-ID` request header.
 * - When the header is absent, responds with HTTP 401 (authentication required).
 * - When the bucket is exhausted, responds with HTTP 429 and a `Retry-After` header.
 * - On internal errors, fails open to avoid service disruption.
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

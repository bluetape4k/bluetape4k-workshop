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
 * WebFilter that applies a combined IP+userId rate limit to `/api/sensitive/` requests.
 *
 * ## Strategy
 * A single Redis bucket is keyed by the composite string `"combined:$ip:$userId"`.
 * This means each (IP, user) pair has its own independent quota — a user cannot exhaust
 * another user's quota, and a shared IP (e.g. NAT) does not penalise all users behind it.
 *
 * ## Behavior / Contract
 * - Only intercepts requests whose path starts with `/api/sensitive`.
 * - Both IP and `X-User-ID` header are required; either missing leads to HTTP 400.
 * - When the combined bucket is exhausted, responds with HTTP 429 + `Retry-After`.
 * - On internal errors, fails open to avoid service disruption.
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
                    // userId is always required; IP falls back to "unknown" if unavailable
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

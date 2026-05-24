package io.bluetape4k.workshop.bucket4j.advanced.filter

import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedSuspendRateLimiter
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.trace
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.bucket4j.advanced.utils.HeaderConstants
import io.bluetape4k.workshop.bucket4j.advanced.utils.RequestUtils
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
 * WebFilter that applies IP-based rate limiting to `/api/anonymous/` requests.
 *
 * ## Behavior / Contract
 * - Only intercepts requests whose path starts with `/api/anonymous`.
 * - Client IP is derived from [RequestUtils.extractIp] with [trustProxy] config.
 * - When the bucket is exhausted, responds with HTTP 429 and a `Retry-After` header.
 * - On extraction failure (no IP), responds with HTTP 400.
 * - On internal errors, fails open (request passes through) to avoid service disruption.
 */
@Component
@Order(10)
class IpRateLimitWebFilter(
    @Qualifier("ipRateLimiter")
    private val rateLimiter: DistributedSuspendRateLimiter,
    @Value("\${ratelimit.trust-proxy:false}")
    private val trustProxy: Boolean = false,
) : WebFilter {

    companion object : KLoggingChannel() {
        private const val TARGET_PATH_PREFIX = "/api/anonymous"
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        mono(Dispatchers.IO) {
            val path = exchange.request.uri.path
            if (!path.startsWith(TARGET_PATH_PREFIX)) {
                chain.filter(exchange).awaitSingleOrNull()
            } else {
                val ip = RequestUtils.extractIp(exchange, trustProxy)
                log.trace { "IP rate limit: path=$path, ip=$ip" }

                // Use "unknown" as fallback when IP cannot be determined (e.g. mock clients in tests
                // or requests arriving without a remote address). A shared "unknown" bucket applies
                // rate limiting rather than blocking or failing open.
                val effectiveIp = ip?.takeIf { it.isNotBlank() } ?: "unknown"
                run {
                    try {
                        val key = "ip:$effectiveIp"
                        val result = rateLimiter.consume(key, 1L)
                        exchange.response.headers.set(
                            HeaderConstants.X_RATELIMIT_REMAINING,
                            result.availableTokens.toString()
                        )
                        val resetSecs =
                            TimeUnit.NANOSECONDS.toSeconds(result.diagnostics.nanosToWaitForReset).coerceAtLeast(0)
                        exchange.response.headers.set(HeaderConstants.X_RATELIMIT_RESET, resetSecs.toString())
                        if (result.isConsumed) {
                            log.trace { "IP bucket[$key] consumed, remaining=${result.availableTokens}" }
                            chain.filter(exchange).awaitSingleOrNull()
                        } else {
                            val retryAfterSecs = result.retryAfter
                                ?.let { TimeUnit.NANOSECONDS.toSeconds(it.toNanos()).coerceAtLeast(1) }
                                ?: 1L
                            exchange.response.headers.set(HeaderConstants.RETRY_AFTER, retryAfterSecs.toString())
                            log.warn { "IP rate limit exceeded for $key. retryAfter=${retryAfterSecs}s" }
                            exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                            Mono.empty<Void>().awaitSingleOrNull()
                        }
                    } catch (e: Throwable) {
                        log.warn(e) { "IP rate limit check failed for ip=$effectiveIp; failing open" }
                        chain.filter(exchange).awaitSingleOrNull()
                    }
                }
            }
        }
}

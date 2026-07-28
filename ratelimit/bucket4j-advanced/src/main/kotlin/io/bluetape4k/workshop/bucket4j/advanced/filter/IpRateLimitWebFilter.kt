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
 * `/api/anonymous/` 요청에 IP 기반 rate limiting을 적용하는 [WebFilter]입니다.
 *
 * ## 동작 계약
 * - path가 `/api/anonymous`로 시작하는 요청만 가로챕니다.
 * - client IP는 [trustProxy] 설정을 반영해 [RequestUtils.extractIp]에서 추출합니다.
 * - bucket이 소진되면 HTTP 429와 `Retry-After` header로 응답합니다.
 * - IP를 추출하지 못하면 HTTP 400으로 응답합니다.
 * - 내부 오류가 나면 서비스 중단을 피하려고 fail-open으로 통과시킵니다.
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

                // IP를 알 수 없으면 "unknown"을 fallback으로 사용합니다(테스트 mock client나 remote address가 없는 요청 등).
                // 공유 "unknown" bucket을 적용해 차단하거나 fail-open하지 않고 rate limiting을 유지합니다.
                val effectiveIp = ip?.takeIf { it.isNotBlank() } ?: "unknown"
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "IP rate limit check failed for ip=$effectiveIp; failing open" }
                    chain.filter(exchange).awaitSingleOrNull()
                }
            }
        }
}

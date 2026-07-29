package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID
import java.util.concurrent.TimeUnit

/** command body나 credential을 기록하지 않고 HTTP boundary를 로그로 남깁니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class RequestLoggingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val requestId =
            request
                .getHeader(REQUEST_ID_HEADER)
                ?.takeIf(REQUEST_ID_PATTERN::matches)
                ?: UUID.randomUUID().toString()
        response.setHeader(REQUEST_ID_HEADER, requestId)
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)
        val startedAt = System.nanoTime()
        try {
            chain.doFilter(request, response)
        } finally {
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            log.info {
                "reservation_http_completed requestId=$requestId method=${request.method} " +
                    "path=${request.requestURI} status=${response.status} elapsedMillis=$elapsedMillis"
            }
        }
    }

    companion object : KLogging() {
        private const val REQUEST_ID_HEADER = "X-Request-Id"
        const val REQUEST_ID_ATTRIBUTE = "reservation.requestId"
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._-]{8,80}")
    }
}

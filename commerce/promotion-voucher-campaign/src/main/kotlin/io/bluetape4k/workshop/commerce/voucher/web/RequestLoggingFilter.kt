package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Adds a bounded correlation id, browser hardening headers, and secret-free access logs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class RequestLoggingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)?.takeIf(::isSafeRequestId) ?: newRequestId()
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        response.setHeader("Content-Security-Policy", CSP)
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Cache-Control", "no-store")
        val started = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            log.info {
                "voucher_http_completed method=${request.method} path=${request.requestURI} " +
                    "status=${response.status} requestId=$requestId elapsedMillis=$elapsedMillis"
            }
        }
    }

    private fun isSafeRequestId(value: String): Boolean =
        value.length in 1..64 && value.all { it.code in 0x21..0x7e }

    companion object : KLogging() {
        private const val CSP =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
    }
}

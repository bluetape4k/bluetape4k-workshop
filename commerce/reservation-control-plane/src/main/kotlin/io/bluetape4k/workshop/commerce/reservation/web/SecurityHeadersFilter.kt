package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** reservation 및 owner-specific browser state가 cache되거나 다른 origin에 embedded되지 못하게 합니다. */
@Component
internal class SecurityHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("X-Content-Type-Options", "nosniff")
        chain.doFilter(request, response)
        log.debug { "reservation_security_headers_applied status=${response.status}" }
    }

    companion object : KLogging()
}

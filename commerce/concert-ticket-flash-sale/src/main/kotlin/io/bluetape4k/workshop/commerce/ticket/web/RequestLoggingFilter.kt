package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import io.bluetape4k.idgenerators.uuid.Uuid

/** 명시적인 metadata allowlist만 로그로 남깁니다. header, identity, key, body, query string은 제외합니다. */
class RequestLoggingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val correlationId = request.getHeader("X-Correlation-Id")?.takeIf { it.length in 8..64 }
            ?: Uuid.V7.nextId().toString()
        response.setHeader("X-Correlation-Id", correlationId)
        try {
            chain.doFilter(request, response)
        } finally {
            log.info {
                "ticket_http method=${request.method} path=${request.requestURI} status=${response.status} correlationId=$correlationId"
            }
        }
    }

    companion object : KLogging()
}

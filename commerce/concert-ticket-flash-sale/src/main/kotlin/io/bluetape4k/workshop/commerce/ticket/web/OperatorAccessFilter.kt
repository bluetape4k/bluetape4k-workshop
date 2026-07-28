package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.support.requireGe
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/** demo 전용 operator authentication입니다. TCP peer가 loopback이 아니면 credential을 거부합니다. */
class OperatorAccessFilter(private val expectedToken: String) : OncePerRequestFilter() {
    init {
        expectedToken.length.requireGe(32, "expectedToken.length")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.requestURI.startsWith("/api/v1/operator/")) {
            val token = request.getHeader("X-Demo-Operator")
            if (!request.remoteAddr.isLoopbackLiteral() || !constantTimeEquals(token, expectedToken)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                return
            }
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
                "demo-operator",
                null,
                listOf(SimpleGrantedAuthority("ROLE_OPERATOR")),
            )
        }
        chain.doFilter(request, response)
    }

    private fun constantTimeEquals(candidate: String?, expected: String): Boolean {
        if (candidate == null || candidate.length != expected.length) return false
        var mismatch = 0
        candidate.indices.forEach { mismatch = mismatch or (candidate[it].code xor expected[it].code) }
        return mismatch == 0
    }
}

internal fun String.isLoopbackLiteral(): Boolean = this == "127.0.0.1" || this == "::1" || this == "0:0:0:0:0:0:0:1"

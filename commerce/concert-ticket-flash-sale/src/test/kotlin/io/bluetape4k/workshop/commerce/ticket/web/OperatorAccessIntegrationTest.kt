package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.shouldBeEqualTo
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

internal class OperatorAccessIntegrationTest {
    @Test
    fun `demo operator credential is rejected from a non loopback peer`() {
        val token = "x".repeat(32)
        val request = MockHttpServletRequest("POST", "/api/v1/operator/reconciliation-runs").apply {
            remoteAddr = "203.0.113.8"
            addHeader("X-Demo-Operator", token)
        }
        val response = MockHttpServletResponse()

        OperatorAccessFilter(token).doFilter(request, response, MockFilterChain())

        response.status shouldBeEqualTo HttpServletResponse.SC_UNAUTHORIZED
    }
}

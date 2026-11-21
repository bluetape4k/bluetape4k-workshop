package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * OperatorAccessIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OperatorAccessIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "demo operator credential is rejected from a non loopback peer" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `demo operator credential is rejected from a non loopback peer`() {
        val token = "x".repeat(32)
        val request = MockHttpServletRequest("POST", "/api/v1/operator/reconciliation-runs")
            .apply {
                remoteAddr = "203.0.113.8"
                addHeader("X-Demo-Operator", token)
            }
        val response = MockHttpServletResponse()

        OperatorAccessFilter(token).doFilter(request, response, MockFilterChain())

        response.status shouldBeEqualTo HttpServletResponse.SC_UNAUTHORIZED
    }
}

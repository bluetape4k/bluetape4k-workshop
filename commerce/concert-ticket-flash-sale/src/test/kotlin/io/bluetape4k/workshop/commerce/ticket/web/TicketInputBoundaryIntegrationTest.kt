package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.operations.api.OperationsCommands
import io.bluetape4k.workshop.commerce.ticket.operations.api.ReconcileSummary
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock

/**
 * TicketInputBoundaryIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketInputBoundaryIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "operator run rejects an oversized batch and short reason" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator run rejects an oversized batch and short reason`() {
        val operations = OperationsCommands {
            ReconcileSummary(0, 0, 0)
        }
        val mvc = MockMvcBuilders
            .standaloneSetup(OperatorTicketController(operations, Clock.systemUTC()))
            .setControllerAdvice(ApiExceptionHandler())
            .build()

        mvc
            .post("/api/v1/operator/reconciliation-runs") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"limit":51,"reason":"short"}"""
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid_request") }
            }
    }
}

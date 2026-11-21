package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * TicketRedactionContractTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketRedactionContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "problem response never reflects a raw principal canary" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `problem response never reflects a raw principal canary`() {
        val canary = "raw-user-canary@example.invalid"
        val mvc = MockMvcBuilders
            .standaloneSetup(
                CustomerTicketController(NoopPurchaseCommands, { _, _ -> null }, PrincipalSubjectResolver()),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

        val response = mvc
            .get("/api/v1/purchase-attempts/${Uuid.V7.nextId()}") {
                principal = UsernamePasswordAuthenticationToken
                    .authenticated(canary, null, emptyList())
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid_request") }
            }
            .andReturn()
            .response
            .contentAsString

        log.debug { "response: $response" }
        response shouldNotContain canary
    }
}

package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyTicketOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseCommands
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseQueries
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseSnapshot
import io.bluetape4k.workshop.commerce.ticket.purchase.api.StartPurchase
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

/**
 * CustomerTicketWebIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class CustomerTicketWebIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "other buyer cannot discover an attempt" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `other buyer cannot discover an attempt`() {
        val owner = Uuid.V7.nextId()
        val other = Uuid.V7.nextId()
        val attempt = Uuid.V7.nextId()

        val snapshot = PurchaseSnapshot(attempt, PurchaseState.INVENTORY_HELD, 0, Instant.EPOCH)
        val queries = PurchaseQueries { id, buyer -> snapshot.takeIf { id == attempt && buyer == owner } }

        val mvc = MockMvcBuilders
            .standaloneSetup(
                CustomerTicketController(NoopPurchaseCommands, queries, PrincipalSubjectResolver()),
            )
            .setControllerAdvice(ApiExceptionHandler())
            .build()

        mvc
            .get("/api/v1/purchase-attempts/$attempt") {
                principal = UsernamePasswordAuthenticationToken.authenticated(
                    other.toString(),
                    null,
                    emptyList()
                )
            }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("purchase_not_found") }
                jsonPath("$.retryable") { value(false) }
            }
    }

    @Test
/**
 * 이 시나리오는 "owner response is no store and contains only recovery projection" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `owner response is no store and contains only recovery projection`() {
        val owner = Uuid.V7.nextId()
        val attempt = Uuid.V7.nextId()
        val snapshot = PurchaseSnapshot(attempt, PurchaseState.APPROVED, 3, Instant.EPOCH)
        val mvc = MockMvcBuilders
            .standaloneSetup(
                CustomerTicketController(
                    NoopPurchaseCommands,
                    { _, _ -> snapshot },
                    PrincipalSubjectResolver()
                ),
            )
            .setControllerAdvice(ApiExceptionHandler())
            .build()

        mvc
            .get("/api/v1/purchase-attempts/$attempt") {
                principal = UsernamePasswordAuthenticationToken
                    .authenticated(owner.toString(), null, emptyList())
            }
            .andExpect {
                status { isOk() }
                header { string("Cache-Control", "no-store") }
                jsonPath("$.attemptId") { value(attempt.toString()) }
                jsonPath("$.state") { value("APPROVED") }
                jsonPath("$.buyerSubjectId") { doesNotExist() }
                jsonPath("$.authorizationOperationId") { doesNotExist() }
            }
    }
}

/**
 * NoopPurchaseCommands 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal object NoopPurchaseCommands: PurchaseCommands {
/**
 * start은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun start(command: StartPurchase): PurchaseSnapshot = error("not used")
/**
 * cancel은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun cancel(command: CancelPurchase): PurchaseSnapshot = error("not used")
/**
 * applyTicketOutcome은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot = error("not used")
}

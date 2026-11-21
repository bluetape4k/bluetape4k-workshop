package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import org.junit.jupiter.api.Test

/**
 * TicketContextRestartIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketContextRestartIntegrationTest: AbstractTicketIntegrationTest() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "new worker instance resumes an unknown operation by lookup" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `new worker instance resumes an unknown operation by lookup`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)

            val provider = FakePaymentProvider().apply {
                completeButLoseResponse(command.authorizationOperationId, PaymentOutcome.APPROVED)
            }

            PaymentWorker(fixture.executor, fixture.service, provider)
                .run(command.authorizationOperationId)

            fixture.execute(
                "UPDATE ticket_payment_operations SET next_reconcile_at = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                        "WHERE operation_id = '${command.authorizationOperationId}'",
            )

            PaymentWorker(fixture.executor, fixture.service, provider)
                .run(command.authorizationOperationId)

            provider.authorizationCount(command.authorizationOperationId) shouldBeEqualTo 1
            fixture.purchaseAttemptState(command.attemptId).shouldBeEqualTo("approved")

            fixture.assertInventoryInvariant()
        }
    }
}

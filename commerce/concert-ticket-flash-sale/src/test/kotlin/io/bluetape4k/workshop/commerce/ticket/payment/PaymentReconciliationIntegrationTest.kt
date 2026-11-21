package io.bluetape4k.workshop.commerce.ticket.payment

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyResult
import org.junit.jupiter.api.Test

/**
 * PaymentReconciliationIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class PaymentReconciliationIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "stale claim cannot apply a late provider response" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stale claim cannot apply a late provider response`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)

            val worker = PaymentWorker(fixture.executor, fixture.service, FakePaymentProvider())
            val first = checkNotNull(worker.claim(command.authorizationOperationId))
            fixture.execute(
                "UPDATE ticket_payment_operations SET claim_until = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                        "WHERE operation_id = '${command.authorizationOperationId}'",
            )
            val second = worker.claim(command.authorizationOperationId).shouldNotBeNull()

            worker.apply(first, PaymentOutcome.APPROVED) shouldBeEqualTo ApplyResult.STALE
            worker.apply(second, PaymentOutcome.APPROVED) shouldBeEqualTo ApplyResult.APPLIED

            fixture.attemptState(command.attemptId) shouldBeEqualTo "approved"
            fixture.inventoryCounts() shouldBeEqualTo (0 to 1)
        }
    }

    @Test
/**
 * 이 시나리오는 "lookup first converges after provider success response is lost" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `lookup first converges after provider success response is lost`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)

            val provider = FakePaymentProvider()
            provider.completeButLoseResponse(command.authorizationOperationId, PaymentOutcome.APPROVED)

            val worker = PaymentWorker(fixture.executor, fixture.service, provider)
            worker.run(command.authorizationOperationId) shouldBeEqualTo ApplyResult.APPLIED

            fixture.attemptState(command.attemptId) shouldBeEqualTo "reconciliation_required"
            fixture.execute(
                "UPDATE ticket_payment_operations SET next_reconcile_at = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                        "WHERE operation_id = '${command.authorizationOperationId}'",
            )
            worker.run(command.authorizationOperationId) shouldBeEqualTo ApplyResult.APPLIED

            provider.authorizationCount(command.authorizationOperationId) shouldBeEqualTo 1
            fixture.attemptState(command.attemptId) shouldBeEqualTo "approved"
            fixture.inventoryCounts() shouldBeEqualTo (0 to 1)
            fixture.orderCount(command.attemptId) shouldBeEqualTo 1
        }
    }
}

/**
 * attemptState은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun PurchaseFixture.attemptState(attemptId: java.util.UUID): String =
    purchaseAttemptState(attemptId)

/**
 * inventoryCounts은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun PurchaseFixture.inventoryCounts(): Pair<Int, Int> =
    inventoryQuantities().let { it.held to it.sold }

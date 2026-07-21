package io.bluetape4k.workshop.commerce.ticket.payment

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyResult
import org.junit.jupiter.api.Test

internal class PaymentReconciliationIntegrationTest {
    @Test
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
            val second = checkNotNull(worker.claim(command.authorizationOperationId))

            worker.apply(first, PaymentOutcome.APPROVED) shouldBeEqualTo ApplyResult.STALE
            worker.apply(second, PaymentOutcome.APPROVED) shouldBeEqualTo ApplyResult.APPLIED
            fixture.attemptState(command.attemptId) shouldBeEqualTo "approved"
            fixture.inventoryCounts() shouldBeEqualTo (0 to 1)
        }
    }

    @Test
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
            fixture.queryInt("SELECT COUNT(*) FROM ticket_orders WHERE attempt_id = '${command.attemptId}'") shouldBeEqualTo 1
        }
    }
}

private fun PurchaseFixture.attemptState(attemptId: java.util.UUID): String =
    queryString("SELECT state FROM ticket_purchase_attempts WHERE attempt_id = '$attemptId'")

private fun PurchaseFixture.inventoryCounts(): Pair<Int, Int> =
    queryInt("SELECT held_quantity FROM ticket_inventory WHERE sale_id = '$saleId' AND grade = 'GENERAL'") to
        queryInt("SELECT sold_quantity FROM ticket_inventory WHERE sale_id = '$saleId' AND grade = 'GENERAL'")

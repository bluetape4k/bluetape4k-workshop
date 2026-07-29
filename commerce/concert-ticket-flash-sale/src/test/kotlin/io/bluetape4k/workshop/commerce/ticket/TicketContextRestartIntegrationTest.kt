package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import org.junit.jupiter.api.Test

internal class TicketContextRestartIntegrationTest : AbstractTicketIntegrationTest() {
    @Test
    fun `new worker instance resumes an unknown operation by lookup`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)
            val provider = FakePaymentProvider().apply {
                completeButLoseResponse(command.authorizationOperationId, PaymentOutcome.APPROVED)
            }
            PaymentWorker(fixture.executor, fixture.service, provider).run(command.authorizationOperationId)
            fixture.execute(
                "UPDATE ticket_payment_operations SET next_reconcile_at = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                    "WHERE operation_id = '${command.authorizationOperationId}'",
            )

            PaymentWorker(fixture.executor, fixture.service, provider).run(command.authorizationOperationId)

            provider.authorizationCount(command.authorizationOperationId) shouldBeEqualTo 1
            fixture.queryString("SELECT state FROM ticket_purchase_attempts WHERE attempt_id = '${command.attemptId}'")
                .shouldBeEqualTo("approved")
            fixture.assertInventoryInvariant()
        }
    }
}

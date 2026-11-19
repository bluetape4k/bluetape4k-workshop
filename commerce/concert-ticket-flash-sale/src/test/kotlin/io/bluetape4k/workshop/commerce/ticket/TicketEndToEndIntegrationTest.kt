package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.FakeTicketProvider
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.TicketEffectWorker
import org.junit.jupiter.api.Test

internal class TicketEndToEndIntegrationTest : AbstractTicketIntegrationTest() {

    companion object: KLogging()
    
    @Test
    fun `admitted purchase converges through payment and one ticket issue`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)
            val payments = FakePaymentProvider()
                .apply { complete(command.authorizationOperationId, PaymentOutcome.APPROVED) }

            PaymentWorker(fixture.executor, fixture.service, payments)
                .run(command.authorizationOperationId)

            val effectId = fixture.issueEffectOperationId()

            TicketEffectWorker(fixture.executor, fixture.service, FakeTicketProvider())
                .run(effectId)

            fixture.purchaseAttemptState(command.attemptId).shouldBeEqualTo("approved")

            fixture.ticketState().shouldBeEqualTo("issued")
            payments.authorizationCount(command.authorizationOperationId) shouldBeEqualTo 0

            fixture.assertInventoryInvariant()
            fixture.assertNoDuplicateEffects()
        }
    }
}

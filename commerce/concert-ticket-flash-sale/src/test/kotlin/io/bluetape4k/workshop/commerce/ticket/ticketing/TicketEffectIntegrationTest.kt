package io.bluetape4k.workshop.commerce.ticket.ticketing

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.FakeTicketProvider
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.TicketEffectWorker
import org.junit.jupiter.api.Test

internal class TicketEffectIntegrationTest {

    companion object: KLogging()

    @Test
    fun `response loss and duplicate delivery issue one external ticket and one receipt`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)
            val payments = FakePaymentProvider().apply {
                complete(command.authorizationOperationId, PaymentOutcome.APPROVED)
            }
            PaymentWorker(fixture.executor, fixture.service, payments).run(command.authorizationOperationId)
            val operationId = fixture.issueEffectOperationId()
            val provider = FakeTicketProvider().apply { succeedButLoseResponse(operationId) }
            val worker = TicketEffectWorker(fixture.executor, fixture.service, provider)

            worker.run(operationId).shouldBeTrue()
            fixture.expireEffectClaim(operationId)
            worker.run(operationId).shouldBeTrue()
            worker.run(operationId).shouldBeFalse()

            provider.issueCount(operationId) shouldBeEqualTo 1
            fixture.ticketState() shouldBeEqualTo "issued"
            fixture.ticketDisposition() shouldBeEqualTo "issued"
            fixture.effectReceiptCount(operationId).shouldBeEqualTo(1)
        }
    }
}

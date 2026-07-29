package io.bluetape4k.workshop.commerce.ticket.ticketing

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.FakeTicketProvider
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.TicketEffectWorker
import org.junit.jupiter.api.Test

internal class TicketEffectIntegrationTest {
    @Test
    fun `response loss and duplicate delivery issue one external ticket and one receipt`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)
            val payments = FakePaymentProvider().apply {
                complete(command.authorizationOperationId, PaymentOutcome.APPROVED)
            }
            PaymentWorker(fixture.executor, fixture.service, payments).run(command.authorizationOperationId)
            val operationId = fixture.queryUuid("SELECT operation_id FROM ticket_effect_operations WHERE effect_kind = 'issue'")
            val provider = FakeTicketProvider().apply { succeedButLoseResponse(operationId) }
            val worker = TicketEffectWorker(fixture.executor, fixture.service, provider)

            worker.run(operationId).shouldBeEqualTo(true)
            fixture.execute(
                "UPDATE ticket_effect_operations SET claim_until = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                    "WHERE operation_id = '$operationId'",
            )
            worker.run(operationId).shouldBeEqualTo(true)
            worker.run(operationId).shouldBeEqualTo(false)

            provider.issueCount(operationId).shouldBeEqualTo(1)
            fixture.queryString("SELECT state FROM ticket_tickets").shouldBeEqualTo("issued")
            fixture.queryString("SELECT ticket_disposition FROM ticket_orders").shouldBeEqualTo("issued")
            fixture.queryInt("SELECT COUNT(*) FROM ticket_effect_receipts WHERE operation_id = '$operationId'")
                .shouldBeEqualTo(1)
        }
    }
}

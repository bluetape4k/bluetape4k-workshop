package io.bluetape4k.workshop.commerce.ticket.purchase

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.FakeRefundProvider
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.RefundService
import org.junit.jupiter.api.Test

internal class RefundRaceIntegrationTest {
    @Test
    fun `late approval suppresses ticket issue and restocks only after refund succeeds`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)
            fixture.execute(
                "UPDATE ticket_purchase_attempts SET state = 'payment_authorizing' " +
                    "WHERE attempt_id = '${command.attemptId}'",
            )
            fixture.service.cancel(CancelPurchase(command.attemptId, command.buyerSubjectId))

            val paymentProvider = FakePaymentProvider().apply {
                complete(command.authorizationOperationId, PaymentOutcome.APPROVED)
            }
            PaymentWorker(fixture.executor, fixture.service, paymentProvider).run(command.authorizationOperationId)

            fixture.queryString("SELECT state FROM ticket_purchase_attempts WHERE attempt_id = '${command.attemptId}'")
                .shouldBeEqualTo("refund_pending")
            fixture.queryInt("SELECT sold_quantity FROM ticket_inventory WHERE sale_id = '${fixture.saleId}' AND grade = 'GENERAL'")
                .shouldBeEqualTo(1)
            fixture.queryInt("SELECT COUNT(*) FROM ticket_tickets").shouldBeEqualTo(0)
            fixture.queryInt("SELECT COUNT(*) FROM ticket_effect_operations").shouldBeEqualTo(0)

            val refundOperationId = fixture.queryUuid(
                "SELECT refund_operation_id FROM ticket_orders WHERE attempt_id = '${command.attemptId}'",
            )
            val refundProvider = FakeRefundProvider().apply { succeed(refundOperationId) }
            val refunds = RefundService(fixture.executor, refundProvider)

            refunds.run(refundOperationId).shouldBeEqualTo(true)
            refunds.run(refundOperationId).shouldBeEqualTo(false)

            refundProvider.refundCount(refundOperationId).shouldBeEqualTo(1)
            fixture.queryString("SELECT state FROM ticket_purchase_attempts WHERE attempt_id = '${command.attemptId}'")
                .shouldBeEqualTo("refunded")
            fixture.queryInt("SELECT sold_quantity FROM ticket_inventory WHERE sale_id = '${fixture.saleId}' AND grade = 'GENERAL'")
                .shouldBeEqualTo(0)
            fixture.queryInt("SELECT COUNT(*) FROM ticket_active_identity_guards WHERE active_attempt_id = '${command.attemptId}'")
                .shouldBeEqualTo(0)
            fixture.queryInt("SELECT COUNT(*) FROM ticket_effect_receipts WHERE operation_id = '$refundOperationId'")
                .shouldBeEqualTo(1)
        }
    }
}

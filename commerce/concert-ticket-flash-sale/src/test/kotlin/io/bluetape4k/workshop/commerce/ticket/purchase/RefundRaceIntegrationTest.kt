package io.bluetape4k.workshop.commerce.ticket.purchase

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.FakeRefundProvider
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.RefundService
import org.junit.jupiter.api.Test

internal class RefundRaceIntegrationTest {

    companion object: KLogging()

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

            fixture.purchaseAttemptState(command.attemptId).shouldBeEqualTo("refund_pending")
            fixture.inventorySold().shouldBeEqualTo(1)
            fixture.ticketCount().shouldBeEqualTo(0)
            fixture.effectOperationCount().shouldBeEqualTo(0)

            val refundOperationId = fixture.refundOperationId(command.attemptId)
            val refundProvider = FakeRefundProvider().apply { succeed(refundOperationId) }
            val refunds = RefundService(fixture.executor, refundProvider)

            refunds.run(refundOperationId).shouldBeTrue()
            refunds.run(refundOperationId).shouldBeFalse()

            refundProvider.refundCount(refundOperationId) shouldBeEqualTo 1

            fixture.purchaseAttemptState(command.attemptId).shouldBeEqualTo("refunded")
            fixture.inventorySold().shouldBeEqualTo(0)
            fixture.activeIdentityGuardCount(command.attemptId).shouldBeEqualTo(0)
            fixture.effectReceiptCount(refundOperationId).shouldBeEqualTo(1)
        }
    }
}

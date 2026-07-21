package io.bluetape4k.workshop.commerce.ticket.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant

internal class TicketTransitionsTest {
    @Test
    fun `payment timeout preserves held inventory for reconciliation`() {
        transition(PurchaseState.PAYMENT_AUTHORIZING, PaymentOutcome.UNKNOWN) shouldBeEqualTo
            PurchaseTransition(
                next = PurchaseState.RECONCILIATION_REQUIRED,
                heldDelta = 0,
                soldDelta = 0,
                releaseGuards = false,
            )
    }

    @Test
    fun `normal approval moves held inventory to sold and releases foreground guards`() {
        transition(PurchaseState.PAYMENT_AUTHORIZING, PaymentOutcome.APPROVED) shouldBeEqualTo
            PurchaseTransition(
                next = PurchaseState.APPROVED,
                heldDelta = -1,
                soldDelta = 1,
                releaseGuards = true,
                ticketDisposition = TicketDisposition.PENDING,
            )
    }

    @Test
    fun `late approval suppresses issue and enters refund remediation`() {
        transition(PurchaseState.CANCELLATION_REQUESTED, PaymentOutcome.APPROVED) shouldBeEqualTo
            PurchaseTransition(
                next = PurchaseState.REFUND_PENDING,
                heldDelta = -1,
                soldDelta = 1,
                releaseGuards = false,
                ticketDisposition = TicketDisposition.NEVER_ISSUED,
            )
    }

    @Test
    fun `refund does not restock until ticket disposition is safe`() {
        refundTransition(PurchaseState.REFUND_PENDING, TicketDisposition.ISSUED) shouldBeEqualTo
            PurchaseTransition(
                next = PurchaseState.REFUND_PENDING,
                heldDelta = 0,
                soldDelta = 0,
                releaseGuards = false,
                ticketDisposition = TicketDisposition.ISSUED,
            )
        refundTransition(PurchaseState.REFUND_PENDING, TicketDisposition.REVOKED) shouldBeEqualTo
            PurchaseTransition(
                next = PurchaseState.REFUNDED,
                heldDelta = 0,
                soldDelta = -1,
                releaseGuards = true,
                ticketDisposition = TicketDisposition.REVOKED,
            )
    }

    @Test
    fun `suspended sale resumes only inside its window with healthy invariants`() {
        val context =
            SaleTransitionContext(
                now = Instant.parse("2026-07-21T10:00:00Z"),
                opensAt = Instant.parse("2026-07-21T09:00:00Z"),
                closesAt = Instant.parse("2026-07-21T11:00:00Z"),
                invariantsHealthy = true,
            )

        saleTransition(SaleState.SUSPENDED, SaleCommand.RESUME, context) shouldBeEqualTo SaleState.OPEN
        assertFailsWith<InvalidSaleTransition> {
            saleTransition(
                SaleState.SUSPENDED,
                SaleCommand.RESUME,
                context.copy(invariantsHealthy = false),
            )
        }
    }

    @Test
    fun `stale purchase outcome is rejected instead of guessed`() {
        val failure =
            assertFailsWith<InvalidPurchaseTransition> {
                transition(PurchaseState.APPROVED, PaymentOutcome.DECLINED)
            }

        failure.state shouldBeEqualTo PurchaseState.APPROVED
        failure.outcome shouldBeEqualTo PaymentOutcome.DECLINED
    }

    @Test
    fun `ticket retry exhaustion moves to quarantine`() {
        ticketTransition(TicketState.REVOKE_PENDING, TicketEffectOutcome.RETRY_BUDGET_EXHAUSTED) shouldBeEqualTo
            TicketState.QUARANTINED
    }
}

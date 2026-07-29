package io.bluetape4k.workshop.commerce.usagebilling.billing.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentCommand
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentOutcome
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class BillingAdjustmentServiceTest {
    @Test
    fun `posts one immutable compensating fact through the Billing journal`() {
        val journal = RecordingAdjustmentJournal()
        val service = BillingAdjustmentService(journal)
        val command = BillingAdjustmentCommand(
            adjustmentEventId = UUID.randomUUID(),
            tenantId = "tenant-a",
            correctionOf = UUID.randomUUID(),
            amount = BigDecimal("-0.20"),
            currency = "USD",
        )

        service.post(command) shouldBeEqualTo BillingAdjustmentOutcome.APPLIED
        journal.commands shouldBeEqualTo listOf(command)
    }

    private class RecordingAdjustmentJournal : BillingAdjustmentJournal {
        val commands = mutableListOf<BillingAdjustmentCommand>()

        override fun post(command: BillingAdjustmentCommand): BillingAdjustmentOutcome {
            commands += command
            return BillingAdjustmentOutcome.APPLIED
        }
    }
}

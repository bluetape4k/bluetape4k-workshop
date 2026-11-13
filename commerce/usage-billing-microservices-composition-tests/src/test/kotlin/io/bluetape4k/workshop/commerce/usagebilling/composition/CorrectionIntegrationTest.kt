package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentOutcome
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.*
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
class CorrectionIntegrationTest {
    @Test
    fun `Billing adjustment appends an Invoice correction without rewriting the original line`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.activatePrice(TENANT, METER_CODE, BigDecimal("0.10"))
            fixture.publishMeterEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.billingHasPriceEvidence(TENANT, METER_CODE).shouldBeTrue()
            }
            fixture.acceptUsage(TENANT, METER_CODE)
            fixture.publishUsageEvents()
            await atMost TIMEOUT untilAsserted { fixture.chargeCount() shouldBeEqualTo 1L }
            val originalChargeEventId = fixture.latestBillingEventId()
            fixture.publishBillingEvents()
            await atMost TIMEOUT untilAsserted { fixture.invoiceLineCount() shouldBeEqualTo 1L }

            val adjustmentEventId = UUID.randomUUID()
            fixture.postBillingAdjustment(
                TENANT,
                originalChargeEventId,
                BigDecimal("-0.10"),
                adjustmentEventId,
            ) shouldBeEqualTo BillingAdjustmentOutcome.APPLIED
            fixture.postBillingAdjustment(
                TENANT,
                originalChargeEventId,
                BigDecimal("-0.10"),
                adjustmentEventId,
            ) shouldBeEqualTo BillingAdjustmentOutcome.DUPLICATE
            fixture.publishBillingEvents()

            await atMost TIMEOUT untilAsserted { fixture.invoiceLineCount() shouldBeEqualTo 2L }
            fixture.invoiceLines().also { lines ->
                lines.first().sourceEventId shouldBeEqualTo originalChargeEventId
                lines.first().correctionOf.shouldBeNull()
                lines.last().sourceEventId shouldBeEqualTo adjustmentEventId
                lines.last().correctionOf shouldBeEqualTo originalChargeEventId
                lines.last().amount shouldBeEqualTo (-0.10).toBigDecimal()
            }
            fixture.publishInvoiceEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.queryAppliedEventCount() shouldBeGreaterOrEqualTo 6
            }
        }
    }

    private companion object {
        val TIMEOUT: kotlin.time.Duration = 20.seconds
        const val TENANT = "tenant-correction"
        const val METER_CODE = "api_calls"
    }
}

package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.Awaitility.await
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
class ParityIntegrationTest {
    @Test
    fun `distributed charge and invoice totals retain the established billing contract`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.activatePrice(TENANT, METER_CODE, UNIT_PRICE)
            fixture.publishMeterEvents()
            await().atMost(TIMEOUT).untilAsserted {
                fixture.billingHasPriceEvidence(TENANT, METER_CODE).shouldBeTrue()
            }

            fixture.acceptUsage(TENANT, METER_CODE)
            fixture.publishUsageEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.chargeAmounts().single().compareTo(EXPECTED_TOTAL) shouldBeEqualTo 0
            }

            fixture.publishBillingEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.invoiceLines().single().amount.compareTo(EXPECTED_TOTAL) shouldBeEqualTo 0
            }
            fixture.publishInvoiceEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.queryAppliedEventCount() shouldBeEqualTo 4
            }
        }
    }

    private companion object {
        val TIMEOUT: kotlin.time.Duration = 20.seconds
        val UNIT_PRICE: BigDecimal = BigDecimal("0.10")
        val EXPECTED_TOTAL: BigDecimal = BigDecimal("0.200000")
        const val TENANT = "tenant-parity"
        const val METER_CODE = "api_calls"
    }
}

package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

@Tag("integration")
class OrderingIntegrationTest {
    @Test
    fun `future aggregate version is deferred until the missing version arrives`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.activatePrice(TENANT, METER_CODE, BigDecimal("0.10"))
            fixture.publishMeterEvents()
            await().atMost(TIMEOUT).untilAsserted {
                fixture.billingHasPriceEvidence(TENANT, METER_CODE) shouldBeEqualTo true
            }

            fixture.sendUsageEvent(TENANT, METER_CODE, AGGREGATE_ID, 2, VERSION_TWO_EVENT_ID)
            await().during(Duration.ofSeconds(1)).atMost(TIMEOUT).untilAsserted {
                fixture.chargeCount() shouldBeEqualTo 0L
            }

            fixture.sendUsageEvent(TENANT, METER_CODE, AGGREGATE_ID, 1)
            await().atMost(TIMEOUT).untilAsserted { fixture.chargeCount() shouldBeEqualTo 1L }
            fixture.sendUsageEvent(TENANT, METER_CODE, AGGREGATE_ID, 2, VERSION_TWO_EVENT_ID)
            await().atMost(TIMEOUT).untilAsserted { fixture.chargeCount() shouldBeEqualTo 2L }
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(20)
        val VERSION_TWO_EVENT_ID: UUID = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a22")
        const val TENANT = "tenant-ordering"
        const val METER_CODE = "api_calls"
        const val AGGREGATE_ID = "ordered-usage"
    }
}

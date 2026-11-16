package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.during
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.*
import kotlin.time.Duration.Companion.seconds

class OrderingIntegrationTest {

    private companion object: KLogging() {
        val TIMEOUT: kotlin.time.Duration = 20.seconds
        val VERSION_TWO_EVENT_ID: UUID = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a22")

        const val TENANT = "tenant-ordering"
        const val METER_CODE = "api_calls"
        const val AGGREGATE_ID = "ordered-usage"
    }

    @Test
    fun `future aggregate version is deferred until the missing version arrives`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.activatePrice(
                tenantId = TENANT,
                meterCode = METER_CODE,
                amount = BigDecimal("0.10")
            )
            fixture.publishMeterEvents()

            await atMost TIMEOUT untilAsserted {
                fixture.billingHasPriceEvidence(TENANT, METER_CODE).shouldBeTrue()
            }

            fixture.sendUsageEvent(
                tenantId = TENANT,
                meterCode = METER_CODE,
                aggregateId = AGGREGATE_ID,
                aggregateVersion = 2,
                eventId = VERSION_TWO_EVENT_ID
            )

            await during 1.seconds atMost TIMEOUT untilAsserted {
                fixture.chargeCount() shouldBeEqualTo 0L
            }

            fixture.sendUsageEvent(
                tenantId = TENANT,
                meterCode = METER_CODE,
                aggregateId = AGGREGATE_ID,
                aggregateVersion = 1
            )
            await atMost TIMEOUT untilAsserted {
                fixture.chargeCount() shouldBeEqualTo 1L
            }

            fixture.sendUsageEvent(
                tenantId = TENANT,
                meterCode = METER_CODE,
                aggregateId = AGGREGATE_ID,
                aggregateVersion = 2,
                eventId = VERSION_TWO_EVENT_ID
            )
            await atMost TIMEOUT untilAsserted {
                fixture.chargeCount() shouldBeEqualTo 2L
            }
        }
    }
}

package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
class OutageIntegrationTest {
    @Test
    fun `Kafka outage leaves committed Meter outbox backlog and recovery delivers it`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.activatePrice(TENANT, METER_CODE, BigDecimal("0.10"))
            fixture.blockTopic(METER_TOPIC)
            try {
                fixture.publishMeterEvents().retryWait shouldBeEqualTo 1
            } finally {
                fixture.unblockTopic(METER_TOPIC)
            }
            fixture.outboxBacklog("meter") shouldBeEqualTo 1L

            await withPollInterval 1.seconds atMost TIMEOUT untilAsserted {
                fixture.publishMeterEvents().published shouldBeEqualTo 1
            }
            await atMost TIMEOUT untilAsserted {
                fixture.priceEvidence(TENANT, METER_CODE).shouldNotBeNull()
            }
        }
    }

    private companion object {
        val TIMEOUT: kotlin.time.Duration = 30.seconds
        const val TENANT = "tenant-outage"
        const val METER_CODE = "api_calls"
        const val METER_TOPIC = "meter.events.v1"
    }
}

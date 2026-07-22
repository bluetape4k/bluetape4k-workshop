package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

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

            await().pollInterval(Duration.ofSeconds(1)).atMost(TIMEOUT).untilAsserted {
                fixture.publishMeterEvents().published shouldBeEqualTo 1
            }
            await().atMost(TIMEOUT).untilAsserted {
                checkNotNull(fixture.priceEvidence(TENANT, METER_CODE))
            }
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        const val TENANT = "tenant-outage"
        const val METER_CODE = "api_calls"
        const val METER_TOPIC = "meter.events.v1"
    }
}

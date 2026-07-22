package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

@Tag("integration")
class UsageBillingMicroserviceCompositionIntegrationTest {
    @Test
    fun `committed price survives delayed publication and is delivered after recovery`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.blockTopic(METER_TOPIC)

            fixture.activatePrice(TENANT, METER_CODE, BigDecimal("0.10"))

            fixture.outboxBacklog("meter") shouldBeEqualTo 1L
            fixture.unblockTopic(METER_TOPIC)
            fixture.publishMeterEvents()
            await().atMost(Duration.ofSeconds(15)).untilAsserted {
                checkNotNull(fixture.priceEvidence(TENANT, METER_CODE))
            }
        }
    }

    private companion object {
        const val METER_TOPIC = "meter.events.v1"
        const val TENANT = "tenant-composition"
        const val METER_CODE = "api_calls"
    }
}

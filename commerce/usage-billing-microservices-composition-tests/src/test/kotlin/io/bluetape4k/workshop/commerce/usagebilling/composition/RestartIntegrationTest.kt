package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

@Tag("integration")
class RestartIntegrationTest {
    @Test
    fun `Usage context restart preserves durable price evidence and continues publication`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.activatePrice(TENANT, METER_CODE, BigDecimal("0.10"))
            fixture.publishMeterEvents()
            await().atMost(TIMEOUT).untilAsserted {
                checkNotNull(fixture.priceEvidence(TENANT, METER_CODE))
                fixture.billingHasPriceEvidence(TENANT, METER_CODE) shouldBeEqualTo true
            }

            fixture.restartUsageContext()

            checkNotNull(fixture.priceEvidence(TENANT, METER_CODE))
            fixture.acceptUsage(TENANT, METER_CODE)
            fixture.publishUsageEvents()
            await().atMost(TIMEOUT).untilAsserted { fixture.chargeCount() shouldBeEqualTo 1L }
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(20)
        const val TENANT = "tenant-restart"
        const val METER_CODE = "api_calls"
    }
}

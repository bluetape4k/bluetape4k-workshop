package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryConformanceFixture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.during
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

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
            await atMost TIMEOUT untilAsserted {
                fixture.priceEvidence(TENANT, METER_CODE).shouldNotBeNull()
            }
        }
    }

    @Test
    fun `duplicate Usage delivery leaves one Billing financial effect`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.activatePrice(DUPLICATE_TENANT, METER_CODE, BigDecimal("0.10"))
            fixture.publishMeterEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.priceEvidence(DUPLICATE_TENANT, METER_CODE).shouldNotBeNull()
                fixture.billingHasPriceEvidence(DUPLICATE_TENANT, METER_CODE).shouldBeTrue()
            }
            fixture.acceptUsage(DUPLICATE_TENANT, METER_CODE, DUPLICATE_SOURCE_EVENT_ID)
            fixture.publishUsageEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.chargeCount() shouldBeEqualTo 1L
            }

            fixture.redeliverLatestUsageEvent()

            await atMost TIMEOUT during 2.seconds untilAsserted {
                fixture.chargeCount() shouldBeEqualTo 1L
            }
            KafkaRecoveryConformanceFixture(TIMEOUT.toJavaDuration()).assertDedupBoundary(
                logicalEventIds = setOf(DUPLICATE_SOURCE_EVENT_ID),
                deliveredEventIds = listOf(DUPLICATE_SOURCE_EVENT_ID, DUPLICATE_SOURCE_EVENT_ID),
                appliedEventIds = setOf(DUPLICATE_SOURCE_EVENT_ID),
            )
        }
    }

    @Test
    fun `price and usage produce one charge one invoice line and Query projections across Kafka`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.activatePrice(PARITY_TENANT, METER_CODE, "0.10".toBigDecimal())
            fixture.publishMeterEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.priceEvidence(PARITY_TENANT, METER_CODE).shouldNotBeNull()
                fixture.billingHasPriceEvidence(PARITY_TENANT, METER_CODE).shouldBeTrue()
            }

            fixture.acceptUsage(PARITY_TENANT, METER_CODE)
            fixture.publishUsageEvents()
            await atMost TIMEOUT untilAsserted { fixture.chargeCount() shouldBeEqualTo 1L }

            fixture.publishBillingEvents()
            await atMost TIMEOUT untilAsserted { fixture.invoiceLineCount() shouldBeEqualTo 1L }

            fixture.publishInvoiceEvents()

            await atMost TIMEOUT untilAsserted {
                fixture.queryAppliedEventCount() shouldBeGreaterOrEqualTo 4
            }
        }
    }

    private companion object {
        val TIMEOUT: kotlin.time.Duration = 20.seconds
        const val METER_TOPIC = "meter.events.v1"
        const val TENANT = "tenant-composition"
        const val DUPLICATE_TENANT = "tenant-duplicate"
        const val PARITY_TENANT = "tenant-parity"
        const val METER_CODE = "api_calls"
        const val DUPLICATE_SOURCE_EVENT_ID = "source-duplicate-1"
    }
}

package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryConformanceFixture
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryObservation
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryPath
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.MeterOutboxState
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxStatus
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

@Tag("integration")
class BrokerPathRecoveryIntegrationTest {
    @Test
    fun `real Kafka broker path outage preserves the Meter outbox row and recovers delivery`() {
        UsageBillingMicroserviceFixture(brokerPathProxy = true).use { fixture ->
            fixture.start()
            val activation = fixture.activatePrice(TENANT, METER_CODE, BigDecimal("0.10"))
            val recoveryStartedAt = System.nanoTime()
            fixture.cutBrokerPath()
            try {
                fixture.publishMeterEvents().retryWait shouldBeEqualTo 1
            } finally {
                fixture.restoreBrokerPath()
            }
            fixture.outboxBacklog("meter") shouldBeEqualTo 1L
            fixture.meterOutboxState(activation.eventId) shouldBeEqualTo
                MeterOutboxState(MeterOutboxStatus.RETRY_WAIT, attempt = 1)

            await().pollInterval(Duration.ofSeconds(1)).atMost(TIMEOUT).untilAsserted {
                fixture.publishMeterEvents().published shouldBeEqualTo 1
            }
            fixture.meterOutboxState(activation.eventId) shouldBeEqualTo
                MeterOutboxState(MeterOutboxStatus.PUBLISHED, attempt = 1)
            await().atMost(TIMEOUT).untilAsserted {
                (fixture.priceEvidence(TENANT, METER_CODE) != null) shouldBeEqualTo true
            }
            KafkaRecoveryConformanceFixture(TIMEOUT).assertTransportRecovery(
                KafkaRecoveryObservation(
                    path = KafkaRecoveryPath.TRANSPORT_INTERRUPTION,
                    logicalEventIds = setOf(activation.eventId.toString()),
                    deliveredEventIds = listOf(activation.eventId.toString()),
                    appliedEventIds = setOf(activation.eventId.toString()),
                    conflictCount = 0,
                    recoveryElapsed = java.time.Duration.ofNanos(System.nanoTime() - recoveryStartedAt),
                    recovered = true,
                    transportInterrupted = true,
                    leaderChanged = false,
                    coordinatorChanged = false,
                    replacementReady = false,
                    isrRestored = false,
                ),
            )
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        const val TENANT = "tenant-broker-path"
        const val METER_CODE = "api_calls"
    }
}

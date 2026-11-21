package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.MeterOutboxState
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxStatus
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryConformanceFixture
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryObservation
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryPath
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
/**
 * BrokerPathRecoveryIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class BrokerPathRecoveryIntegrationTest {

    private companion object: KLogging() {
/**
 * TIMEOUT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val TIMEOUT: Duration = 30.seconds()
/**
 * TENANT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT = "tenant-broker-path"
/**
 * METER_CODE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val METER_CODE = "api_calls"
    }

    @Test
/**
 * 이 시나리오는 "real Kafka broker path outage preserves the Meter outbox row and recovers delivery" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

            await withPollInterval 1.seconds atMost TIMEOUT untilAsserted {
                fixture.publishMeterEvents().published shouldBeEqualTo 1
            }

            fixture.meterOutboxState(activation.eventId) shouldBeEqualTo
                    MeterOutboxState(MeterOutboxStatus.PUBLISHED, attempt = 1)

            await atMost TIMEOUT untilAsserted {
                (fixture.priceEvidence(TENANT, METER_CODE) != null).shouldBeTrue()
            }

            KafkaRecoveryConformanceFixture(TIMEOUT).assertTransportRecovery(
                KafkaRecoveryObservation(
                    path = KafkaRecoveryPath.TRANSPORT_INTERRUPTION,
                    logicalEventIds = setOf(activation.eventId.toString()),
                    deliveredEventIds = listOf(activation.eventId.toString()),
                    appliedEventIds = setOf(activation.eventId.toString()),
                    conflictCount = 0,
                    recoveryElapsed = (System.nanoTime() - recoveryStartedAt).nanos(),
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
}

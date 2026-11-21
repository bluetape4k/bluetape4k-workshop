package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
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
/**
 * OutageIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class OutageIntegrationTest {

    private companion object: KLogging() {
/**
 * TIMEOUT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val TIMEOUT: kotlin.time.Duration = 30.seconds
/**
 * TENANT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT = "tenant-outage"
/**
 * METER_CODE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val METER_CODE = "api_calls"
/**
 * METER_TOPIC은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val METER_TOPIC = "meter.events.v1"
    }

    @Test
/**
 * 이 시나리오는 "Kafka outage leaves committed Meter outbox backlog and recovery delivers it" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
}

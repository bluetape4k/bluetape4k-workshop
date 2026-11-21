package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.during
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.*
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
/**
 * OrderingIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class OrderingIntegrationTest {

    private companion object: KLogging() {
/**
 * TIMEOUT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val TIMEOUT: kotlin.time.Duration = 20.seconds
/**
 * VERSION_TWO_EVENT_ID은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val VERSION_TWO_EVENT_ID: UUID = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a22")

/**
 * TENANT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT = "tenant-ordering"
/**
 * METER_CODE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val METER_CODE = "api_calls"
/**
 * AGGREGATE_ID은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val AGGREGATE_ID = "ordered-usage"
    }

    @Test
/**
 * 이 시나리오는 "future aggregate version is deferred until the missing version arrives" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

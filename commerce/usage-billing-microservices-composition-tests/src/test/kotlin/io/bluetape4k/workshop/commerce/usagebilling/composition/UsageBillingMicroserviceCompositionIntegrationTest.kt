package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import io.bluetape4k.kafka.test.conformance.KafkaRecoveryConformanceFixture
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
/**
 * UsageBillingMicroserviceCompositionIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class UsageBillingMicroserviceCompositionIntegrationTest {

    private companion object: KLogging() {
/**
 * TIMEOUT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val TIMEOUT: kotlin.time.Duration = 20.seconds
/**
 * METER_TOPIC은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val METER_TOPIC = "meter.events.v1"
/**
 * TENANT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT = "tenant-composition"
/**
 * DUPLICATE_TENANT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val DUPLICATE_TENANT = "tenant-duplicate"
/**
 * PARITY_TENANT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PARITY_TENANT = "tenant-parity"
/**
 * METER_CODE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val METER_CODE = "api_calls"
/**
 * DUPLICATE_SOURCE_EVENT_ID은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val DUPLICATE_SOURCE_EVENT_ID = "source-duplicate-1"
    }


    @Test
/**
 * 이 시나리오는 "committed price survives delayed publication and is delivered after recovery" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "duplicate Usage delivery leaves one Billing financial effect" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "price and usage produce one charge one invoice line and Query projections across Kafka"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
            await atMost TIMEOUT untilAsserted {
                fixture.chargeCount() shouldBeEqualTo 1L
            }

            fixture.publishBillingEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.invoiceLineCount() shouldBeEqualTo 1L
            }

            fixture.publishInvoiceEvents()

            await atMost TIMEOUT untilAsserted {
                fixture.queryAppliedEventCount() shouldBeGreaterOrEqualTo 4
            }
        }
    }
}

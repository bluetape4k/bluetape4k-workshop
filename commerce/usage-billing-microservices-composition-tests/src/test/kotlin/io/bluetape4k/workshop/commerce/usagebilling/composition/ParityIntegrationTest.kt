package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.Awaitility.await
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
/**
 * ParityIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ParityIntegrationTest {

    private companion object: KLogging() {
/**
 * TIMEOUT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val TIMEOUT: kotlin.time.Duration = 20.seconds
/**
 * UNIT_PRICE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val UNIT_PRICE: BigDecimal = BigDecimal("0.10")
/**
 * EXPECTED_TOTAL은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val EXPECTED_TOTAL: BigDecimal = BigDecimal("0.200000")
/**
 * TENANT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT = "tenant-parity"
/**
 * METER_CODE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val METER_CODE = "api_calls"
    }

    @Test
/**
 * 이 시나리오는 "distributed charge and invoice totals retain the established billing contract" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `distributed charge and invoice totals retain the established billing contract`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.activatePrice(TENANT, METER_CODE, UNIT_PRICE)
            fixture.publishMeterEvents()
            await().atMost(TIMEOUT).untilAsserted {
                fixture.billingHasPriceEvidence(TENANT, METER_CODE).shouldBeTrue()
            }

            fixture.acceptUsage(TENANT, METER_CODE)
            fixture.publishUsageEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.chargeAmounts().single().compareTo(EXPECTED_TOTAL) shouldBeEqualTo 0
            }

            fixture.publishBillingEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.invoiceLines().single().amount.compareTo(EXPECTED_TOTAL) shouldBeEqualTo 0
            }
            fixture.publishInvoiceEvents()
            await atMost TIMEOUT untilAsserted {
                fixture.queryAppliedEventCount() shouldBeEqualTo 4
            }
        }
    }
}

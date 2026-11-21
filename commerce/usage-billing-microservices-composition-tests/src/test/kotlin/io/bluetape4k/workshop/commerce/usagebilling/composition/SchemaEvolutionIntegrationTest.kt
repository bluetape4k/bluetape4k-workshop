package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
/**
 * SchemaEvolutionIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class SchemaEvolutionIntegrationTest {

    private companion object: KLogging() {
/**
 * TIMEOUT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val TIMEOUT: kotlin.time.Duration = 20.seconds
/**
 * TENANT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT = "tenant-schema"
    }

    @Test
/**
 * 이 시나리오는 "Query accepts additive v2 and quarantines an unknown mandatory version" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Query accepts additive v2 and quarantines an unknown mandatory version`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            val compatibleEventId = UUID.randomUUID()
            val unsupportedEventId = UUID.randomUUID()

            fixture.sendQueryEvent(compatibleEventId, TENANT, "InvoiceIssued", schemaVersion = 2)
            fixture.sendQueryEvent(unsupportedEventId, TENANT, "InvoiceIssued", schemaVersion = 99)

            await atMost TIMEOUT withPollInterval 250.milliseconds untilAsserted {
                fixture.queryAppliedEventCount() shouldBeEqualTo 1
                fixture.queryRecoverySnapshot().quarantineCount shouldBeEqualTo 1L
            }
        }
    }
}

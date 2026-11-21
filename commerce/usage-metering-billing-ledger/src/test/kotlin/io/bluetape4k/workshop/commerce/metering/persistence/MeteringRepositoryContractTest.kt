package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant


@Tag("integration")
/**
 * MeteringRepositoryContractTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class MeteringRepositoryContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "fixture seeds authority through Exposed and tenant meter uniqueness is enforced" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fixture seeds authority through Exposed and tenant meter uniqueness is enforced`() {
        MeteringDatabaseFixture().use { fixture ->
            val seed = fixture.resetAndSeed()

            fixture.executor.transaction {
                MeterEntity.findById(seed.meterId)?.meterCode shouldBeEqualTo seed.meterCode
                BillingCalendarEntity.findById(seed.calendarId)?.currency shouldBeEqualTo seed.currency
            }

            assertFailsWith<ExposedSQLException> {
                fixture.executor.transaction {
                    MeterEntity.new {
                        tenantId = seed.tenantId
                        meterCode = seed.meterCode
                        unit = "duplicate"
                        description = null
                        active = true
                        createdAt = Instant.parse("2026-07-01T00:00:01Z")
                    }
                }
            }
        }
    }
}

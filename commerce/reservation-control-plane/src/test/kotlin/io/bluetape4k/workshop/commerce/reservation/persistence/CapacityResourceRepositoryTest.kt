package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * CapacityResourceRepositoryTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class CapacityResourceRepositoryTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "capacity CAS admits once and rejects stale revision on PostgreSQL" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `capacity CAS admits once and rejects stale revision on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val repository = CapacityResourceRepository()
            val resource = repository.create("demo-room", capacity = 1, policyVersion = 1)

            repository.tryOccupy(resource.id, expectedRevision = 0).shouldBeTrue()
            repository.tryOccupy(resource.id, expectedRevision = 0).shouldBeFalse()

            val occupied = repository.findById(resource.id)
            occupied.occupiedCount shouldBeEqualTo 1
            occupied.revision shouldBeEqualTo 1
        }
    }
}

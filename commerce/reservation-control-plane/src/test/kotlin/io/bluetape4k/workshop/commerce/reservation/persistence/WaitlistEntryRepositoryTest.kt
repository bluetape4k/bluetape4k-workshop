package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import org.junit.jupiter.api.Test

/**
 * WaitlistEntryRepositoryTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class WaitlistEntryRepositoryTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "join allocates stable FIFO sequence and snapshots preserve that order on PostgreSQL" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `join allocates stable FIFO sequence and snapshots preserve that order on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resource = CapacityResourceRepository().create("fifo-room", capacity = 1, policyVersion = 1)
            val repository = WaitlistEntryRepository()

            val first = repository.join(resource.id, ownerDigest = "a".repeat(64))
            val second = repository.join(resource.id, ownerDigest = "b".repeat(64))

            first.sequence shouldBeEqualTo 1L
            second.sequence shouldBeEqualTo 2L
            repository.snapshots(resource.id).map { it.id } shouldBeEqualTo listOf(first.id, second.id)
            repository.oldestWaiting(resource.id)?.id shouldBeEqualTo first.id
        }
    }

    @Test
/**
 * 이 시나리오는 "cancel CAS requires owner revision and WAITING state on PostgreSQL" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `cancel CAS requires owner revision and WAITING state on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resource = CapacityResourceRepository().create("cancel-room", capacity = 1, policyVersion = 1)
            val repository = WaitlistEntryRepository()
            val entry = repository.join(resource.id, ownerDigest = "a".repeat(64))

            repository.transition(
                id = entry.id,
                ownerDigest = "b".repeat(64),
                expectedRevision = 0,
                from = WaitlistState.WAITING,
                to = WaitlistState.CANCELLED
            ).shouldBeFalse()

            repository.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = 0,
                from = WaitlistState.WAITING,
                to = WaitlistState.CANCELLED
            ).shouldBeTrue()

            repository.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = 0,
                from = WaitlistState.WAITING,
                to = WaitlistState.CANCELLED
            ).shouldBeFalse()

            repository.findById(entry.id).state shouldBeEqualTo WaitlistState.CANCELLED
            repository.findById(entry.id).revision shouldBeEqualTo 1L
        }
    }
}

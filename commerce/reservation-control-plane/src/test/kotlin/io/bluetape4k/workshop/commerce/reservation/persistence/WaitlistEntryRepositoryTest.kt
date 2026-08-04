package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import org.junit.jupiter.api.Test

class WaitlistEntryRepositoryTest {
    @Test
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

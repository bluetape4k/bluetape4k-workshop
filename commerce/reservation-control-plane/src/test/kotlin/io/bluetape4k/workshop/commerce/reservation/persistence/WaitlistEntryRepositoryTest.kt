package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WaitlistEntryRepositoryTest {
    @Test
    fun `join allocates stable FIFO sequence and snapshots preserve that order on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resource = CapacityResourceRepository().create("fifo-room", capacity = 1, policyVersion = 1)
            val repository = WaitlistEntryRepository()

            val first = repository.join(resource.id, ownerDigest = "a".repeat(64))
            val second = repository.join(resource.id, ownerDigest = "b".repeat(64))

            assertEquals(1L, first.sequence)
            assertEquals(2L, second.sequence)
            assertEquals(listOf(first.id, second.id), repository.snapshots(resource.id).map { it.id })
            assertEquals(first.id, repository.oldestWaiting(resource.id)?.id)
        }
    }

    @Test
    fun `cancel CAS requires owner revision and WAITING state on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resource = CapacityResourceRepository().create("cancel-room", capacity = 1, policyVersion = 1)
            val repository = WaitlistEntryRepository()
            val entry = repository.join(resource.id, ownerDigest = "a".repeat(64))

            assertFalse(repository.transition(
                id = entry.id,
                ownerDigest = "b".repeat(64),
                expectedRevision = 0,
                from = WaitlistState.WAITING,
                to = WaitlistState.CANCELLED,
            ))
            assertTrue(repository.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = 0,
                from = WaitlistState.WAITING,
                to = WaitlistState.CANCELLED,
            ))
            assertFalse(repository.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = 0,
                from = WaitlistState.WAITING,
                to = WaitlistState.CANCELLED,
            ))

            assertEquals(WaitlistState.CANCELLED, repository.findById(entry.id).state)
            assertEquals(1L, repository.findById(entry.id).revision)
        }
    }
}

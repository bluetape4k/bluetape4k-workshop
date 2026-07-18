package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapacityResourceRepositoryTest {
    @Test
    fun `capacity CAS admits once and rejects stale revision on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val repository = CapacityResourceRepository()
            val resource = repository.create("demo-room", capacity = 1, policyVersion = 1)

            assertTrue(repository.tryOccupy(resource.id, expectedRevision = 0))
            assertFalse(repository.tryOccupy(resource.id, expectedRevision = 0))

            val occupied = repository.findById(resource.id)
            assertEquals(1, occupied.occupiedCount)
            assertEquals(1, occupied.revision)
        }
    }
}

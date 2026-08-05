package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class CapacityResourceRepositoryTest {
    @Test
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

package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepositoryArchitectureTest {
    @Test
    fun `every concrete event store repository implements ExposedJdbcRepository`() {
        assertTrue(ExposedJdbcRepository::class.java.isAssignableFrom(EventStoreRepository::class.java))
    }
}

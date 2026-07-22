package io.bluetape4k.workshop.commerce.usagebilling.meter.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

class MeterRepositoryArchitectureTest {
    @Test
    fun `meter outbox repository implements ExposedJdbcRepository`() {
        ExposedJdbcRepository::class.java.isAssignableFrom(
            Class.forName("io.bluetape4k.workshop.commerce.usagebilling.meter.persistence.MeterOutboxRepository"),
        ) shouldBeEqualTo true
    }
}

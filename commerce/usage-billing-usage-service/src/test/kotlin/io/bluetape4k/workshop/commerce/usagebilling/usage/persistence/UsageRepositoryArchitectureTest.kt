package io.bluetape4k.workshop.commerce.usagebilling.usage.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

class UsageRepositoryArchitectureTest {
    @Test
    fun `usage outbox repository implements ExposedJdbcRepository`() {
        ExposedJdbcRepository::class.java.isAssignableFrom(
            Class.forName("io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsageOutboxRepository"),
        ) shouldBeEqualTo true
    }
}

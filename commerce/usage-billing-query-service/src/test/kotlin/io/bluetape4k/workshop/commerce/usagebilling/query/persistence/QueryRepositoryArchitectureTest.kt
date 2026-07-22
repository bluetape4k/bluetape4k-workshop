package io.bluetape4k.workshop.commerce.usagebilling.query.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

class QueryRepositoryArchitectureTest {
    @Test
    fun `query inbox repository implements ExposedJdbcRepository`() {
        ExposedJdbcRepository::class.java.isAssignableFrom(
            Class.forName("io.bluetape4k.workshop.commerce.usagebilling.query.persistence.QueryInboxRepository"),
        ) shouldBeEqualTo true
    }
}

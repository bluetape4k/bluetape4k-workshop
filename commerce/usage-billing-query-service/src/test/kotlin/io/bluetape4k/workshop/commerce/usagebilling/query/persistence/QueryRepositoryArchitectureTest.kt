package io.bluetape4k.workshop.commerce.usagebilling.query.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

class QueryRepositoryArchitectureTest {
    @Test
    fun `every query persistence repository implements ExposedJdbcRepository`() {
        listOf(
            "QueryInboxRepository",
            "QueryReadModelRepository",
            "QueryCheckpointRepository",
            "QueryQuarantineRepository",
            "QueryRedriveAuditRepository",
        ).all { repository ->
            ExposedJdbcRepository::class.java.isAssignableFrom(
                Class.forName("io.bluetape4k.workshop.commerce.usagebilling.query.persistence.$repository"),
            )
        } shouldBeEqualTo true
    }
}

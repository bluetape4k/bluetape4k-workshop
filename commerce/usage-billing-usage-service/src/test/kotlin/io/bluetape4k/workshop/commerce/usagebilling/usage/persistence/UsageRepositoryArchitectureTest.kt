package io.bluetape4k.workshop.commerce.usagebilling.usage.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

class UsageRepositoryArchitectureTest {
    @Test
    fun `usage durable repositories implement ExposedJdbcRepository`() {
        listOf(
            "UsageOutboxRepository",
            "UsagePriceEvidenceRepository",
            "UsagePriceEvidenceInboxRepository",
            "UsageRecordRepository",
        ).all { repository ->
            ExposedJdbcRepository::class.java.isAssignableFrom(
                Class.forName("io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.$repository"),
            )
        } shouldBeEqualTo true
    }
}

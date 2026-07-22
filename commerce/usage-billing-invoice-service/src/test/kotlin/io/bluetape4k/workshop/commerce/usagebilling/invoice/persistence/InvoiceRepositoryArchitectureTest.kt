package io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

class InvoiceRepositoryArchitectureTest {
    @Test
    fun `invoice durable repositories implement ExposedJdbcRepository`() {
        listOf("InvoiceOutboxRepository", "InvoiceInboxRepository", "InvoiceLineRepository").all { repository ->
            ExposedJdbcRepository::class.java.isAssignableFrom(
                Class.forName("io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence.$repository"),
            )
        } shouldBeEqualTo true
    }
}

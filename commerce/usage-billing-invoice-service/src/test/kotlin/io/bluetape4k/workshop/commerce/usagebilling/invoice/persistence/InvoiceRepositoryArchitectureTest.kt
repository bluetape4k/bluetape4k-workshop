package io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

class InvoiceRepositoryArchitectureTest {

    companion object: KLogging()

    @Test
    fun `invoice durable repositories implement ExposedJdbcRepository`() {
        listOf("InvoiceOutboxRepository", "InvoiceInboxRepository", "InvoiceLineRepository").all { repository ->
            ExposedJdbcRepository::class.java.isAssignableFrom(
                Class.forName("io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence.$repository"),
            )
        }.shouldBeTrue()
    }
}

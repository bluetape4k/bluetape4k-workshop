package io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

class InvoiceRepositoryArchitectureTest {
    @Test
    fun `invoice outbox repository implements ExposedJdbcRepository`() {
        ExposedJdbcRepository::class.java.isAssignableFrom(
            Class.forName("io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence.InvoiceOutboxRepository"),
        ) shouldBeEqualTo true
    }
}

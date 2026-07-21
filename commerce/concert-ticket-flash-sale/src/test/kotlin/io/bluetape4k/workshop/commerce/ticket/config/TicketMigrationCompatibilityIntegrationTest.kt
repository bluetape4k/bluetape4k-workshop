package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabaseFixture
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("migration-compatibility")
internal class TicketMigrationCompatibilityIntegrationTest {
    @Test
    fun `current schema retains every aggregate authority and natural uniqueness guard`() {
        TicketDatabaseFixture().use { fixture ->
            fixture.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema = current_schema() AND table_name LIKE 'ticket_%'",
                    ).use { result ->
                        result.next()
                        result.getInt(1) shouldBeEqualTo 18
                    }
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM pg_constraint " +
                            "WHERE conrelid IN ('ticket_payment_operations'::regclass, 'ticket_effect_receipts'::regclass) " +
                            "AND contype = 'u'",
                    ).use { result ->
                        result.next()
                        result.getInt(1) shouldBeEqualTo 2
                    }
                }
            }
        }
    }
}

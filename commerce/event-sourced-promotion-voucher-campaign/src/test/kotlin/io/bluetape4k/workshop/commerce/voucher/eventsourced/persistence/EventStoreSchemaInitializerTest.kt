package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

internal class EventStoreSchemaInitializerTest {

    companion object: KLogging()

    @Test
    fun `initializer exposes only the reviewed append-only ddl allowlist`() {
        EventStoreSchemaInitializer.sqlStatements()
            .all { statement ->
                statement.startsWith("CREATE") ||
                    statement.startsWith("REVOKE") ||
                    statement.startsWith("GRANT")
            }.shouldBeTrue()
    }
}

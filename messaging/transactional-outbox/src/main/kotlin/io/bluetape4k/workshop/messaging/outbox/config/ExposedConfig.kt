package io.bluetape4k.workshop.messaging.outbox.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.messaging.outbox.domain.OrderTable
import io.bluetape4k.workshop.messaging.outbox.domain.OutboxEventTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Creates the [OrderTable] and [OutboxEventTable] database schemas on startup.
 *
 * Uses `SchemaUtils.createMissingTablesAndColumns` so it is safe to run against an
 * existing database — columns and tables that already exist are left untouched.
 */
@Component
class ExposedConfig : ApplicationRunner {

    companion object : KLogging()

    @Transactional
    override fun run(args: ApplicationArguments) {
        log.info { "Creating outbox schema tables if not present…" }
        SchemaUtils.createMissingTablesAndColumns(OrderTable, OutboxEventTable)
        log.info { "Schema initialisation complete." }
    }
}

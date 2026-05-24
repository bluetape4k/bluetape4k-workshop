package io.bluetape4k.workshop.multitenant.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.multitenant.domain.InvoiceTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Creates workshop tables when the application starts.
 */
@Component
class ExposedSchemaInitializer : ApplicationRunner {

    companion object : KLogging()

    @Transactional
    override fun run(args: ApplicationArguments) {
        log.info { "Creating tenant isolation workshop schema." }
        SchemaUtils.createMissingTablesAndColumns(InvoiceTable)
    }
}

package io.bluetape4k.workshop.observability.advanced.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.observability.advanced.model.Users
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Initializes the database schema on application startup.
 *
 * ## Behavior / Contract
 * - Creates the `users` table if it does not exist.
 * - Fails fast on error — silent swallowing is prohibited.
 */
@Component
class SchemaInitializer : ApplicationRunner {

    companion object : KLogging()

    override fun run(args: ApplicationArguments) {
        try {
            transaction {
                SchemaUtils.create(Users)
            }
            log.info { "Database schema initialized successfully" }
        } catch (e: Exception) {
            log.error(e) { "Failed to initialize database schema" }
            throw e
        }
    }
}

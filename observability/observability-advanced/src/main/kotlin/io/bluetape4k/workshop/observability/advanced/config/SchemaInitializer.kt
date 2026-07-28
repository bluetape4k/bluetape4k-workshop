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
 * application startup 시 database schema 를 초기화합니다.
 *
 * ## Behavior / Contract
 * - `users` table 이 없으면 생성합니다.
 * - error 발생 시 fail-fast 하며 silent swallowing 은 금지합니다.
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

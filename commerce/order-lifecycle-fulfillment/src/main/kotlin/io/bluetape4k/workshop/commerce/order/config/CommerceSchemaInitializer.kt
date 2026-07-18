package io.bluetape4k.workshop.commerce.order.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.order.persistence.commerceTables
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class CommerceSchemaInitializer : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(*commerceTables)
        log.info { "commerce_schema_initialized tableCount=${commerceTables.size}" }
    }

    companion object : KLogging()
}

package io.bluetape4k.workshop.messaging.fallback.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.messaging.fallback.domain.OrderTable
import io.bluetape4k.workshop.messaging.fallback.publication.EventPublicationTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Creates the workshop tables used by the Kafka-first outbox fallback example.
 */
@Component
class ExposedConfig : ApplicationRunner {

    companion object : KLogging()

    @Transactional
    override fun run(args: ApplicationArguments) {
        log.info { "Creating kafka-outbox-fallback schema tables if not present." }
        SchemaUtils.create(OrderTable, EventPublicationTable)
        log.info { "Kafka-outbox-fallback schema initialization complete." }
    }
}

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
 * startup 시 [OrderTable] 과 [OutboxEventTable] database schema 를 생성합니다.
 *
 * `SchemaUtils.createMissingTablesAndColumns` 를 사용하므로 existing database 에 대해 실행해도 안전합니다. 이미 존재하는 column 과 table 은 그대로 둡니다.
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

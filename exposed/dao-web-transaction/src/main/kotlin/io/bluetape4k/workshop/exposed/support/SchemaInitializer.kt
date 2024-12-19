package io.bluetape4k.workshop.exposed.support

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.domain.UserEntity
import org.jetbrains.exposed.sql.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional
class SchemaInitializer: ApplicationRunner {

    companion object: KLogging()

    override fun run(args: ApplicationArguments?) {
        log.info { "Initialize Database Schema" }
        SchemaUtils.create(UserEntity)
    }
}

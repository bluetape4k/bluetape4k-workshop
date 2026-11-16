package io.bluetape4k.workshop.optimization.fieldservice.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** demo 전용 disposable schema initializer이며 production migration은 제공하지 않습니다. */
@Component
@Profile("demo")
internal class FieldServiceDatabaseInitializer: ApplicationRunner {

    companion object: KLogging()

    @Transactional
    override fun run(args: ApplicationArguments) {
        log.info { "Create database tables." }
        SchemaUtils.create(*FieldServiceTables.all)
    }
}

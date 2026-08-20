package io.bluetape4k.workshop.optimization.fieldservice.persistence

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** demo 전용 disposable schema initializer이며 production migration은 제공하지 않습니다. */
@Component
@Profile("demo")
internal class FieldServiceDatabaseInitializer : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(*FieldServiceTables.all)
    }
}

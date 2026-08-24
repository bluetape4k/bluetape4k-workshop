package io.bluetape4k.workshop.optimization.lastmile.persistence

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Synthetic demo schema는 disposable DB에서만 자동 생성합니다. */
@Component
class LastMileDatabaseInitializer : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(*LastMileTables.all)
    }
}

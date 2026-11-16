package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** PostgreSQL profile에서만 authoritative schema를 생성합니다. demo fake는 DB 없이 시작됩니다. */
@Profile("postgres")
@Component
internal class ShiftCoverageDatabaseInitializer: ApplicationRunner {

    companion object: KLogging()

    @Transactional
    override fun run(args: ApplicationArguments) {
        log.info { "Create ShiftCoverageTables ... " }
        SchemaUtils.create(*ShiftCoverageTables.all)
    }
}

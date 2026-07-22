package io.bluetape4k.workshop.commerce.usagebilling.query.config

import io.bluetape4k.workshop.commerce.usagebilling.query.persistence.QueryCheckpoints
import io.bluetape4k.workshop.commerce.usagebilling.query.persistence.QueryInboxEvents
import io.bluetape4k.workshop.commerce.usagebilling.query.persistence.QueryReadModels
import io.bluetape4k.workshop.commerce.usagebilling.query.persistence.QueryQuarantineEvents
import io.bluetape4k.workshop.commerce.usagebilling.query.persistence.QueryRedriveAudits
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class QuerySchemaInitializer : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(
            QueryInboxEvents,
            QueryReadModels,
            QueryCheckpoints,
            QueryQuarantineEvents,
            QueryRedriveAudits,
        )
    }
}

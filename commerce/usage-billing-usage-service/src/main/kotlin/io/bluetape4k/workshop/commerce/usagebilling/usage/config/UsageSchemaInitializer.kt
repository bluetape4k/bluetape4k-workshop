package io.bluetape4k.workshop.commerce.usagebilling.usage.config

import io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsageOutboxEvents
import io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsagePriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsageRecords
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class UsageSchemaInitializer : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(UsageOutboxEvents, UsagePriceEvidence, UsageRecords)
    }
}

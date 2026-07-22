package io.bluetape4k.workshop.commerce.usagebilling.meter.config

import io.bluetape4k.workshop.commerce.usagebilling.meter.persistence.MeterCommandReceipts
import io.bluetape4k.workshop.commerce.usagebilling.meter.persistence.MeterOutboxEvents
import io.bluetape4k.workshop.commerce.usagebilling.meter.persistence.MeterPriceVersions
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class MeterSchemaInitializer : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(MeterOutboxEvents, MeterPriceVersions, MeterCommandReceipts)
    }
}

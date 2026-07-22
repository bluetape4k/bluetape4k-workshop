package io.bluetape4k.workshop.commerce.usagebilling.invoice.config

import io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence.InvoiceLines
import io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence.InvoiceInboxEvents
import io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence.InvoiceOutboxEvents
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class InvoiceSchemaInitializer : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(InvoiceOutboxEvents, InvoiceInboxEvents, InvoiceLines)
    }
}

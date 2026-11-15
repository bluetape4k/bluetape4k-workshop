package io.bluetape4k.workshop.commerce.usagebilling.invoice.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxResult
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceJournal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InvoiceInboxService(
    private val journal: InvoiceJournal,
) {

    companion object: KLogging()

    @Transactional
    fun handle(event: InvoiceInboxEvent): InvoiceInboxResult {
        val outcome = journal.apply(event)
        log.debug { "outcome=$outcome" }
        return InvoiceInboxResult(outcome == InvoiceInboxOutcome.APPLIED, outcome)
    }
}

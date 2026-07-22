package io.bluetape4k.workshop.commerce.usagebilling.invoice.application

import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxResult
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceJournal
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceLine
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InvoiceInboxService(
    private val journal: InvoiceJournal,
) {
    @Transactional
    fun handle(event: InvoiceInboxEvent): InvoiceInboxResult {
        if (journal.findLine(event.eventId) != null) return InvoiceInboxResult(created = false)
        journal.append(InvoiceLine(event.eventId, event.correctionOf, event.amount))
        return InvoiceInboxResult(created = true)
    }
}

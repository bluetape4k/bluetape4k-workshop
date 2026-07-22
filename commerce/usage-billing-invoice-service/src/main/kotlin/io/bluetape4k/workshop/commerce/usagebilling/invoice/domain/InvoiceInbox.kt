package io.bluetape4k.workshop.commerce.usagebilling.invoice.domain

import io.bluetape4k.support.requireNotBlank
import java.math.BigDecimal
import java.util.UUID

data class InvoiceInboxEvent(
    val eventId: UUID,
    val eventType: String,
    val correctionOf: UUID?,
    val amount: BigDecimal,
) {
    init {
        eventType.requireNotBlank("eventType")
    }
}

data class InvoiceLine(
    val sourceEventId: UUID,
    val correctionOf: UUID?,
    val amount: BigDecimal,
)

data class InvoiceInboxResult(
    val created: Boolean,
)

interface InvoiceJournal {
    val lines: List<InvoiceLine>

    fun findLine(sourceEventId: UUID): InvoiceLine?

    fun append(line: InvoiceLine)
}

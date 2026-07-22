package io.bluetape4k.workshop.commerce.usagebilling.invoice.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceJournal
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceLine
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class InvoiceInboxServiceTest {
    private val journal = InMemoryInvoiceJournal()
    private val service = InvoiceInboxService(journal)

    @Test
    fun `ChargeRated creates one immutable invoice line with source event provenance`() {
        val event = chargeRated()

        service.handle(event).created shouldBeEqualTo true
        journal.lines.single().sourceEventId shouldBeEqualTo event.eventId
        journal.lines.single().correctionOf shouldBeEqualTo null
    }

    @Test
    fun `duplicate ChargeRated delivery creates no additional invoice line`() {
        val event = chargeRated()
        service.handle(event)

        service.handle(event).created shouldBeEqualTo false
        journal.lines.size shouldBeEqualTo 1
    }

    @Test
    fun `AdjustmentPosted appends a correction line without mutating original charge line`() {
        val original = chargeRated()
        service.handle(original)
        val correction = InvoiceInboxEvent(UUID.randomUUID(), "AdjustmentPosted", original.eventId, BigDecimal("-0.10"))

        service.handle(correction).created shouldBeEqualTo true
        journal.lines.size shouldBeEqualTo 2
        journal.lines[0].amount shouldBeEqualTo BigDecimal("0.10")
        journal.lines[1].correctionOf shouldBeEqualTo original.eventId
    }

    private fun chargeRated(): InvoiceInboxEvent =
        InvoiceInboxEvent(UUID.randomUUID(), "ChargeRated", null, BigDecimal("0.10"))

    private class InMemoryInvoiceJournal : InvoiceJournal {
        override val lines = mutableListOf<InvoiceLine>()

        override fun findLine(sourceEventId: UUID): InvoiceLine? =
            lines.firstOrNull { it.sourceEventId == sourceEventId }

        override fun append(line: InvoiceLine) {
            lines += line
        }
    }
}

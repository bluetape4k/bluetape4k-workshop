package io.bluetape4k.workshop.commerce.usagebilling.invoice.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.math.BigDecimal
import java.util.UUID

data class InvoiceInboxEvent(
    val eventId: UUID,
    val eventType: String,
    val correctionOf: UUID?,
    val amount: BigDecimal,
    val tenantId: String = "tenant-a",
    val payloadDigest: String = eventId.toString(),
) : Serializable {
    init {
        eventType.requireNotBlank("eventType")
        tenantId.requireNotBlank("tenantId")
        payloadDigest.requireNotBlank("payloadDigest")
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class InvoiceLine(
    val sourceEventId: UUID,
    val correctionOf: UUID?,
    val amount: BigDecimal,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class InvoiceInboxResult(
    val created: Boolean,
    val outcome: InvoiceInboxOutcome,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

enum class InvoiceInboxOutcome {
    APPLIED,
    DUPLICATE,
    QUARANTINED,
}

interface InvoiceJournal {
    val lines: List<InvoiceLine>

    fun findLine(sourceEventId: UUID): InvoiceLine?

    fun apply(event: InvoiceInboxEvent): InvoiceInboxOutcome
}

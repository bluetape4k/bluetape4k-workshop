package io.bluetape4k.workshop.multitenant.repository

import io.bluetape4k.workshop.multitenant.domain.InvoiceRecord
import io.bluetape4k.workshop.multitenant.domain.InvoiceTable
import io.bluetape4k.workshop.multitenant.domain.toInvoiceRecord
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

/**
 * Baseline repository that intentionally omits tenant predicates.
 *
 * This class exists only to make leakage risk executable in tests.
 */
@Repository
class UnsafeInvoiceRepository {

    /**
     * Finds by invoice ID alone, allowing a caller from another tenant to read the row.
     */
    fun findByIdWithoutTenant(invoiceId: Long): InvoiceRecord? =
        InvoiceTable
            .selectAll()
            .where { InvoiceTable.id eq invoiceId }
            .firstOrNull()
            ?.toInvoiceRecord()
}

package io.bluetape4k.workshop.multitenant.domain

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Shared invoice table. Tenant isolation is enforced by repository predicates.
 */
object InvoiceTable : LongIdTable("tenant_invoices") {
    val tenantId = varchar("tenant_id", 64)
    val customerName = varchar("customer_name", 120)
    val amount = decimal("amount", 19, 2)
    val status = enumerationByName("status", 20, InvoiceStatus::class).default(InvoiceStatus.OPEN)
}

/**
 * Maps an Exposed row to an [InvoiceRecord].
 */
fun ResultRow.toInvoiceRecord(): InvoiceRecord =
    InvoiceRecord(
        id = this[InvoiceTable.id].value,
        tenantId = TenantId(this[InvoiceTable.tenantId]),
        customerName = this[InvoiceTable.customerName],
        amount = this[InvoiceTable.amount],
        status = this[InvoiceTable.status],
    )

package io.bluetape4k.workshop.multitenant.domain

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * 공유 invoice table 입니다. tenant isolation 은 repository predicate 로 강제합니다.
 */
object InvoiceTable : LongIdTable("tenant_invoices") {
    val tenantId = varchar("tenant_id", 64)
    val customerName = varchar("customer_name", 120)
    val amount = decimal("amount", 19, 2)
    val status = enumerationByName("status", 20, InvoiceStatus::class).default(InvoiceStatus.OPEN)
}

/**
 * Exposed row 를 [InvoiceRecord] 로 mapping 합니다.
 */
fun ResultRow.toInvoiceRecord(): InvoiceRecord =
    InvoiceRecord(
        id = this[InvoiceTable.id].value,
        tenantId = TenantId(this[InvoiceTable.tenantId]),
        customerName = this[InvoiceTable.customerName],
        amount = this[InvoiceTable.amount],
        status = this[InvoiceTable.status],
    )

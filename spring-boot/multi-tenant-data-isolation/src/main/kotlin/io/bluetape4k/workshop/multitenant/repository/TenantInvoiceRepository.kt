package io.bluetape4k.workshop.multitenant.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.multitenant.domain.InvoiceRecord
import io.bluetape4k.workshop.multitenant.domain.InvoiceStatus
import io.bluetape4k.workshop.multitenant.domain.InvoiceTable
import io.bluetape4k.workshop.multitenant.domain.TenantId
import io.bluetape4k.workshop.multitenant.domain.toInvoiceRecord
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository

/**
 * Tenant-safe invoice repository backed by Bluetape4k's Exposed JDBC repository helper.
 *
 * Callers execute repository operations inside a Spring-managed transaction.
 */
@Repository
class TenantInvoiceRepository : LongJdbcRepository<InvoiceRecord> {

    companion object : KLogging()

    override val table = InvoiceTable

    override fun extractId(entity: InvoiceRecord): Long = entity.id

    override fun ResultRow.toEntity(): InvoiceRecord = toInvoiceRecord()

    /**
     * Inserts an invoice and returns the persisted record.
     */
    fun saveInvoice(invoice: InvoiceRecord): InvoiceRecord {
        val id = InvoiceTable.insertAndGetId {
            it[tenantId] = invoice.tenantId.value
            it[customerName] = invoice.customerName
            it[amount] = invoice.amount
            it[status] = invoice.status
        }.value
        return requireNotNull(findByTenantAndId(invoice.tenantId, id)) {
            "Inserted invoice $id was not found for tenant ${invoice.tenantId}"
        }
    }

    /**
     * Finds an invoice only when both tenant and ID match.
     */
    fun findByTenantAndId(tenantId: TenantId, invoiceId: Long): InvoiceRecord? =
        InvoiceTable
            .selectAll()
            .where {
                (InvoiceTable.tenantId eq tenantId.value) and (InvoiceTable.id eq invoiceId)
            }
            .firstOrNull()
            ?.toInvoiceRecord()

    /**
     * Lists invoices for a single tenant only.
     */
    fun findAllByTenant(tenantId: TenantId): List<InvoiceRecord> =
        InvoiceTable
            .selectAll()
            .where { InvoiceTable.tenantId eq tenantId.value }
            .orderBy(InvoiceTable.id, SortOrder.ASC)
            .map { it.toInvoiceRecord() }

    /**
     * Updates invoice status only when the tenant predicate also matches.
     */
    fun updateStatus(tenantId: TenantId, invoiceId: Long, status: InvoiceStatus): Boolean =
        InvoiceTable.update({
            (InvoiceTable.tenantId eq tenantId.value) and (InvoiceTable.id eq invoiceId)
        }) {
            it[InvoiceTable.status] = status
        } == 1

    /**
     * Removes all workshop data.
     */
    fun deleteAllInvoices() {
        InvoiceTable.deleteAll()
    }
}

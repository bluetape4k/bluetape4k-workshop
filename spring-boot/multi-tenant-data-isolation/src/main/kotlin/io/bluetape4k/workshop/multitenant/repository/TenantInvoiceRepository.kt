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
 * Bluetape4k Exposed JDBC repository helper 를 기반으로 하는 tenant-safe invoice repository 입니다.
 *
 * caller 는 Spring-managed transaction 안에서 repository 작업을 실행합니다.
 */
@Repository
class TenantInvoiceRepository : LongJdbcRepository<InvoiceRecord> {

    companion object : KLogging()

    override val table = InvoiceTable

    override fun extractId(entity: InvoiceRecord): Long = entity.id

    override fun ResultRow.toEntity(): InvoiceRecord = toInvoiceRecord()

    /**
     * invoice 를 삽입하고 저장된 record 를 반환합니다.
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
     * tenant 와 ID 가 모두 일치할 때만 invoice 를 찾습니다.
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
     * 단일 tenant 의 invoice 만 나열합니다.
     */
    fun findAllByTenant(tenantId: TenantId): List<InvoiceRecord> =
        InvoiceTable
            .selectAll()
            .where { InvoiceTable.tenantId eq tenantId.value }
            .orderBy(InvoiceTable.id, SortOrder.ASC)
            .map { it.toInvoiceRecord() }

    /**
     * tenant predicate 도 일치할 때만 invoice status 를 갱신합니다.
     */
    fun updateStatus(tenantId: TenantId, invoiceId: Long, status: InvoiceStatus): Boolean =
        InvoiceTable.update({
            (InvoiceTable.tenantId eq tenantId.value) and (InvoiceTable.id eq invoiceId)
        }) {
            it[InvoiceTable.status] = status
        } == 1

    /**
     * 모든 workshop data 를 제거합니다.
     */
    fun deleteAllInvoices() {
        InvoiceTable.deleteAll()
    }
}

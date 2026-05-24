package io.bluetape4k.workshop.multitenant.domain

import java.io.Serializable
import java.math.BigDecimal

/**
 * Invoice record persisted by the tenant-scoped Exposed repository.
 */
data class InvoiceRecord(
    val id: Long = 0L,
    val tenantId: TenantId,
    val customerName: String,
    val amount: BigDecimal,
    val status: InvoiceStatus = InvoiceStatus.OPEN,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 104L
    }
}

/**
 * Minimal invoice lifecycle for isolation tests.
 */
enum class InvoiceStatus {
    OPEN,
    PAID,
    VOID,
}

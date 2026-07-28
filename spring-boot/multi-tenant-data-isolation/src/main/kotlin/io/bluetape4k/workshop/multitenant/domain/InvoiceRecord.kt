package io.bluetape4k.workshop.multitenant.domain

import java.io.Serializable
import java.math.BigDecimal

/**
 * tenant-scoped Exposed repository 가 저장하는 invoice record 입니다.
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
 * isolation test 를 위한 최소 invoice lifecycle 입니다.
 */
enum class InvoiceStatus {
    OPEN,
    PAID,
    VOID,
}

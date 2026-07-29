package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.workshop.multitenant.domain.TenantId
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * workshop service 의 tenant-tagged counter 를 기록합니다.
 */
@Component
class TenantMetrics(
    private val meterRegistry: MeterRegistry,
) {

    /**
     * [tenantId] 의 invoice read 를 기록합니다.
     */
    fun recordInvoiceRead(tenantId: TenantId) {
        meterRegistry.counter(INVOICE_READS, "tenant", tenantId.value).increment()
    }

    /**
     * [tenantId] 의 invoice write 를 기록합니다.
     */
    fun recordInvoiceWrite(tenantId: TenantId) {
        meterRegistry.counter(INVOICE_WRITES, "tenant", tenantId.value).increment()
    }

    /**
     * [tenantId] 의 현재 read count 를 반환합니다.
     */
    fun invoiceReads(tenantId: TenantId): Double =
        meterRegistry.counter(INVOICE_READS, "tenant", tenantId.value).count()

    companion object {
        const val INVOICE_READS: String = "tenant.invoice.reads"
        const val INVOICE_WRITES: String = "tenant.invoice.writes"
    }
}

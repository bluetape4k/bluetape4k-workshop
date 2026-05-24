package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.workshop.multitenant.domain.TenantId
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Records tenant-tagged counters for the workshop service.
 */
@Component
class TenantMetrics(
    private val meterRegistry: MeterRegistry,
) {

    /**
     * Records an invoice read for [tenantId].
     */
    fun recordInvoiceRead(tenantId: TenantId) {
        meterRegistry.counter(INVOICE_READS, "tenant", tenantId.value).increment()
    }

    /**
     * Records an invoice write for [tenantId].
     */
    fun recordInvoiceWrite(tenantId: TenantId) {
        meterRegistry.counter(INVOICE_WRITES, "tenant", tenantId.value).increment()
    }

    /**
     * Returns the current read count for [tenantId].
     */
    fun invoiceReads(tenantId: TenantId): Double =
        meterRegistry.counter(INVOICE_READS, "tenant", tenantId.value).count()

    companion object {
        const val INVOICE_READS: String = "tenant.invoice.reads"
        const val INVOICE_WRITES: String = "tenant.invoice.writes"
    }
}

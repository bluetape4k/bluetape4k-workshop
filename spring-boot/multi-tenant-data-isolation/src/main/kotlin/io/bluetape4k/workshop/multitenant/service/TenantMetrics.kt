package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.multitenant.domain.TenantId
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * workshop service 의 tenant-fingerprint-tagged counter 를 기록합니다.
 *
 * tenant 원문은 관측 backend로 보내지 않고 SHA-256 prefix만 bounded tag로 사용합니다.
 */
@Component
class TenantMetrics(
    private val meterRegistry: MeterRegistry,
) {
    companion object: KLogging() {
        const val INVOICE_READS: String = "tenant.invoice.reads"
        const val INVOICE_WRITES: String = "tenant.invoice.writes"
        const val TENANT_FINGERPRINT_TAG: String = "tenant_fingerprint"


        /**
         * 운영 metric에 노출할 tenant 식별 fingerprint를 반환합니다.
         */
        fun tenantFingerprint(tenantId: TenantId): String =
            MessageDigest.getInstance("SHA-256")
                .digest(tenantId.value.toUtf8Bytes())
                .take(FINGERPRINT_BYTES)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val FINGERPRINT_BYTES: Int = 8
    }

    /**
     * [tenantId] 의 invoice read 를 기록합니다.
     */
    fun recordInvoiceRead(tenantId: TenantId) {
        getCounter(tenantId).increment()
    }

    /**
     * [tenantId] 의 invoice write 를 기록합니다.
     */
    fun recordInvoiceWrite(tenantId: TenantId) {
        getCounter(tenantId).increment()
    }

    /**
     * [tenantId] 의 현재 read count 를 반환합니다.
     */
    fun invoiceReads(tenantId: TenantId): Double =
        getCounter(tenantId).count()

    private fun getCounter(tenantId: TenantId): Counter =
        meterRegistry.counter(INVOICE_READS, TENANT_FINGERPRINT_TAG, tenantFingerprint(tenantId))

}

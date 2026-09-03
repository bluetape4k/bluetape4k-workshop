package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.workshop.multitenant.domain.TenantId
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.UTF_8
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

    /**
     * [tenantId] 의 invoice read 를 기록합니다.
     */
    fun recordInvoiceRead(tenantId: TenantId) {
        meterRegistry.counter(INVOICE_READS, TENANT_FINGERPRINT_TAG, tenantFingerprint(tenantId)).increment()
    }

    /**
     * [tenantId] 의 invoice write 를 기록합니다.
     */
    fun recordInvoiceWrite(tenantId: TenantId) {
        meterRegistry.counter(INVOICE_WRITES, TENANT_FINGERPRINT_TAG, tenantFingerprint(tenantId)).increment()
    }

    /**
     * [tenantId] 의 현재 read count 를 반환합니다.
     */
    fun invoiceReads(tenantId: TenantId): Double =
        meterRegistry.counter(INVOICE_READS, TENANT_FINGERPRINT_TAG, tenantFingerprint(tenantId)).count()

    companion object {
        const val INVOICE_READS: String = "tenant.invoice.reads"
        const val INVOICE_WRITES: String = "tenant.invoice.writes"
        const val TENANT_FINGERPRINT_TAG: String = "tenant_fingerprint"

        /**
         * 운영 metric에 노출할 tenant 식별 fingerprint를 반환합니다.
         */
        fun tenantFingerprint(tenantId: TenantId): String =
            MessageDigest.getInstance("SHA-256")
                .digest(tenantId.value.toByteArray(UTF_8))
                .take(FINGERPRINT_BYTES)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val FINGERPRINT_BYTES: Int = 8
    }
}

package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.workshop.multitenant.domain.TenantId
import org.springframework.stereotype.Component

/**
 * Builds tenant-prefixed keys for every non-database isolation boundary.
 */
@Component
class TenantKeyFactory {

    /**
     * Cache key that includes tenant scope.
     */
    fun invoiceCacheKey(tenantId: TenantId, invoiceId: Long): String =
        "${tenantId.keyPrefix()}:invoice:$invoiceId"

    /**
     * Baseline cache key that intentionally omits tenant scope.
     */
    fun unsafeInvoiceCacheKey(invoiceId: Long): String = "invoice:$invoiceId"

    /**
     * Lock key that includes tenant scope.
     */
    fun invoiceLockKey(tenantId: TenantId, invoiceId: Long): String =
        "${tenantId.keyPrefix()}:lock:invoice:$invoiceId"

    /**
     * Rate-limit key that includes tenant scope and the caller principal.
     */
    fun rateLimitKey(tenantId: TenantId, principal: String): String =
        "${tenantId.keyPrefix()}:rate-limit:$principal"
}

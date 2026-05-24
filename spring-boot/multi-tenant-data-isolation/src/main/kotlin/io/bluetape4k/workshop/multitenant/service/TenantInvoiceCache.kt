package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.workshop.multitenant.domain.InvoiceRecord
import io.bluetape4k.workshop.multitenant.domain.TenantId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache that demonstrates the difference between tenant-safe and unsafe keys.
 */
@Component
class TenantInvoiceCache(
    private val keyFactory: TenantKeyFactory,
) {

    private val cache = ConcurrentHashMap<String, InvoiceRecord>()

    /**
     * Reads with a tenant-prefixed key.
     */
    fun getOrLoad(
        tenantId: TenantId,
        invoiceId: Long,
        loader: () -> InvoiceRecord?,
    ): InvoiceRecord? {
        val key = keyFactory.invoiceCacheKey(tenantId, invoiceId)
        cache[key]?.let { return it }
        return loader()?.also { cache[key] = it }
    }

    /**
     * Reads with an unsafe key that omits tenant scope.
     */
    fun getOrLoadUnsafe(
        invoiceId: Long,
        loader: () -> InvoiceRecord?,
    ): InvoiceRecord? {
        val key = keyFactory.unsafeInvoiceCacheKey(invoiceId)
        cache[key]?.let { return it }
        return loader()?.also { cache[key] = it }
    }

    /**
     * Returns current cache keys for workshop assertions.
     */
    fun keys(): Set<String> = cache.keys.toSet()

    /**
     * Evicts a tenant-scoped invoice cache entry.
     */
    fun evict(tenantId: TenantId, invoiceId: Long) {
        cache.remove(keyFactory.invoiceCacheKey(tenantId, invoiceId))
    }

    /**
     * Clears all cache entries.
     */
    fun clear() {
        cache.clear()
    }
}

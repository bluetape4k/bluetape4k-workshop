package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.workshop.multitenant.domain.InvoiceRecord
import io.bluetape4k.workshop.multitenant.domain.TenantId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * tenant-safe key 와 unsafe key 의 차이를 보여주는 in-memory cache 입니다.
 */
@Component
class TenantInvoiceCache(
    private val keyFactory: TenantKeyFactory,
) {

    private val cache = ConcurrentHashMap<String, InvoiceRecord>()

    /**
     * tenant prefix 가 포함된 key 로 읽습니다.
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
     * tenant scope 를 생략한 unsafe key 로 읽습니다.
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
     * workshop assertion 에 사용할 현재 cache key 를 반환합니다.
     */
    fun keys(): Set<String> = cache.keys.toSet()

    /**
     * tenant-scoped invoice cache entry 를 evict 합니다.
     */
    fun evict(tenantId: TenantId, invoiceId: Long) {
        cache.remove(keyFactory.invoiceCacheKey(tenantId, invoiceId))
    }

    /**
     * 모든 cache entry 를 지웁니다.
     */
    fun clear() {
        cache.clear()
    }
}

package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.multitenant.domain.InvoiceRecord
import io.bluetape4k.workshop.multitenant.domain.InvoiceStatus
import io.bluetape4k.workshop.multitenant.domain.TenantId
import io.bluetape4k.workshop.multitenant.repository.TenantInvoiceRepository
import io.bluetape4k.workshop.multitenant.repository.UnsafeInvoiceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Application service that composes tenant-scoped data, cache, lock, rate-limit, and metric paths.
 */
@Service
class TenantInvoiceService(
    private val tenantRepository: TenantInvoiceRepository,
    private val unsafeRepository: UnsafeInvoiceRepository,
    private val invoiceCache: TenantInvoiceCache,
    private val lockRegistry: TenantLockRegistry,
    private val rateLimiter: TenantRateLimiter,
    private val metrics: TenantMetrics,
) {

    companion object : KLogging()

    /**
     * Creates a tenant-scoped invoice.
     */
    @Transactional
    fun createInvoice(invoice: InvoiceRecord): InvoiceRecord =
        tenantRepository.saveInvoice(invoice).also {
            metrics.recordInvoiceWrite(it.tenantId)
        }

    /**
     * Reads an invoice through the tenant-safe repository and cache.
     */
    @Transactional(readOnly = true)
    fun findInvoice(tenantId: TenantId, invoiceId: Long): InvoiceRecord? =
        invoiceCache.getOrLoad(tenantId, invoiceId) {
            tenantRepository.findByTenantAndId(tenantId, invoiceId)
        }?.also {
            metrics.recordInvoiceRead(tenantId)
        }

    /**
     * Updates an invoice only when the tenant predicate matches.
     */
    @Transactional
    fun markPaid(tenantId: TenantId, invoiceId: Long): Boolean =
        tenantRepository.updateStatus(tenantId, invoiceId, InvoiceStatus.PAID).also { updated ->
            if (updated) {
                invoiceCache.evict(tenantId, invoiceId)
            }
        }

    /**
     * Baseline read path that intentionally omits tenant scope.
     */
    @Transactional(readOnly = true)
    fun unsafeFindInvoiceForBaseline(requestingTenantId: TenantId, invoiceId: Long): InvoiceRecord? =
        invoiceCache.getOrLoadUnsafe(invoiceId) {
            unsafeRepository.findByIdWithoutTenant(invoiceId)
        }?.also {
            metrics.recordInvoiceRead(requestingTenantId)
        }

    /**
     * Executes a tenant-keyed invoice lock scenario.
     */
    fun <T> withInvoiceLock(tenantId: TenantId, invoiceId: Long, block: (String) -> T): T =
        lockRegistry.withInvoiceLock(tenantId, invoiceId, block)

    /**
     * Consumes one request from a tenant-keyed rate-limit bucket.
     */
    fun tryRateLimit(tenantId: TenantId, principal: String, limit: Int = 2): RateLimitDecision =
        rateLimiter.tryConsume(tenantId, principal, limit)

    /**
     * Clears volatile state for tests and repeatable workshop runs.
     */
    fun resetVolatileState() {
        invoiceCache.clear()
        lockRegistry.clear()
        rateLimiter.clear()
    }

    /**
     * Clears persistent and volatile workshop state.
     */
    @Transactional
    fun resetWorkshopState() {
        tenantRepository.deleteAllInvoices()
        resetVolatileState()
    }
}

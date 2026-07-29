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
 * tenant-scoped data, cache, lock, rate-limit, metric path 를 조합하는 application service 입니다.
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
     * tenant-scoped invoice 를 생성합니다.
     */
    @Transactional
    fun createInvoice(invoice: InvoiceRecord): InvoiceRecord =
        tenantRepository.saveInvoice(invoice).also {
            metrics.recordInvoiceWrite(it.tenantId)
        }

    /**
     * tenant-safe repository 와 cache 를 통해 invoice 를 읽습니다.
     */
    @Transactional(readOnly = true)
    fun findInvoice(tenantId: TenantId, invoiceId: Long): InvoiceRecord? =
        invoiceCache.getOrLoad(tenantId, invoiceId) {
            tenantRepository.findByTenantAndId(tenantId, invoiceId)
        }?.also {
            metrics.recordInvoiceRead(tenantId)
        }

    /**
     * tenant predicate 가 일치할 때만 invoice 를 갱신합니다.
     */
    @Transactional
    fun markPaid(tenantId: TenantId, invoiceId: Long): Boolean =
        tenantRepository.updateStatus(tenantId, invoiceId, InvoiceStatus.PAID).also { updated ->
            if (updated) {
                invoiceCache.evict(tenantId, invoiceId)
            }
        }

    /**
     * tenant scope 를 의도적으로 생략하는 baseline read path 입니다.
     */
    @Transactional(readOnly = true)
    fun unsafeFindInvoiceForBaseline(requestingTenantId: TenantId, invoiceId: Long): InvoiceRecord? =
        invoiceCache.getOrLoadUnsafe(invoiceId) {
            unsafeRepository.findByIdWithoutTenant(invoiceId)
        }?.also {
            metrics.recordInvoiceRead(requestingTenantId)
        }

    /**
     * tenant-keyed invoice lock scenario 를 실행합니다.
     */
    fun <T> withInvoiceLock(tenantId: TenantId, invoiceId: Long, block: (String) -> T): T =
        lockRegistry.withInvoiceLock(tenantId, invoiceId, block)

    /**
     * tenant-keyed rate-limit bucket 에서 요청 하나를 소비합니다.
     */
    fun tryRateLimit(tenantId: TenantId, principal: String, limit: Int = 2): RateLimitDecision =
        rateLimiter.tryConsume(tenantId, principal, limit)

    /**
     * 테스트와 반복 가능한 workshop 실행을 위해 volatile state 를 지웁니다.
     */
    fun resetVolatileState() {
        invoiceCache.clear()
        lockRegistry.clear()
        rateLimiter.clear()
    }

    /**
     * persistent workshop state 와 volatile workshop state 를 모두 지웁니다.
     */
    @Transactional
    fun resetWorkshopState() {
        tenantRepository.deleteAllInvoices()
        resetVolatileState()
    }
}

package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.workshop.multitenant.domain.TenantId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tenant-keyed lock registry for deterministic workshop tests.
 */
@Component
class TenantLockRegistry(
    private val keyFactory: TenantKeyFactory,
) {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Executes [block] while holding a lock whose key includes tenant scope.
     */
    fun <T> withInvoiceLock(tenantId: TenantId, invoiceId: Long, block: (String) -> T): T {
        val key = keyFactory.invoiceLockKey(tenantId, invoiceId)
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        return lock.withLock { block(key) }
    }

    /**
     * Returns the lock keys created during the workshop scenario.
     */
    fun keys(): Set<String> = locks.keys.toSet()

    /**
     * Clears all lock entries.
     */
    fun clear() {
        locks.clear()
    }
}

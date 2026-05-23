package io.bluetape4k.workshop.lock.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.lock.domain.DeductionResult
import io.bluetape4k.workshop.lock.domain.DeductionResult.InsufficientStock
import io.bluetape4k.workshop.lock.domain.DeductionResult.LockNotAcquired
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.InventoryStore
import org.redisson.api.RedissonClient
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Inventory service that uses [org.redisson.api.RLock] for mutual exclusion.
 *
 * ## Behavior / Contract
 * - Uses 3-argument `tryLock(waitMs, leaseMs, unit)` to disable the watchdog.
 *   Without an explicit lease, Redisson's watchdog extends the lock indefinitely,
 *   risking a never-expiring lock if the holder crashes.
 * - Returns [LockNotAcquired] when the lock cannot be acquired within [waitMs].
 * - The `finally` block releases the lock only if it is still held by the current thread.
 */
class LockedInventoryService(
    private val redisson: RedissonClient,
    private val store: InventoryStore,
) {

    companion object : KLogging()

    fun deduct(
        id: Long,
        qty: Int,
        waitMs: Long = 2000L,
        leaseMs: Long = 5000L,
    ): DeductionResult {
        qty.requirePositiveNumber("qty")
        val lockName = "inventory:lock:$id"
        val lock = redisson.getLock(lockName)
        val acquired = lock.tryLock(waitMs, leaseMs, MILLISECONDS)
        if (!acquired) {
            log.warn { "Failed to acquire lock: $lockName" }
            return LockNotAcquired(lockName)
        }
        try {
            val current = store.currentStock(id)
            if (current < qty) return InsufficientStock(qty, current)
            val remaining = store.applyChange(id, -qty)
            return Success(remaining)
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }
}

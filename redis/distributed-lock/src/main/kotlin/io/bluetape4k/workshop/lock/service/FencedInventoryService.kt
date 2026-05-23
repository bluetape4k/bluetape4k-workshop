package io.bluetape4k.workshop.lock.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.lock.domain.DeductionResult
import io.bluetape4k.workshop.lock.domain.DeductionResult.InsufficientStock
import io.bluetape4k.workshop.lock.domain.DeductionResult.LockNotAcquired
import io.bluetape4k.workshop.lock.domain.DeductionResult.Rejected
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.InventoryStore
import io.bluetape4k.workshop.lock.fenced.FencedResources
import org.redisson.api.RedissonClient
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Inventory service that uses [org.redisson.api.RFencedLock] for fencing-token-based protection.
 *
 * ## Behavior / Contract
 * - Uses the blocking `tryLockAndGetToken(waitMs, leaseMs, unit)` — safe to call from blocking threads.
 * - Delegates guarded writes to [FencedResources]: if the token is stale, returns [Rejected].
 * - The `finally` block wraps `unlock()` in `runCatching` to absorb `IllegalMonitorStateException`
 *   when the lease has already expired.
 */
class FencedInventoryService(
    private val redisson: RedissonClient,
    private val store: InventoryStore,
    private val fencedResources: FencedResources,
) {

    companion object : KLogging()

    fun deduct(
        id: Long,
        qty: Int,
        waitMs: Long = 2000L,
        leaseMs: Long = 5000L,
    ): DeductionResult {
        qty.requirePositiveNumber("qty")
        val lockName = "inventory:fenced:$id"
        val fLock = redisson.getFencedLock(lockName)
        val token: Long? = fLock.tryLockAndGetToken(waitMs, leaseMs, MILLISECONDS)
        if (token == null) {
            log.warn { "Failed to acquire fenced lock: $lockName" }
            return LockNotAcquired(lockName)
        }
        try {
            val current = store.currentStock(id)
            if (current < qty) return InsufficientStock(qty, current)
            return fencedResources.forResource(id).apply(token) {
                val remaining = store.applyChange(id, -qty)
                Success(remaining, token)
            } ?: Rejected(token)
        } finally {
            runCatching { fLock.unlock() }
                .onFailure { log.warn(it) { "Fenced unlock failed (lease may have expired): $lockName" } }
        }
    }
}

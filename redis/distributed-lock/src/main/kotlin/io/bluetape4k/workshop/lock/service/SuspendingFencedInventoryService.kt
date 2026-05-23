package io.bluetape4k.workshop.lock.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.redisson.coroutines.getLockId
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.lock.domain.DeductionResult
import io.bluetape4k.workshop.lock.domain.DeductionResult.InsufficientStock
import io.bluetape4k.workshop.lock.domain.DeductionResult.LockNotAcquired
import io.bluetape4k.workshop.lock.domain.DeductionResult.Rejected
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.InventoryStore
import io.bluetape4k.workshop.lock.fenced.FencedResources
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Coroutine-safe inventory service using [org.redisson.api.RFencedLock] with explicit lock identity.
 *
 * ## Behavior / Contract
 * - Uses [RedissonClient.getLockId] to obtain a stable, coroutine-safe lock identity
 *   (Snowflake ID). This identity must be passed to both `tryLockAsync` and `unlockAsync`.
 * - Two-step acquire: `tryLockAsync(lockId)` then `tokenAsync.await()`.
 *   There is no `tryLockAndGetTokenAsync(lockId)` overload in Redisson 4.x.
 * - The `finally` block runs `unlockAsync(lockId)` inside `withContext(NonCancellable)`.
 *   Without this guard, coroutine cancellation causes the await to throw [kotlinx.coroutines.CancellationException]
 *   before the unlock completes, leaking the lock until lease expiry.
 * - [beforeWork] is a **test seam** — defaults to a no-op. Inject `suspend { delay(ms) }`
 *   in tests to create a reliable cancellation window inside the lock-held section.
 */
class SuspendingFencedInventoryService(
    private val redisson: RedissonClient,
    private val store: InventoryStore,
    private val fencedResources: FencedResources,
    private val beforeWork: suspend () -> Unit = {},
) {

    companion object : KLoggingChannel()

    suspend fun deduct(
        id: Long,
        qty: Int,
        waitMs: Long = 2000L,
        leaseMs: Long = 5000L,
    ): DeductionResult {
        qty.requirePositiveNumber("qty")
        val lockName = "inventory:sfenced:$id"
        val lockId = redisson.getLockId(lockName)
        val fLock = redisson.getFencedLock(lockName)

        val acquired = fLock.tryLockAsync(waitMs, leaseMs, MILLISECONDS, lockId).await()
        if (!acquired) {
            log.warn { "Failed to acquire suspending fenced lock: $lockName (lockId=$lockId)" }
            return LockNotAcquired(lockName)
        }

        val token: Long = fLock.tokenAsync.await()
        try {
            beforeWork()  // test seam: default no-op; inject delay for cancellation tests
            val current = store.currentStock(id)
            if (current < qty) return InsufficientStock(qty, current)
            return fencedResources.forResource(id).apply(token) {
                val remaining = store.applyChange(id, -qty)
                Success(remaining, token)
            } ?: Rejected(token)
        } finally {
            // CRITICAL: NonCancellable prevents CancellationException from aborting the unlock.
            // Without this, a cancelled coroutine leaks the lock until lease expiry.
            withContext(NonCancellable) {
                try {
                    fLock.unlockAsync(lockId).await()
                } catch (e: Exception) {
                    log.warn(e) { "Suspending fenced unlock failed: $lockName (lockId=$lockId)" }
                }
            }
        }
    }
}

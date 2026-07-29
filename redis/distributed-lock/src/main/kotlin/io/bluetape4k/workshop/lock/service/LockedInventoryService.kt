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
 * [org.redisson.api.RLock]으로 상호 배제를 적용하는 재고 서비스입니다.
 *
 * ## 동작 계약
 * - watchdog을 비활성화하려고 3개 인자 `tryLock(waitMs, leaseMs, unit)`을 사용합니다.
 *   명시적인 lease가 없으면 Redisson watchdog이 lock을 계속 연장하므로,
 *   보유자가 중단될 때 만료되지 않는 lock이 남을 수 있습니다.
 * - [waitMs] 안에 lock을 얻지 못하면 [LockNotAcquired]를 반환합니다.
 * - `finally` 블록은 현재 thread가 아직 lock을 보유할 때만 해제합니다.
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

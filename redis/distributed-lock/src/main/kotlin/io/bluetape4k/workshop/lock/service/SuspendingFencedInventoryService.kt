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
 * 명시적인 lock identity와 [org.redisson.api.RFencedLock]을 사용하는 coroutine-safe 재고 서비스입니다.
 *
 * ## 동작 계약
 * - [RedissonClient.getLockId]로 coroutine-safe한 안정적 lock identity(Snowflake ID)를 얻습니다.
 *   이 identity는 `tryLockAsync`와 `unlockAsync` 모두에 전달해야 합니다.
 * - lock 획득은 `tryLockAsync(lockId)` 뒤에 `tokenAsync.await()`를 호출하는 2단계입니다.
 *   Redisson 4.x에는 `tryLockAndGetTokenAsync(lockId)` overload가 없습니다.
 * - `finally` 블록은 `withContext(NonCancellable)` 안에서 `unlockAsync(lockId)`를 실행합니다.
 *   이 보호가 없으면 coroutine 취소 때문에 unlock 완료 전에 await가 [kotlinx.coroutines.CancellationException]을 던지고,
 *   lease 만료 전까지 lock이 누수될 수 있습니다.
 * - [beforeWork]는 **테스트용 삽입 지점**이며 기본값은 no-op입니다. 테스트에서 `suspend { delay(ms) }`를 주입해
 *   lock 보유 구간 안에 안정적인 취소 창을 만듭니다.
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
            beforeWork()  // 테스트용 삽입 지점: 기본 no-op이며 취소 테스트에서는 delay를 주입합니다.
            val current = store.currentStock(id)
            if (current < qty) return InsufficientStock(qty, current)
            return fencedResources.forResource(id).apply(token) {
                val remaining = store.applyChange(id, -qty)
                Success(remaining, token)
            } ?: Rejected(token)
        } finally {
            // 중요: NonCancellable은 CancellationException이 unlock을 중단하지 못하게 합니다.
            // 이 보호가 없으면 취소된 coroutine이 lease 만료 전까지 lock을 누수합니다.
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

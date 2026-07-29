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
 * [org.redisson.api.RFencedLock]으로 fencing-token 기반 보호를 적용하는 재고 서비스입니다.
 *
 * ## 동작 계약
 * - blocking API인 `tryLockAndGetToken(waitMs, leaseMs, unit)`을 사용하므로 blocking thread에서 호출해도 됩니다.
 * - 보호된 쓰기는 [FencedResources]에 위임합니다. token이 오래되었으면 [Rejected]를 반환합니다.
 * - lease가 이미 만료된 경우의 `IllegalMonitorStateException`을 흡수하기 위해
 *   `finally` 블록에서 `unlock()`을 `runCatching`으로 감쌉니다.
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

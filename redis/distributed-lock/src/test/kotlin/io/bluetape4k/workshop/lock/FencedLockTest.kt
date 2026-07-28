package io.bluetape4k.workshop.lock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.workshop.lock.domain.DeductionResult.LockNotAcquired
import io.bluetape4k.workshop.lock.domain.DeductionResult.Rejected
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.Inventory
import io.bluetape4k.workshop.lock.fenced.FencedResource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger

/**
 * [FencedResource]와 [io.bluetape4k.workshop.lock.service.FencedInventoryService]의 동작을 검증합니다.
 */
class FencedLockTest : AbstractDistributedLockTest() {

    private val inventoryId = 1L

    @BeforeEach
    fun setupInventory() {
        store.register(Inventory(inventoryId, "사과", 100))
    }

    @Test
    fun `RFencedLock 토큰은 단조 증가한다`() {
        val lockName = randomName("fenced")
        val fLock = redisson.getFencedLock(lockName)
        val tokens = mutableListOf<Long>()

        repeat(5) {
            val token = fLock.tryLockAndGetToken(1000, 5000, MILLISECONDS)
            token.shouldNotBeNull()
            tokens.add(token)
            fLock.unlock()
        }

        tokens.zipWithNext().forEach { (a, b) ->
            b shouldBeGreaterThan a
        }
    }

    @Test
    fun `FencedResource - 구 토큰으로 apply 시도 시 null 반환, 동일 토큰 재진입 허용`() {
        val resource = FencedResource(99L)

        resource.apply(5L) { "ok" }.shouldNotBeNull()
        resource.apply(3L) { "should reject" }.shouldBeNull()     // 오래된 token: 3 < 5
        resource.apply(5L) { "same ok" }.shouldNotBeNull()        // 동일 token은 재진입으로 허용합니다.
        resource.apply(4L) { "also reject" }.shouldBeNull()       // 오래된 token: 4 < 5
    }

    @Test
    fun `FencedInventoryService - 동시 차감 시 oversell 이 발생하지 않는다`() {
        val successCount = AtomicInteger(0)

        MultithreadingTester()
            .workers(20)
            .rounds(1)
            .add {
                val result = fencedService.deduct(inventoryId, 10, waitMs = 3000L, leaseMs = 5000L)
                if (result is Success) successCount.incrementAndGet()
            }
            .run()

        successCount.get() shouldBeEqualTo 10
        store.currentStock(inventoryId) shouldBeEqualTo 0
    }

    @Test
    fun `FencedInventoryService - 구 토큰으로 차감 시도 시 Rejected 반환`() {
        // 공유 FencedResource에 매우 큰 token을 먼저 심어 둡니다.
        // 새 Redisson lock token은 1부터 시작하므로 9999보다 작아 Rejected가 됩니다.
        fencedResources.forResource(inventoryId).apply(9999L) { Unit }

        val result = fencedService.deduct(inventoryId, 10, waitMs = 2000L, leaseMs = 5000L)

        result shouldBeInstanceOf Rejected::class
    }

    @Test
    fun `FencedInventoryService - 락 획득 실패 시 LockNotAcquired 반환`() {
        // RFencedLock은 reentrant이므로 main thread의 획득을 막으려면 다른 thread에서 보유해야 합니다.
        val lockName = "inventory:fenced:$inventoryId"
        val holderLock = redisson.getFencedLock(lockName)
        val acquireLatch = java.util.concurrent.CountDownLatch(1)
        val releaseLatch = java.util.concurrent.CountDownLatch(1)

        val holder = Thread {
            val token = holderLock.tryLockAndGetToken(1000, 10_000, MILLISECONDS)
            acquireLatch.countDown()
            if (token != null) {
                releaseLatch.await()
                runCatching { holderLock.unlock() }
            }
        }
        holder.start()
        acquireLatch.await()  // holder thread가 fenced lock을 보유할 때까지 기다립니다.

        try {
            val result = fencedService.deduct(inventoryId, 10, waitMs = 0L, leaseMs = 1000L)
            result shouldBeInstanceOf LockNotAcquired::class
        } finally {
            releaseLatch.countDown()
            holder.join(2000)
        }
    }
}

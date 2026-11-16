package io.bluetape4k.workshop.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.logging.debug
import io.bluetape4k.utils.Runtimex
import io.bluetape4k.workshop.lock.domain.DeductionResult
import io.bluetape4k.workshop.lock.domain.DeductionResult.InsufficientStock
import io.bluetape4k.workshop.lock.domain.DeductionResult.LockNotAcquired
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.Inventory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [io.bluetape4k.workshop.lock.service.LockedInventoryService]가 [org.redisson.api.RLock]으로
 * 동시 부하의 oversell을 막는지 검증합니다.
 */
class DistributedLockTest: AbstractDistributedLockTest() {

    private val inventoryId = 1L

    @BeforeEach
    fun setupInventory() {
        store.register(Inventory(inventoryId, "사과", 100))
    }

    @RepeatedTest(3)
    fun `LockedInventoryService - 동시 차감 시 oversell 이 발생하지 않는다`() {
        val successCount = AtomicInteger(0)

        MultithreadingTester()
            .workers(Runtimex.availableProcessors)
            .rounds(2)
            .add {
                val result: DeductionResult = lockedService.deduct(inventoryId, 10, waitMs = 3000L, leaseMs = 5000L)
                log.debug { "result=$result" }
                if (result is Success) successCount.incrementAndGet()
            }
            .run()

        successCount.get() shouldBeEqualTo 10
        store.currentStock(inventoryId) shouldBeEqualTo 0
    }

    @RepeatedTest(3)
    fun `LockedInventoryService - Virtual Thread 동시 차감 시 oversell 이 발생하지 않는다`() {
        val successCount = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(Runtimex.availableProcessors * 2)
            .add {
                val result: DeductionResult = lockedService.deduct(inventoryId, 10, waitMs = 3000L, leaseMs = 5000L)
                log.debug { "result=$result" }
                if (result is Success) successCount.incrementAndGet()
            }
            .run()

        successCount.get() shouldBeEqualTo 10
        store.currentStock(inventoryId) shouldBeEqualTo 0
    }

    @Test
    fun `qty 가 0 이하이면 IllegalArgumentException 을 던진다`() {
        assertFailsWith<IllegalArgumentException> { lockedService.deduct(inventoryId, 0) }
        assertFailsWith<IllegalArgumentException> { lockedService.deduct(inventoryId, -1) }
    }

    @Test
    fun `재고 부족 시 InsufficientStock 을 반환한다`() {
        val result = lockedService.deduct(inventoryId, 200)

        log.debug { "result=$result" }
        result.shouldBeInstanceOf<InsufficientStock>()
        result.requested shouldBeEqualTo 200
        result.available shouldBeLessThan 200
    }

    @Test
    fun `락 획득 실패 시 LockNotAcquired 반환`() {
        // RLock은 reentrant이므로 main thread의 획득을 막으려면 다른 thread에서 보유해야 합니다.
        val lockName = "inventory:lock:$inventoryId"
        val holderLock = redisson.getLock(lockName)
        val acquireLatch = java.util.concurrent.CountDownLatch(1)
        val releaseLatch = java.util.concurrent.CountDownLatch(1)

        val holder = thread {
            val held = holderLock.tryLock(1000, 10_000, MILLISECONDS)
            acquireLatch.countDown()
            if (held) {
                releaseLatch.await()
                if (holderLock.isHeldByCurrentThread) holderLock.unlock() 
            }
        }

        acquireLatch.await()  // holder thread가 lock을 보유할 때까지 기다립니다.

        try {
            val result = lockedService.deduct(inventoryId, 10, waitMs = 0L, leaseMs = 1000L)
            log.debug { "result=$result" }
            result shouldBeInstanceOf LockNotAcquired::class
        } finally {
            releaseLatch.countDown()
            holder.join(2000)
        }
    }
}

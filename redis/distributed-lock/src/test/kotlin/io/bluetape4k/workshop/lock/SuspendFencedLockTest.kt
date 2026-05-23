package io.bluetape4k.workshop.lock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.lock.domain.DeductionResult.LockNotAcquired
import io.bluetape4k.workshop.lock.domain.DeductionResult.Rejected
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.Inventory
import io.bluetape4k.workshop.lock.service.SuspendingFencedInventoryService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies [SuspendingFencedInventoryService] correctness and cancellation safety.
 */
class SuspendFencedLockTest : AbstractDistributedLockTest() {

    private val inventoryId = 1L

    @BeforeEach
    fun setupInventory() {
        store.register(Inventory(inventoryId, "사과", 100))
    }

    @RepeatedTest(3)
    fun `SuspendingFencedInventoryService - 동시 코루틴 차감 시 oversell 이 발생하지 않는다`() = runSuspendIO {
        val successCount = AtomicInteger(0)

        // workers(20).rounds(20): totalUnits = rounds * blockCount = 20 * 1 = 20 attempts
        // With stock=100, qty=10 → exactly 10 succeed, 10 get InsufficientStock
        SuspendedJobTester()
            .workers(20)
            .rounds(20)
            .add {
                val result = suspendingService.deduct(inventoryId, 10, waitMs = 5000L, leaseMs = 5000L)
                if (result is Success) successCount.incrementAndGet()
            }
            .run()

        successCount.get() shouldBeEqualTo 10
        store.currentStock(inventoryId) shouldBeEqualTo 0
    }

    @Test
    fun `코루틴 취소 시 락이 정상 해제됨 (NonCancellable unlock 검증)`() = runSuspendIO {
        store.register(Inventory(999L, "취소테스트", 100))
        val lockName = "inventory:sfenced:999"
        val fLock = redisson.getFencedLock(lockName)

        // beforeWork seam: delay(500) fires inside the lock-held section,
        // ensuring delay(50) cancel lands DURING the lock-held window — not after deduct() returns.
        val slowService = SuspendingFencedInventoryService(
            redisson = redisson,
            store = store,
            fencedResources = fencedResources,
            beforeWork = { delay(500) },
        )

        val job = launch { slowService.deduct(999L, 10) }
        delay(50)        // fires during the 500ms beforeWork window — lock is held
        job.cancel()
        job.join()

        // NonCancellable ensures unlock completes even after cancel
        fLock.isLocked.shouldBeFalse()
    }

    @Test
    fun `SuspendingFencedInventoryService - 구 토큰으로 차감 시도 시 Rejected 반환`() = runSuspendIO {
        // Pre-seed the shared FencedResource with a very high token.
        // Any new Redisson lock token (starting from 1) will be strictly less than 9999 → Rejected.
        fencedResources.forResource(inventoryId).apply(9999L) { Unit }

        val result = suspendingService.deduct(inventoryId, 10, waitMs = 2000L, leaseMs = 5000L)

        result shouldBeInstanceOf Rejected::class
    }

    @Test
    fun `SuspendingFencedInventoryService - 락 획득 실패 시 LockNotAcquired 반환`() = runSuspendIO {
        // RFencedLock is reentrant — must hold from a DIFFERENT thread to block coroutine acquisition.
        val lockName = "inventory:sfenced:$inventoryId"
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
        acquireLatch.await()  // wait until holder thread has the lock

        try {
            val result = suspendingService.deduct(inventoryId, 10, waitMs = 0L, leaseMs = 1000L)
            result shouldBeInstanceOf LockNotAcquired::class
        } finally {
            releaseLatch.countDown()
            holder.join(2000)
        }
    }
}

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
 * [SuspendingFencedInventoryService]의 정확성과 취소 안전성을 검증합니다.
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

        // workers(20).rounds(20): totalUnits = rounds * blockCount = 20 * 1 = 20회 시도입니다.
        // stock=100, qty=10이면 정확히 10회 성공하고 10회는 InsufficientStock이 됩니다.
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

        // beforeWork 삽입 지점: delay(500)이 lock 보유 구간 안에서 실행되므로,
        // delay(50) 뒤 취소가 deduct() 반환 후가 아니라 lock 보유 중에 도착합니다.
        val slowService = SuspendingFencedInventoryService(
            redisson = redisson,
            store = store,
            fencedResources = fencedResources,
            beforeWork = { delay(500) },
        )

        val job = launch { slowService.deduct(999L, 10) }
        delay(50)        // 500ms beforeWork 창 중에 실행되므로 lock이 보유되어 있습니다.
        job.cancel()
        job.join()

        // NonCancellable은 취소 이후에도 unlock이 완료되도록 보장합니다.
        fLock.isLocked.shouldBeFalse()
    }

    @Test
    fun `SuspendingFencedInventoryService - 구 토큰으로 차감 시도 시 Rejected 반환`() = runSuspendIO {
        // 공유 FencedResource에 매우 큰 token을 먼저 심어 둡니다.
        // 새 Redisson lock token은 1부터 시작하므로 9999보다 작아 Rejected가 됩니다.
        fencedResources.forResource(inventoryId).apply(9999L) { Unit }

        val result = suspendingService.deduct(inventoryId, 10, waitMs = 2000L, leaseMs = 5000L)

        result shouldBeInstanceOf Rejected::class
    }

    @Test
    fun `SuspendingFencedInventoryService - 락 획득 실패 시 LockNotAcquired 반환`() = runSuspendIO {
        // RFencedLock은 reentrant이므로 coroutine의 획득을 막으려면 다른 thread에서 보유해야 합니다.
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
        acquireLatch.await()  // holder thread가 lock을 보유할 때까지 기다립니다.

        try {
            val result = suspendingService.deduct(inventoryId, 10, waitMs = 0L, leaseMs = 1000L)
            result shouldBeInstanceOf LockNotAcquired::class
        } finally {
            releaseLatch.countDown()
            holder.join(2000)
        }
    }
}

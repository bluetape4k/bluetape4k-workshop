package io.bluetape4k.workshop.lock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.workshop.lock.domain.DeductionResult.InsufficientStock
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.Inventory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * Verifies that [io.bluetape4k.workshop.lock.service.LockedInventoryService]
 * prevents over-sell under concurrent load using [org.redisson.api.RLock].
 */
class DistributedLockTest : AbstractDistributedLockTest() {

    private val inventoryId = 1L

    @BeforeEach
    fun setupInventory() {
        store.register(Inventory(inventoryId, "사과", 100))
    }

    @RepeatedTest(3)
    fun `LockedInventoryService - 동시 차감 시 oversell 이 발생하지 않는다`() {
        val successCount = AtomicInteger(0)

        MultithreadingTester()
            .workers(20)
            .rounds(1)
            .add {
                val result = lockedService.deduct(inventoryId, 10, waitMs = 3000L, leaseMs = 5000L)
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
        result shouldBeInstanceOf InsufficientStock::class
        (result as InsufficientStock).requested shouldBeEqualTo 200
        (result as InsufficientStock).available shouldBeLessThan 200
    }
}

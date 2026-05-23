package io.bluetape4k.workshop.lock

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.Inventory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demonstrates that [io.bluetape4k.workshop.lock.service.UnsafeInventoryService]
 * produces a race condition (over-sell) under concurrent load.
 *
 * The test is `@RepeatedTest(3)` to improve reproducibility of the non-deterministic race.
 */
class BaselineRaceTest : AbstractDistributedLockTest() {

    private val inventoryId = 1L

    @BeforeEach
    fun setupInventory() {
        store.register(Inventory(inventoryId, "사과", 100))
    }

    @RepeatedTest(3)
    fun `UnsafeInventoryService - 동시 차감 시 oversell 이 발생한다`() {
        val successCount = AtomicInteger(0)

        MultithreadingTester()
            .workers(20)
            .rounds(1)
            .add {
                val result = unsafeService.deduct(inventoryId, 10)
                if (result is Success) successCount.incrementAndGet()
            }
            .run()

        val currentStock = store.currentStock(inventoryId)
        // Race condition: either more than 10 successes OR stock went negative
        val raceObserved = successCount.get() > 10 || currentStock < 0
        assert(raceObserved) {
            "Expected race condition (oversell) but got: successCount=${successCount.get()}, currentStock=$currentStock"
        }
    }
}

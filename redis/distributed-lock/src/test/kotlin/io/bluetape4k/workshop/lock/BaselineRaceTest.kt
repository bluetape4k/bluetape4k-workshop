package io.bluetape4k.workshop.lock

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.Inventory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import java.util.concurrent.atomic.AtomicInteger

/**
 * [io.bluetape4k.workshop.lock.service.UnsafeInventoryService]가 동시 부하에서
 * race condition(oversell)을 만든다는 점을 보여줍니다.
 *
 * 비결정적인 경쟁 재현성을 높이려고 `@RepeatedTest(3)`로 실행합니다.
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
        // race condition은 성공 건수가 10회를 넘거나 재고가 음수가 되는 형태로 관측됩니다.
        // bluetape4k-assertions의 shouldBeTrue()는 -ea 설정과 무관하게 항상 예외를 던집니다.
        val raceObserved = successCount.get() > 10 || currentStock < 0
        raceObserved.shouldBeTrue()
    }
}

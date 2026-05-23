package io.bluetape4k.workshop.lock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.Inventory
import io.bluetape4k.workshop.lock.fenced.FencedResource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies [FencedResource] and [io.bluetape4k.workshop.lock.service.FencedInventoryService] behaviour.
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
        resource.apply(3L) { "should reject" }.shouldBeNull()     // stale: 3 < 5
        resource.apply(5L) { "same ok" }.shouldNotBeNull()        // equal token allowed (re-entry)
        resource.apply(4L) { "also reject" }.shouldBeNull()       // stale: 4 < 5
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
}

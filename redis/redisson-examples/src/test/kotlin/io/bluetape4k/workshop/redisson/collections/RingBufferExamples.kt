package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.junit5.coroutines.runSuspendTest
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.future.await
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.redisson.api.RRingBuffer

/**
 * Ring buffer examples
 *
 * 참고: [Ring Buffer](https://github.com/redisson/redisson/wiki/7.-distributed-collections#721-ring-buffer)
 */
class RingBufferExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    @Test
    fun `use Ring Buffer`() = runSuspendTest {
        val buffer: RRingBuffer<Int> = redisson.getRingBuffer(randomName())

        // 버퍼 용량을 미리 설정해주어야 합니다.
        buffer.trySetCapacity(4)
        buffer.capacityAsync().await() shouldBeEqualTo 4

        buffer.addAllAsync(listOf(1, 2, 3, 4)).await().shouldBeTrue()

        buffer.remainingCapacityAsync().await() shouldBeEqualTo 0

        // buffer contains 1,2,3,4
        // 새롭게 5,6 을 추가하면 1,2 는 제거되고, 3,4,5,6이 된다
        buffer.addAllAsync(listOf(5, 6)).await().shouldBeTrue()

        // 3, 4를 가져온다
        buffer.pollAsync(2).await() shouldBeEqualTo listOf(3, 4)

        // buffer contains 5, 6
        buffer.remainingCapacityAsync().await() shouldBeEqualTo 2

        buffer.deleteAsync().await()
    }
}

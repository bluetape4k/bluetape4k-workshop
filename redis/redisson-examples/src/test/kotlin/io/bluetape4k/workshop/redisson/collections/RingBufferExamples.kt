package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.junit5.coroutines.runSuspendTest
import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.redisson.coroutines.coAwait
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
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

    companion object: KLogging()

    @Test
    fun `use Ring Buffer`() = runSuspendTest {
        val buffer: RRingBuffer<Int> = redisson.getRingBuffer(randomName())

        // 버퍼 용량을 미리 설정해주어야 합니다.
        buffer.trySetCapacity(4)
        buffer.capacityAsync().coAwait() shouldBeEqualTo 4

        buffer.addAllAsync(listOf(1, 2, 3, 4)).coAwait().shouldBeTrue()

        buffer.remainingCapacityAsync().coAwait() shouldBeEqualTo 0

        // buffer contains 1,2,3,4
        // 새롭게 5,6 을 추가하면 1,2 는 제거되고, 3,4,5,6이 된다
        buffer.addAllAsync(listOf(5, 6)).coAwait().shouldBeTrue()

        // 3, 4를 가져온다
        buffer.pollAsync(2).coAwait() shouldBeEqualTo listOf(3, 4)

        // buffer contains 5, 6
        buffer.remainingCapacityAsync().coAwait() shouldBeEqualTo 2

        buffer.deleteAsync().coAwait()
    }
}

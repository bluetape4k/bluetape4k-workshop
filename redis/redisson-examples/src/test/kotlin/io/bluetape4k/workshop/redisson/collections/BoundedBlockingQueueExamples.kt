package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.redis.redisson.coroutines.coAwait
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.redisson.api.RBoundedBlockingQueue
import java.util.concurrent.TimeUnit


/**
 * [RBoundedBlockingQueue] 예제
 *
 * 참고: [RBoundedBlockingQueue](https://github.com/redisson/redisson/wiki/7.-distributed-collections/#711-bounded-blocking-queue)
 */
class BoundedBlockingQueueExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel() {
        private const val ITEM_SIZE = 10
    }

    @Test
    fun `크기 제한이 있는 Queue 사용`() = runTest {
        val queue = redisson.getBoundedBlockingQueue<Int>(randomName())
        queue.trySetCapacity(ITEM_SIZE).shouldBeTrue()

        // 용량 제한에 도달하게 요소를 추가한다.
        repeat(ITEM_SIZE) {
            queue.offerAsync(it + 1).coAwait().shouldBeTrue()
        }

        // 용량 제한으로 요소 추가에 실패한다 
        queue.offerAsync(ITEM_SIZE + 1).coAwait().shouldBeFalse()

        val job = scope.launch {
            // 1초후 요소 1개를 가져온다.
            delay(1000)
            queue.takeAsync().coAwait() shouldBeEqualTo 1
        }
        yield()

        // 요소 [ITEM_SIZE+1]를 추가합니다. (10초간 시도)
        queue.offerAsync(ITEM_SIZE + 1, 10, TimeUnit.SECONDS).coAwait().shouldBeTrue()

        job.join()

        // 요소 [1]은 job 내부에서 가겨갔기 때문에 삭제되었습니다.
        queue.containsAsync(1).coAwait().shouldBeFalse()

        // 요소 [ITEM_SIZE+1]은 추가되었습니다.
        queue.containsAsync(ITEM_SIZE + 1).coAwait().shouldBeTrue()

        // 큐 삭제 
        queue.deleteAsync().coAwait().shouldBeTrue()
    }
}

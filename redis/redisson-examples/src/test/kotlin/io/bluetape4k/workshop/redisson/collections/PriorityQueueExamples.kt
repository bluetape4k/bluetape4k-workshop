package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.coroutines.support.suspendAwait
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.redis.redisson.RedissonCodecs
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * 우선순위 큐
 *
 * 참고:
 * - [Redisson PriorityQueue](https://github.com/redisson/redisson/wiki/7.-distributed-collections/#716-priority-queue)
 */
class PriorityQueueExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    /**
     * [key]로 정렬하는 데이터를 표현합니다.
     *
     * @property key
     * @property value
     * @constructor Create empty Item
     */
    data class Item(
        val key: String,
        val value: Int,
    ): Comparable<Item>, java.io.Serializable {
        override fun compareTo(other: Item): Int = key.compareTo(other.key)
    }

    @Test
    fun `우선순위 큐 사용 예`() = runSuspendIO {
        val queueName = randomName()
        val queue = redisson.getPriorityQueue<Item>(queueName, RedissonCodecs.LZ4Fory)

        // Item의 key를 기준으로 정렬 (a, b, c)
        queue.add(Item("b", 1))
        queue.add(Item("c", 2))
        queue.add(Item("a", 3))

        // a,b,c,x,y,z 순으로 정렬
        // NOTE: addAll 로 추가된 요소들은 기존 요소들과는 정렬되지 않는다.
        // queue.addAll(listOf(Item("z", 11), Item("y", 22), Item("x", 33)))
        listOf(Item("z", 11), Item("y", 22), Item("x", 33)).forEach { queue.add(it) }

        queue.count() shouldBeEqualTo 6

        // 첫번째 요소 조회
        queue.peekAsync().suspendAwait() shouldBeEqualTo Item("a", 3)

        // 첫번째 요소 가져오기
        queue.pollAsync().suspendAwait() shouldBeEqualTo Item("a", 3)

        // 나머지 요소들을 가져온다. b, c, x, y, z 순으로 정렬
        queue.pollAsync(5).suspendAwait() shouldBeEqualTo listOf(
            Item("b", 1),
            Item("c", 2),
            Item("x", 33),
            Item("y", 22),
            Item("z", 11)
        )

        // NOTE: queue.delete(), queue.deleteAsync() 가 제대로 동작하는지 모르겠다. 항상 False 가 반환된다.
        queue.deleteAsync().suspendAwait()
    }
}

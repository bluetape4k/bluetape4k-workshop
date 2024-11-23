package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.redisson.coroutines.coAwait
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.redisson.api.RSortedSet

/**
 * Sorted set examples
 *
 * 참고: [SortedSet](https://github.com/redisson/redisson/wiki/7.-distributed-collections/#74-sortedset)
 */
class SortedSetExamples: AbstractRedissonTest() {

    companion object: KLogging()

    private fun getSortedSet(name: String): RSortedSet<Int> {
        return redisson.getSortedSet<Int>(name).apply {
            add(2)
            add(3)
            add(1)
        }
    }

    @Test
    fun `정렬된 SET 사용 예`() = runTest {
        val zset = getSortedSet(randomName())

        // 오름차순으로 정렬됨
        zset.first() shouldBeEqualTo 1
        zset.last() shouldBeEqualTo 3

        // 1 요소를 제거
        zset.remove(1).shouldBeTrue()

        // 첫번째 요소는 2가 됨 
        zset.first() shouldBeEqualTo 2

        zset.deleteAsync().coAwait()
    }
}

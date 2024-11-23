package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.redisson.coroutines.coAwait
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.redisson.api.RScoredSortedSet

/**
 * Score로 정렬하는 Sorted Set 에 대한 예제
 *
 * Redis의 ZSET에 해당한다
 *
 * 참고
 * - [Redisson SortedSet](https://redisson.org/glossary/java-sortedset.html)
 * - [SortedSortedSet](https://github.com/redisson/redisson/wiki/7.-distributed-collections/#75-scoredsortedset)
 */
class ScoredSortedSetExamples: AbstractRedissonTest() {

    companion object: KLogging()

    private suspend fun getSampleScoredSortedSet(name: String): RScoredSortedSet<String> {
        // 예제 데이터를 추가하기 위해 RBatch를 사용한다
        val batch = redisson.createBatch()

        with(batch.getScoredSortedSet<String>(name)) {
            addAsync(30.0, "C")
            addAsync(20.0, "B")
            addAsync(10.0, "A")

            val itemValues = mapOf(
                "Z" to 40.0,
                "Y" to 50.0,
                "X" to 60.0,
            )
            addAllAsync(itemValues)
        }

        // RBatch를 실행한다
        batch.executeAsync().coAwait()

        return redisson.getScoredSortedSet(name)
    }

    @Test
    fun `RScoredSortedSet 을 이용하여 Score로 정렬된 Set을 사용한다`() = runTest {
        val zset = getSampleScoredSortedSet(randomName())

        // ScoredSortedSet의 크기는 6이다
        zset.sizeAsync().coAwait() shouldBeEqualTo 6
        // ScoredSortedSet의 요소는 "A", "B", "C", "X", "Y", "Z" 이다
        zset.toSortedSet() shouldBeEqualTo setOf("A", "B", "C", "X", "Y", "Z")

        // ScoredSortedSet에 "B" 요소가 포함되어 있는지 확인한다
        zset.containsAsync("B").coAwait().shouldBeTrue()

        // ScoredSortedSet에 "B", "C", "X" 요소가 모두 포함되어 있는지 확인한다
        zset.containsAllAsync(setOf("B", "C", "X")).coAwait().shouldBeTrue()

        // ScoredSortedSet을 삭제한다
        zset.deleteAsync().coAwait()
    }

    @Test
    fun `Score 를 변경하여, 자동으로 정렬되도록 한다`() = runTest {
        val zset = getSampleScoredSortedSet(randomName())

        // "B" 요소의 Score를 15.0 증가시킨다 (20.0 -> 35.0)
        zset.addScoreAsync("B", 15.0).coAwait() shouldBeEqualTo 35.0

        // "X"의 score를 100.0 증가시키고, 올림차순 Rank를 얻는다 (Rank는 0부터 시작, X의 Rank는 3 -> 5로 변경)
        zset.addScoreAndGetRankAsync("X", 100.0).coAwait() shouldBeEqualTo 5

        zset.deleteAsync().coAwait()
    }
}

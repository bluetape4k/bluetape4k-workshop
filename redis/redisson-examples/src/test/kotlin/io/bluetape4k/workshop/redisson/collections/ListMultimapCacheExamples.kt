package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.coroutines.support.suspendAwait
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.redisson.api.BatchOptions
import org.redisson.api.RListMultimapCache
import java.util.concurrent.TimeUnit


/**
 * List based Multimap
 *
 * 참고:
 * - [Multimap](https://github.com/redisson/redisson/wiki/7.-distributed-collections#72-multimap)
 */
class ListMultimapCacheExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    private suspend fun addSampleData(mmap: RListMultimapCache<String, Int>) {
        val batch = redisson.createBatch(BatchOptions.defaults())

        with(batch.getListMultimapCache<String, Int>(mmap.name)) {
            putAllAsync("1", listOf(1, 2, 3))
            putAllAsync("2", listOf(5, 6))
            putAsync("4", 7)
        }
        batch.executeAsync().suspendAwait()
    }

    @Test
    fun `use RListMultimapCache`() = runTest {
        val mmapName = randomName()
        val mmap = redisson.getListMultimapCache<String, Int>(mmapName)
        addSampleData(mmap)

        mmap.getAllAsync("1").suspendAwait() shouldBeEqualTo listOf(1, 2, 3)

        // expire 설정
        mmap.expireKeyAsync("1", 60, TimeUnit.SECONDS).suspendAwait()

        mmap.containsEntryAsync("1", 3).suspendAwait().shouldBeTrue()
        mmap.containsKeyAsync("1").suspendAwait().shouldBeTrue()
        mmap.containsValueAsync(3).suspendAwait().shouldBeTrue()


        mmap.entries().forEach { (key, value) ->
            log.debug { "key=$key, value=$value" }
        }

        mmap.removeAsync("1", 3).suspendAwait().shouldBeTrue()
        mmap.getAllAsync("1").suspendAwait() shouldBeEqualTo listOf(1, 2)

        // put all
        mmap.putAllAsync("5", listOf(5, 6, 7, 8, 9)).suspendAwait().shouldBeTrue()

        // 기존 List를 반환하고 새로운 값을 설정
        mmap.replaceValuesAsync("2", listOf(5, 6, 7, 8, 9)).suspendAwait() shouldBeEqualTo listOf(5, 6)

        // RList 를 반환한다
        mmap.get("2").addAsync(100).suspendAwait()

        // List Value를 반환한다
        mmap.getAllAsync("2").suspendAwait() shouldBeEqualTo listOf(5, 6, 7, 8, 9, 100)

        // fast remove
        mmap.fastRemoveAsync("2").suspendAwait() shouldBeEqualTo 1
        // fast remove with not exists key
        mmap.fastRemoveAsync("9999").suspendAwait() shouldBeEqualTo 0

        mmap.deleteAsync().suspendAwait()
    }
}

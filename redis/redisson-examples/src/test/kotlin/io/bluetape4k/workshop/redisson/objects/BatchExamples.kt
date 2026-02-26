package io.bluetape4k.workshop.redisson.objects

import io.bluetape4k.coroutines.support.awaitSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.RepeatedTest
import org.redisson.api.BatchOptions

class BatchExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel() {
        private const val REPEAT_SIZE = 3
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `복수의 비동기 작업을 Batch로 수행한다`() = runSuspendIO {
        val name1 = randomName()
        val name2 = randomName()
        val name3 = randomName()
        val counterName = randomString()


        val map1 = redisson.getMap<String, String>(name1)
        val map2 = redisson.getMap<String, String>(name2)
        val map3 = redisson.getMap<String, String>(name3)

        // 3개의 다른 맵에 값을 추가하는 작업을 Batch로 수행한다.
        val batch = redisson.createBatch(BatchOptions.defaults())

        batch.getMap<String, String>(name1).fastPutAsync("1", "2")
        batch.getMap<String, String>(name2).fastPutAsync("2", "3")
        batch.getMap<String, String>(name3).fastPutAsync("2", "5")

        // counterName 값을 2번 증가시키는 작업을 Batch로 수행한다.
        val future1 = batch.getAtomicLong(counterName).incrementAndGetAsync()
        val future2 = batch.getAtomicLong(counterName).incrementAndGetAsync()

        // 모든 비동기 작업을 Batch로 수행한다.
        val results = batch.executeAsync().awaitSuspending()

        // NOTE: fastPutAsync 의 결과는 new insert 인 경우는 true, update 는 false 를 반환한다.
        results.responses.forEachIndexed { index, result ->
            log.debug { "response[$index]=$result" }
        }
        future1.awaitSuspending() shouldBeEqualTo results.responses[3]
        future2.awaitSuspending() shouldBeEqualTo results.responses[4]

        map1.getAsync("1").awaitSuspending() shouldBeEqualTo "2"
        map2.getAsync("2").awaitSuspending() shouldBeEqualTo "3"
        map3.getAsync("2").awaitSuspending() shouldBeEqualTo "5"

        redisson.getAtomicLong(counterName).get() shouldBeEqualTo 2L

        map1.deleteAsync().awaitSuspending()
        map2.deleteAsync().awaitSuspending()
        map3.deleteAsync().awaitSuspending()
        redisson.getAtomicLong(counterName).deleteAsync().awaitSuspending()
    }
}

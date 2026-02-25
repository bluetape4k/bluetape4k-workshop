package io.bluetape4k.workshop.redisson.objects

import io.bluetape4k.coroutines.support.awaitSuspending
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnJre
import org.junit.jupiter.api.condition.JRE
import kotlin.random.Random

/**
 * 대용량 데이터의 Count 를 확률 방식으로 계산하도록 한다
 *
 * 참고: [HyperLogLog](https://github.com/redisson/redisson/wiki/6.-distributed-objects/#69-hyperloglog)
 */
class HyperLogLogExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    @Test
    fun `RHyperLogLog 사용 예제`() = runSuspendIO {
        val hyperLog1 = redisson.getHyperLogLog<Int>(randomName())

        hyperLog1.addAsync(1).awaitSuspending()
        hyperLog1.addAsync(1).awaitSuspending()
        hyperLog1.addAsync(2).awaitSuspending()
        hyperLog1.addAsync(3).awaitSuspending()

        // 중복된 것은 제외하고 [1,2,3] 이다.
        hyperLog1.countAsync().awaitSuspending() shouldBeEqualTo 3

        hyperLog1.addAllAsync(listOf(10, 20, 10, 30)).awaitSuspending()
        hyperLog1.countAsync().awaitSuspending() shouldBeEqualTo 6

        val hyperLog2 = redisson.getHyperLogLog<Int>(randomName())
        hyperLog2.addAsync(3).awaitSuspending()
        hyperLog2.addAsync(4).awaitSuspending()
        hyperLog2.addAsync(5).awaitSuspending()

        val hyperLog3 = redisson.getHyperLogLog<Int>(randomName())
        hyperLog3.addAsync(3).awaitSuspending()
        hyperLog3.addAsync(4).awaitSuspending()
        hyperLog3.addAsync(5).awaitSuspending()

        // 두 Log의 요소들을 merge 한다
        hyperLog2.mergeWithAsync(hyperLog3.name).awaitSuspending()
        hyperLog2.countAsync().awaitSuspending() shouldBeEqualTo 3

        // [1,2,3,10,20,30] + [3,4,5]
        hyperLog1.countWithAsync(hyperLog2.name).awaitSuspending() shouldBeEqualTo 8

        hyperLog3.deleteAsync().awaitSuspending()
        hyperLog2.deleteAsync().awaitSuspending()
        hyperLog1.deleteAsync().awaitSuspending()
    }

    @Test
    fun `멀티 스레드 환경에서 HyperLogLog 사용하기`() {
        val hyperLog = redisson.getHyperLogLog<Int>(randomName())

        // 0 until 10 숫자만 HyperLogLog에 많이 추가하면, 중복되는 것은 제외하고 10개만 남는다
        MultithreadingTester()
            .workers(8)
            .rounds(4)
            .add {
                log.debug { "Add 100 random numbers ... " }
                val numbers = List(100) { Random.nextInt(0, 10) }
                hyperLog.addAll(numbers)
            }
            .run()

        hyperLog.count() shouldBeEqualTo 10
        hyperLog.delete()
    }

    @EnabledOnJre(JRE.JAVA_21)
    @Test
    fun `Virtual Thread 환경에서 HyperLogLog 사용하기`() {
        val hyperLog = redisson.getHyperLogLog<Int>(randomName())

        // 0 until 10 숫자만 HyperLogLog에 많이 추가하면, 중복되는 것은 제외하고 10개만 남는다
        StructuredTaskScopeTester()
            .rounds(8 * 4)
            .add {
                log.debug { "Add 100 random numbers ... " }
                val numbers = List(100) { Random.nextInt(0, 10) }
                hyperLog.addAll(numbers)
            }
            .run()

        hyperLog.count() shouldBeEqualTo 10
        hyperLog.delete()
    }
}

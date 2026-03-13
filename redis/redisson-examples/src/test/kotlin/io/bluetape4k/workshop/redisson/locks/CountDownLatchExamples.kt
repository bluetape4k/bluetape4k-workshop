package io.bluetape4k.workshop.redisson.locks

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * CountDownLatch examples
 *
 * Java용 Redis 기반 분산 CountDownLatch 객체는 CountDownLatch 객체와 유사한 구조를 가집니다.
 *
 * 사용하기 전에 trySetCount(count) 메서드를 통해 카운트로 초기화해야 합니다.
 *
 * 참고:
 * - [CountDownLatch](https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers/#88-countdownlatch)
 */
class CountDownLatchExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    @Test
    fun `분산 CountDownLatch 사용`() = runTest {
        val latchName = randomName()

        val latch = redisson.getCountDownLatch(latchName)
        latch.trySetCount(3)

        List(3) {
            launch {
                delay(Random.nextLong(100, 500))
                log.debug { "Before Latch count=${latch.count}" }
                latch.countDownAsync().await()
                log.debug { "After Latch count=${latch.count}" }
            }
        }

        // latch count 가 0이 될 때까지 대기
        latch.awaitAsync().await()
    }
}

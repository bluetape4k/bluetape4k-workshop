package io.bluetape4k.workshop.redisson.locks

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.redisson.coroutines.coAwait
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * 분산 세마포어 사용 예제
 *
 * 참고: [Semaphore](https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers/#86-semaphore)
 */
class SemaphoreExamples: AbstractRedissonTest() {

    companion object: KLogging()

    @Test
    fun `멀티 Coroutine Job 환경에서 세마포어 사용하기`() = runSuspendIO {
        val semaphoreName = randomName()
        val semaphore = redisson.getSemaphore(semaphoreName)

        // 5개 확보
        semaphore.trySetPermitsAsync(5).coAwait().shouldBeTrue()

        // 3개 획득
        semaphore.acquireAsync(3).coAwait()

        val redisson2 = newRedisson()
        val redisson3 = newRedisson()

        SuspendedJobTester()
            .numThreads(4)
            .roundsPerJob(16)
            .add {
                // 해당 Job은 semaphore 2개 반납
                val s2 = redisson2.getSemaphore(semaphoreName)
                delay(1)

                // 2개 반납 (4개 남음)
                s2.releaseAsync(2).coAwait()
                yield()
            }
            .add {
                // 해당 Job은 semaphore 2개 획득 
                val s3 = redisson3.getSemaphore(semaphoreName)
                delay(1)
                // 2개 확보
                s3.tryAcquireAsync(2, 5.seconds.toJavaDuration()).coAwait().shouldBeTrue()
                delay(1)
            }
            .run()
        redisson2.shutdown()
        redisson3.shutdown()

        semaphore.availablePermitsAsync().coAwait() shouldBeEqualTo 2

        // 4개 반납
        semaphore.releaseAsync(4).coAwait()
        semaphore.availablePermitsAsync().coAwait() shouldBeEqualTo 6

        // 여유분을 모두 반납합니다.
        semaphore.drainPermitsAsync().coAwait() shouldBeEqualTo 6
        semaphore.availablePermitsAsync().coAwait() shouldBeEqualTo 0

        semaphore.deleteAsync().coAwait()
    }

    @Test
    fun `멀티 스레딩 환경에서 세마포어 사용하기`() {
        val semaphoreName = randomName()
        val semaphore = redisson.getSemaphore(semaphoreName)

        // 5개 확보
        semaphore.trySetPermits(5).shouldBeTrue()

        // 3개 획득
        semaphore.acquire(3)

        val redisson2 = newRedisson()
        val redisson3 = newRedisson()

        MultithreadingTester()
            .numThreads(4)
            .roundsPerThread(4)
            .add {
                val s2 = redisson2.getSemaphore(semaphoreName)
                Thread.sleep(1)
                // 2개 반납 (4개 남음)
                s2.release(2)
                Thread.sleep(1)
            }
            .add {
                val s3 = redisson3.getSemaphore(semaphoreName)
                Thread.sleep(1)
                // 4개 확보
                s3.tryAcquire(2, 5.seconds.toJavaDuration()).shouldBeTrue()
                Thread.sleep(1)
            }
            .run()

        redisson2.shutdown()
        redisson3.shutdown()

        semaphore.availablePermits() shouldBeEqualTo 2

        // 4개 반납
        semaphore.release(4)
        semaphore.availablePermits() shouldBeEqualTo 6

        // 여유분을 모두 획득합니다.
        semaphore.drainPermits() shouldBeEqualTo 6
        semaphore.availablePermits() shouldBeEqualTo 0

        semaphore.delete()
    }


    @Test
    fun `Virtual thread 환경에서 세마포어 사용하기`() {
        val semaphoreName = randomName()
        val semaphore = redisson.getSemaphore(semaphoreName)

        // 5개 확보
        semaphore.trySetPermits(5).shouldBeTrue()

        // 3개 획득
        semaphore.acquire(3)

        val redisson2 = newRedisson()
        val redisson3 = newRedisson()

        StructuredTaskScopeTester()
            .roundsPerTask(16)
            .add {
                val s2 = redisson2.getSemaphore(semaphoreName)
                // 2개 반납 (4개 남음)
                s2.release(2)
                Thread.sleep(1)
            }
            .add {
                val s3 = redisson3.getSemaphore(semaphoreName)
                // 4개 확보
                s3.tryAcquire(2, 10.seconds.toJavaDuration()).shouldBeTrue()
                Thread.sleep(1)
            }
            .run()

        redisson2.shutdown()
        redisson3.shutdown()

        // 2개의 Job 이 서로 얻고, 반환했으므로, 기존 (5-3=2) 와 같다
        semaphore.availablePermits() shouldBeEqualTo 2

        // 4개의 permits 추가
        semaphore.release(4)
        semaphore.availablePermits() shouldBeEqualTo 6

        // 여유분을 모두 버립니다.
        semaphore.drainPermits() shouldBeEqualTo 6
        semaphore.availablePermits() shouldBeEqualTo 0

        semaphore.delete()
    }
}

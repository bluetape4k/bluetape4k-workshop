package io.bluetape4k.workshop.redisson.locks

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.redis.redisson.coroutines.coAwait
import io.bluetape4k.redis.redisson.coroutines.getLockId
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 *
 * 페어 락 (Fair Lock)
 *
 * 페어 락은 요청 순서대로 락을 제공하는 것을 보장합니다. 모든 요청은 큐에 저장되고, 5초 동안 반환을 기다립니다.
 * 예를 들어, 5개의 스레드가 어떤 이유로 죽었다면 지연 시간은 25초가 됩니다.
 *
 *
 * 참고:
 * - [FairLock](https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers#82-fair-lock)
 */
class FairLockExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    @Test
    fun `코루틴 환경에서 Fair 락 획득하기`() = runSuspendIO {
        val lock = redisson.getFairLock(randomName())
        val lockCounter = atomic(0)

        SuspendedJobTester()
            .numThreads(16)
            .roundsPerJob(16 * 2)
            .add {
                // NOTE: Coroutine에서 Lock 소유자를 구분하기 위해 lockId 를 발급받습니다.
                val lockId = redisson.getLockId(lock.name)

                // 락 획득에 5초를 대기하고, 10초 후에 lock을 자동 해제합니다.
                val locked = lock.tryLockAsync(5, 10, TimeUnit.SECONDS, lockId).coAwait()

                if (locked) {
                    lockCounter.incrementAndGet()
                    delay(10)
                    // Coroutine 환경에서는 unlock 시에도 lock 소유자를 지정해줘야 합니다.
                    lock.unlockAsync(lockId).coAwait()
                }
            }
            .run()

        lockCounter.value shouldBeEqualTo 16 * 2
    }

    @Test
    fun `멀티 스레딩 환경에서 Fair 락 획득하기`() {
        val lock = redisson.getFairLock(randomName())
        val lockCounter = atomic(0)

        MultithreadingTester()
            .numThreads(16)
            .roundsPerThread(2)
            .add {
                // 락 획득에 5초를 대기하고, 10초 후에 lock을 자동 해제합니다.
                val locked = lock.tryLock(5, 10, TimeUnit.SECONDS)

                if (locked) {
                    lockCounter.incrementAndGet()
                    Thread.sleep(10)
                    lock.unlock()
                }
            }
            .run()

        lockCounter.value shouldBeEqualTo 16 * 2
    }

    @Test
    fun `Virtual Threads 환경에서 Fair 락 획득하기`() {
        val lock = redisson.getFairLock(randomName())
        val lockCounter = atomic(0)

        StructuredTaskScopeTester()
            .roundsPerTask(16 * 2)
            .add {
                // 락 획득에 5초를 대기하고, 10초 후에 lock을 자동 해제합니다.
                val locked = lock.tryLock(5, 10, TimeUnit.SECONDS)

                if (locked) {
                    lockCounter.incrementAndGet()
                    Thread.sleep(10)
                    lock.unlock()
                }
            }
            .run()

        lockCounter.value shouldBeEqualTo 16 * 2
    }
}

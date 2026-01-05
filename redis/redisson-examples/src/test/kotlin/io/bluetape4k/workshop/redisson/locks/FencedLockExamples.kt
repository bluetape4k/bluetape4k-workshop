package io.bluetape4k.workshop.redisson.locks

import io.bluetape4k.coroutines.support.suspendAwait
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.redis.redisson.coroutines.getLockId
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnJre
import org.junit.jupiter.api.condition.JRE
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * FencedLock examples
 *
 * Redis based distributed reentrant FencedLock object for Java and implements Lock interface.
 *
 * 이 유형의 잠금은 클라이언트가 긴 GC 일시 중지 또는 기타 이유로 인해 잠금을 획득한 후
 * 더 이상 잠금을 소유하지 않은 것을 감지할 수 없는 경우를 방지하기 위해 펜싱 토큰을 유지합니다.
 *
 * 이 문제를 해결하려면 잠금 메서드 또는 getToken() 메서드를 통해 토큰을 반환합니다.
 * 토큰은 이 잠금이 보호하는 서비스에서 이전 토큰보다 크거나 같은지 확인하고 조건이 거짓이면 작업을 거부해야 합니다.
 *
 * 잠금을 획득한 Redisson 인스턴스가 충돌하면 해당 잠금은 획득한 상태에서 영원히 멈출 수 있습니다.
 * 이를 방지하기 위해 Redisson은 잠금 감시를 유지하여 잠금 보유자 Redisson 인스턴스가 살아있는 동안 잠금 만료를 연장합니다.
 * 기본적으로 잠금 감시 시간 제한은 30초이며, Config.lockWatchdogTimeout 설정을 통해 변경할 수 있습니다.
 *
 * 참고:
 * - [FencedLock](https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers/#810-fenced-lock)
 */
class FencedLockExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel() {
        const val REPEAT_SIZE = 3
    }

    @Test
    fun `use FencedLock`() {
        val lockName = randomName()
        val lock = redisson.getFencedLock(lockName)

        // 전통적인 lock 메서드를 사용하여 잠금을 획득합니다.
        // var token = lock.lockAndGetToken()

        // 아니면, 10초 후에 자동적으로 락 해제하도록 할 수 있습니다.
        // token = lock.lockAndGetToken(10, TimeUnit.SECONDS)

        // 아니면, 락 획득 대기 시간을 100초 주고, 락을 획득하고, 자동 락 해제 시간을 10초를 지정하는 방식
        val token = lock.tryLockAndGetToken(100, 10, TimeUnit.SECONDS)

        if (token != null) {
            try {
                // check if token >= old token
                log.debug { "Locked with token: $token" }
            } finally {
                lock.unlock()
            }
        }
    }

    @Test
    fun `FencedLock 에서 반환하는 토큰으로 Lock 유무를 판단한다`() {
        val lockName = randomName()
        val lock = redisson.getFencedLock(lockName)

        // 토큰을 반환하여 Lock 유무를 판단한다.
        val token1 = lock.lockAndGetToken()
        val token2 = lock.lockAndGetToken()

        log.debug { "token1=$token1, token2=$token2" }
        token2 shouldBeGreaterThan token1

        lock.unlock()

        val token3 = lock.lockAndGetToken()
        log.debug { "token3=$token3" }
        token3 shouldBeGreaterThan token2
        lock.unlock()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `멀티 스레드 환경에서 FencedLock 획득 및 해제`() {
        val lock = redisson.getFencedLock(randomName())
        val lockCounter = atomic(0)

        MultithreadingTester()
            .numThreads(8)
            .roundsPerThread(2)
            .add {
                // 락 획득 시도
                val token = lock.tryLockAndGetTokenAsync(5, 10, TimeUnit.SECONDS).get() ?: 0

                if (token > 0) {
                    log.debug { "Lock 획득. token=$token" }
                    lockCounter.incrementAndGet()
                    // 더미 작업
                    Thread.sleep(Random.nextLong(50))

                    // 락 해제
                    lock.unlock()
                }
            }
            .run()

        lockCounter.value shouldBeEqualTo 8 * 2
    }

    @EnabledOnJre(JRE.JAVA_21)
    @RepeatedTest(REPEAT_SIZE)
    fun `Virtual Thread 환경에서 FencedLock 획득 및 해제`() {
        val lock = redisson.getFencedLock(randomName())
        val lockCounter = atomic(0)

        StructuredTaskScopeTester()
            .roundsPerTask(16)
            .add {
                // 락 획득 시도 및 토큰 반환
                val token = lock.tryLockAndGetTokenAsync(5, 10, TimeUnit.SECONDS).get() ?: 0

                if (token > 0) {
                    log.debug { "Lock 획득. token=$token" }
                    lockCounter.incrementAndGet()
                    // 더미 작업 표현
                    Thread.sleep(Random.nextLong(50))

                    // lock 해제
                    lock.unlock()
                    log.debug { "Lock 해제." }
                    Thread.sleep(1)
                }
            }
            .run()

        lockCounter.value shouldBeEqualTo 16
    }

    @RepeatedTest(LockExamples.REPEAT_SIZE)
    fun `코루틴 환경에서 FencedLock 획득 및 해제`() = runSuspendIO {
        val lock = redisson.getFencedLock(randomName())
        val lockCounter = atomic(0)

        SuspendedJobTester()
            .numThreads(8)
            .roundsPerJob(16)
            .add {
                val mlockId = redisson.getLockId("ferncedLock")
                val locked = lock.tryLockAsync(5, 10, TimeUnit.SECONDS, mlockId).suspendAwait()
                if (locked) {
                    val token = lock.tokenAsync.suspendAwait()
                    if (token > 0) {
                        log.debug { "Lock 획득. locked=$token" }
                        lockCounter.incrementAndGet()
                        // 더미 작업 표현
                        delay(Random.nextLong(50))

                        // lock 해제
                        lock.unlockAsync(mlockId).suspendAwait()
                        log.debug { "Lock 해제." }
                        delay(1)
                    }
                }
            }
            .run()

        lockCounter.value shouldBeEqualTo 16
    }
}

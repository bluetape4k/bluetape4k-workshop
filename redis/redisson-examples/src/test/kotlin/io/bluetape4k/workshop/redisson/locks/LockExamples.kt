package io.bluetape4k.workshop.redisson.locks

import io.bluetape4k.coroutines.support.awaitSuspending
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.redis.redisson.coroutines.getLockId
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnJre
import org.junit.jupiter.api.condition.JRE
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 분산 락 예제
 *
 * 참고:
 * - [Redisson Lock](https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers/#81-lock)
 */
class LockExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel() {
        const val REPEAT_SIZE = 5
    }

    @Test
    fun `분산 락 사용`() = runSuspendIO {
        val lockName = randomName()

        val lock1 = redisson.getLock(lockName)
        val lockId1 = redisson.getLockId(lockName)

        log.debug { "lock1 lock in 60 seconds." }
        log.debug { "lock1 lock: current coroutineId=$lockId1, threadId=${Thread.currentThread().threadId()}" }
        lock1.lockAsync(60, TimeUnit.SECONDS, lockId1).awaitSuspending()
        lock1.isLockedAsync.awaitSuspending().shouldBeTrue()

        // 다른 Coroutine context 에서 Lock 잡고, 풀기
        val job = scope.launch(exceptionHandler) {
            log.debug { "Lock2 lock" }
            val lock2 = redisson.getLock(lockName)
            val lockId2 = redisson.getLockId(lockName)
            // 이미 lock이 잡혀 있다.
            lock2.isLockedAsync.awaitSuspending().shouldBeTrue()
            // lock1 과 다른 currentCoroutineId 를 가지므로 실패한다.
            lock2.tryLockAsync(lockId2).awaitSuspending().shouldBeFalse()
            lock2.isLockedAsync.awaitSuspending().shouldBeTrue()

            delay(100)

            // lock1 에서 이미 lock 이 걸렸고, lock2는 소유권이 없으므로 lock2로는 unlock 할 수 없다
            log.debug { "Lock2 unlock: current coroutineId=$lockId2, threadId=${Thread.currentThread().threadId()}" }
            runCatching {
                lock2.unlockAsync().awaitSuspending()
            }
            lock2.isLockedAsync.awaitSuspending().shouldBeTrue()
        }
        delay(1000)
        job.join()
        delay(10)

        log.debug { "lock1.isLocked=${lock1.isLocked}" }
        lock1.unlockAsync(lockId1).awaitSuspending()
        lock1.isLockedAsync.awaitSuspending().shouldBeFalse()
    }

    @Test
    fun `tryLock with expiration`() = runSuspendIO {
        val lockName = randomName()

        val lock = redisson.getLock(lockName)

        // Coroutines 환경에서도 고유의 lock id를 만들기 위해 사용
        val lockId = redisson.getLockId(lockName)

        log.debug { "Main Thread에서 tryLock 시도" }
        val acquired1 = lock.tryLockAsync(1, 60, TimeUnit.SECONDS, lockId).awaitSuspending()
        acquired1.shouldBeTrue()
        lock.isLockedAsync.awaitSuspending().shouldBeTrue()

        val ttl1 = lock.remainTimeToLiveAsync().awaitSuspending()
        log.debug { "TTL1: $ttl1" }
        ttl1 shouldBeGreaterThan 0L

        val job = scope.launch(exceptionHandler) {
            log.debug { "다른 Coroutine scope에서 기존 lock에 tryLock 시도 -> 소유권 (lock id)이 다르므로 실패한다" }
            val lockId2 = redisson.getLockId(lockName)
            lock.tryLockAsync(1, 60, TimeUnit.SECONDS, lockId2).awaitSuspending().shouldBeFalse()
        }
        delay(5)
        job.join()

        val prevTtl = lock.remainTimeToLiveAsync().awaitSuspending()

        // 같은 Thread 에서 기존 lock이 걸려 있는데, 또 lock을 시도하면 TTL이 갱신되고, Lock이 걸렸다고 반환한다 (ttl3 >= prevTtl)
        lock.tryLockAsync(1, 60, TimeUnit.SECONDS, lockId).awaitSuspending().shouldBeTrue()

        val ttl3 = lock.remainTimeToLiveAsync().awaitSuspending()
        log.debug { "TTL3: $ttl3, PrevTTL: $prevTtl" }
        ttl3 shouldBeGreaterOrEqualTo prevTtl
    }


    @RepeatedTest(REPEAT_SIZE)
    fun `멀티 스레드 환경에서 락 획득 및 해제`() {
        val lock = redisson.getLock(randomName())
        val lockCounter = AtomicInteger(0)

        // NOTE: Redisson Lock 은 Thread Id 기반으로 수행됩니다. Coroutine 의 경우 Thread를 공유하므로 Lock 사용에 문제가 발생할 수 있습니다.
        MultithreadingTester()
            .workers(8)
            .rounds(2)
            .add {
                // 락 획득 시도 
                val locked = lock.tryLock(5, 10, TimeUnit.SECONDS)
                if (locked) {
                    lockCounter.incrementAndGet()
                    // 더미 작업
                    Thread.sleep(Random.nextLong(50))

                    // 락 해제
                    lock.unlock()
                }
            }
            .run()

        lockCounter.get() shouldBeEqualTo 8 * 2
    }


    @EnabledOnJre(JRE.JAVA_21)
    @RepeatedTest(REPEAT_SIZE)
    fun `Virtual Thread 환경에서 락 획득 및 해제`() {
        val lock = redisson.getLock(randomName())
        val lockCounter = AtomicInteger(0)

        // NOTE: Redisson Lock 은 Thread Id 기반으로 수행됩니다. Coroutine 의 경우 Thread를 공유하므로 Lock 사용에 문제가 발생할 수 있습니다.
        StructuredTaskScopeTester()
            .rounds(16)
            .add {
                val locked = lock.tryLock(5, 10, TimeUnit.SECONDS)
                if (locked) {
                    log.debug { "Lock 획득." }
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

        lockCounter.get() shouldBeEqualTo 16
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `Multi Job 환경에서 락 획득 및 해제`() = runTest {
        val lock = redisson.getLock(randomName())
        val lockCounter = AtomicInteger(0)

        SuspendedJobTester()
            .workers(8)
            .rounds(16)
            .add {
                // Coroutine 환경에서는 Thread Id 기반이 아닌 Lock Id 기반으로 수행됩니다.
                val lockId = redisson.getLockId(lock.name)
                val locked = lock.tryLockAsync(5, 10, TimeUnit.SECONDS, lockId).awaitSuspending()
                if (locked) {
                    log.debug { "Lock 획득." }
                    lockCounter.incrementAndGet()
                    // 더미 작업 표현
                    delay(Random.nextLong(50))

                    // lock 해제
                    lock.unlockAsync(lockId).awaitSuspending()
                    log.debug { "Lock 해제." }
                    delay(1)
                }
            }
            .run()

        lockCounter.get() shouldBeEqualTo 16
    }
}

package io.bluetape4k.workshop.redisson.locks

import io.bluetape4k.codec.Base58
import io.bluetape4k.coroutines.support.suspendAwait
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.redis.redisson.coroutines.getLockId
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.redisson.RedissonMultiLock
import org.redisson.api.RLock
import java.util.concurrent.TimeUnit

/**
 * [RedissonMultiLock]은 복수의 [RLock] 객체를 한번에 Lock/Unlock 을 수행할 수 있는 클래스입니다.
 *
 * 참고: [Multi Lock](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers#83-multilock)
 */
class MultiLockExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    /**
     * 복수의 [RLock] 객체를 한번에 Lock/Unlock 을 수행할 수 있는 [RedissonMultiLock] 에 대한 사용 예입니다.
     */
    @Test
    fun `MultiLock을 이용하여 락 사용하기`() = runSuspendIO {
        log.debug { "lock 3개를 생성합니다." }
        val lock1 = redisson.getLock(randomName())
        val lock2 = redisson.getLock(randomName())
        val lock3 = redisson.getLock(randomName())

        // 새로운 작업이 lock을 걸고 시작했지만, cancel 되면 unlock을 하도록 한다.
        val job = launch(exceptionHandler) {
            val mlock = RedissonMultiLock(lock1, lock2, lock3)
            val mlockId = redisson.getLockId("mlock1")
            try {
                log.debug {
                    "새로운 Thread예서 MultiLock을 잡습니다. threadId=${
                        Thread.currentThread().threadId()
                    }, lockId=$mlockId"
                }

                mlock.tryLockAsync(1, 60, TimeUnit.SECONDS, mlockId).suspendAwait().shouldBeTrue()
                log.debug { "새로운 Thread예서 MultiLock을 잡는데 성공했습니다." }
                assertIsLockedAsync(lock1, lock2, lock3)
            } finally {
                withContext(NonCancellable) {
                    // cancel 되어도 unlock 을 해줘야한다
                    log.debug {
                        "MultiLock을 unlock 합니다. threadId=${
                            Thread.currentThread().threadId()
                        }, lockId=$mlockId"
                    }
                    // NonCancellable context 하에 있기 때문에 currentCoroutineId 가 lock 걸 때와 달리잔다. 그래서 currCoroutineId 를 사용한다
                    mlock.unlockAsync(mlockId).suspendAwait()
                }
            }
        }
        delay(1000)
        job.cancel()

        val mlock2 = RedissonMultiLock(lock1, lock2, lock3)
        val mlockId2 = redisson.getLockId("mlock2")

        log.debug { "Main Thread예서 MultiLock을 잡습니다." }
        mlock2.lockAsync(mlockId2).suspendAwait()

        delay(10)
        assertIsLockedAsync(lock1, lock2, lock3)
        delay(10)

        mlock2.unlockAsync(mlockId2).suspendAwait()
    }

    private suspend fun assertIsLockedAsync(vararg locks: RLock) {
        log.debug { "모든 Lock이 lock이 잡혀있는지 검사합니다..." }
        locks.asFlow().flatMapMerge { flow { emit(it.isLockedAsync().suspendAwait()) } }.toList().all { it }
            .shouldBeTrue()
    }

    @Test
    fun `tryLock Asynchronously with RedissionMultiLock`() = runSuspendIO {
        val lock1 = redisson.getLock(randomName())
        val lock2 = redisson.getLock(randomName())
        val lock3 = redisson.getLock(randomName())
        val lock4 = redisson.getLock(randomName())

        val mlock = RedissonMultiLock(lock1, lock2, lock3)
        val mlockId = redisson.getLockId("mlock")

        log.debug { "Main Thread에서 MultiRock에 대해서 lock을 잡습니다." }
        mlock.tryLockAsync(1, 60, TimeUnit.SECONDS, mlockId).suspendAwait().shouldBeTrue()
        assertIsLockedAsync(lock1, lock2, lock3)

        val job = launch(exceptionHandler) {
            val mlock2 = RedissonMultiLock(lock1, lock2, lock4)
            val mlockId2 = redisson.getLockId("mlock")

            // 이미 Lock이 잡혀있다
            assertIsLockedAsync(lock1, lock2, lock3)

            log.debug { "다른 Thread 에서 새로운 MultiRock에 대해서 lock을 잡으려고 하면 실패한다." }
            mlock2.tryLockAsync(1, 60, TimeUnit.SECONDS, mlockId2).suspendAwait().shouldBeFalse()

            // 이미 Lock이 잡혀있다
            assertIsLockedAsync(lock1, lock2, lock3)

            // mlock2에 속한 lock4 는 lock 이 걸리지 않았다
            lock4.isLockedAsync.suspendAwait().shouldBeFalse()

            // lock 획득에 실패했으므로, unlock을 할 수 없다.
            // mlock2.unlockAsync(mlockId2).suspendAwait()
        }
        delay(10)
        job.join()

        // 같은 Thread 에서 기존 lock이 걸려 있는데, 또 lock을 걸면 TTL이 갱신된다
        mlock.tryLockAsync(1, 60, TimeUnit.SECONDS, mlockId).suspendAwait().shouldBeTrue()

        delay(10)
        mlock.unlockAsync(mlockId).suspendAwait()
    }

    @Test
    fun `tryLock with RedissionMultiLock in multi threading`() {
        val lock1 = redisson.getLock(randomName())
        val lock2 = redisson.getLock(randomName())
        val lock3 = redisson.getLock(randomName())
        val lock4 = redisson.getLock(randomName())

        val mlock = RedissonMultiLock(lock1, lock2, lock3)

        log.debug { "Main Thread에서 MultiRock에 대해서 lock을 잡습니다." }
        mlock.tryLock(1, 60, TimeUnit.SECONDS).shouldBeTrue()
        assertIsLocked(lock1, lock2, lock3)

        MultithreadingTester()
            .numThreads(16)
            .roundsPerThread(2)
            .add {
                val mlock2 = RedissonMultiLock(lock1, lock2, lock4)

                // 이미 Lock이 잡혀있다
                assertIsLocked(lock1, lock2, lock3)

                log.debug { "다른 Thread 에서 새로운 MultiRock에 대해서 lock을 잡으려고 하면 실패한다." }
                mlock2.tryLock(1, 60, TimeUnit.SECONDS).shouldBeFalse()

                // 이미 Lock이 잡혀있다
                assertIsLocked(lock1, lock2, lock3)

                // mlock2에 속한 lock4 는 lock 이 걸리지 않았다
                lock4.isLocked.shouldBeFalse()
            }
            .run()

        // 같은 Thread 에서 기존 lock이 걸려 있는데, 또 lock을 걸면 TTL이 갱신된다
        mlock.tryLock(1, 60, TimeUnit.SECONDS).shouldBeTrue()

        Thread.sleep(10)
        mlock.unlock()
    }

    @Test
    fun `tryLock with RedissionMultiLock in virtual threads`() {
        val lock1 = redisson.getLock(randomName())
        val lock2 = redisson.getLock(randomName())
        val lock3 = redisson.getLock(randomName())
        val lock4 = redisson.getLock(randomName())

        val mlock = RedissonMultiLock(lock1, lock2, lock3)

        log.debug { "Main Thread에서 MultiRock에 대해서 lock을 잡습니다." }
        mlock.tryLock(1, 60, TimeUnit.SECONDS).shouldBeTrue()
        assertIsLocked(lock1, lock2, lock3)

        StructuredTaskScopeTester()
            .roundsPerTask(16 * 2)
            .add {
                val mlock2 = RedissonMultiLock(lock1, lock2, lock4)

                // 이미 Lock이 잡혀있다
                assertIsLocked(lock1, lock2, lock3)

                log.debug { "다른 Thread 에서 새로운 MultiRock에 대해서 lock을 잡으려고 하면 실패한다." }
                mlock2.tryLock(1, 60, TimeUnit.SECONDS).shouldBeFalse()

                // 이미 Lock이 잡혀있다
                assertIsLocked(lock1, lock2, lock3)

                // mlock2에 속한 lock4 는 lock 이 걸리지 않았다
                lock4.isLocked.shouldBeFalse()
            }
            .run()

        // 같은 Thread 에서 기존 lock이 걸려 있는데, 또 lock을 걸면 TTL이 갱신된다
        mlock.tryLock(1, 60, TimeUnit.SECONDS).shouldBeTrue()

        Thread.sleep(10)
        mlock.unlock()
    }

    @Test
    fun `tryLock with RedissionMultiLock in Coroutines`() = runSuspendIO {
        val lock1 = redisson.getLock(randomName())
        val lock2 = redisson.getLock(randomName())
        val lock3 = redisson.getLock(randomName())
        val lock4 = redisson.getLock(randomName())

        val mlock = RedissonMultiLock(lock1, lock2, lock3)

        log.debug { "Main Thread에서 MultiRock에 대해서 lock을 잡습니다." }
        val mlockId = redisson.getLockId("mlock")
        mlock.tryLockAsync(1, 60, TimeUnit.SECONDS, mlockId).suspendAwait().shouldBeTrue()
        assertIsLocked(lock1, lock2, lock3)

        SuspendedJobTester()
            .numThreads(16)
            .roundsPerJob(16 * 2)
            .add {
                val mlock2 = RedissonMultiLock(lock1, lock2, lock4)
                val mlockId2 = redisson.getLockId("mlock2-" + Base58.randomString(6))

                // 이미 Lock이 잡혀있다
                assertIsLockedAsync(lock1, lock2, lock3)

                log.debug { "다른 Coroutine에서 새로운 MultiRock에 대해서 lock을 잡으려고 하면 실패한다." }
                mlock2.tryLockAsync(1, 60, TimeUnit.SECONDS, mlockId2).suspendAwait().shouldBeFalse()

                // 이미 Lock이 잡혀있다
                assertIsLocked(lock1, lock2, lock3)

                // mlock2에 속한 lock4 는 lock 이 걸리지 않았다
                lock4.isLocked.shouldBeFalse()
            }
            .run()

        // 같은 Coroutine 환경에서 기존 lock이 걸려 있는데, 또 lock을 걸면 TTL만 갱신된다
        mlock.tryLockAsync(1, 60, TimeUnit.SECONDS, mlockId).suspendAwait().shouldBeTrue()

        delay(10)
        mlock.unlockAsync(mlockId).suspendAwait()
    }

    private fun assertIsLocked(vararg locks: RLock) {
        log.debug { "모든 Lock이 lock이 잡혀있는지 검사합니다..." }
        locks.all { it.isLocked }.shouldBeTrue()
    }
}

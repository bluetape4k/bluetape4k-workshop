package io.bluetape4k.workshop.redisson.objects

import io.bluetape4k.coroutines.support.suspendAwait
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.RepeatedTest

class AtomicLongExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel() {
        private const val REPEAT_SIZE = 5
        private const val TEST_COUNT = 1_000
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `AtomicLong 을 Coroutines 환경에서 사용하기`() = runSuspendIO {
        val counter = redisson.getAtomicLong(randomName())

        // TEST_COUNT 만큼의 코루틴을 생성하여 incrementAndGetAsync()를 호출합니다.
        val jobs = List(TEST_COUNT) {
            scope.launch {
                counter.incrementAndGetAsync().suspendAwait()
            }
        }
        jobs.joinAll()

        // counter 값이 TEST_COUNT 와 같아야 합니다. (비동기로 다중의 코루틴이 동시에 호출되었지만, Atomic하기 때문에 값이 정확해야 합니다.)
        counter.async.suspendAwait() shouldBeEqualTo TEST_COUNT.toLong()
        counter.deleteAsync().suspendAwait().shouldBeTrue()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `AtomicLog operatiions`() = runSuspendIO {
        val counter = redisson.getAtomicLong(randomName())

        counter.setAsync(0).suspendAwait()
        counter.addAndGetAsync(10L).suspendAwait() shouldBeEqualTo 10L

        counter.compareAndSetAsync(-1L, 42L).suspendAwait().shouldBeFalse()
        counter.compareAndSetAsync(10L, 42L).suspendAwait().shouldBeTrue()

        counter.decrementAndGetAsync().suspendAwait() shouldBeEqualTo 41L
        counter.incrementAndGetAsync().suspendAwait() shouldBeEqualTo 42L

        counter.getAndAddAsync(3L).suspendAwait() shouldBeEqualTo 42L

        counter.getAndDecrementAsync().suspendAwait() shouldBeEqualTo 45L
        counter.getAndIncrementAsync().suspendAwait() shouldBeEqualTo 44L

        counter.deleteAsync().suspendAwait().shouldBeTrue()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `AtomicLong in 코루틴`() = runSuspendIO {
        val counter = redisson.getAtomicLong(randomName())

        // Multi Job 환경에서 AtomicLong이 안정적으로 동작하는지 확인합니다.
        SuspendedJobTester()
            .numThreads(8)
            .roundsPerJob(8 * 32)
            .add {
                counter.incrementAndGetAsync().suspendAwait()
                delay(10)
            }
            .run()

        counter.async.suspendAwait() shouldBeEqualTo 8 * 32L
        counter.deleteAsync().suspendAwait().shouldBeTrue()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `AtomicLong in Multi threading`() {
        val counter = redisson.getAtomicLong(randomName())

        // Multi threading 환경에서 AtomicLong이 안정적으로 동작하는지 확인합니다.
        MultithreadingTester()
            .numThreads(32)
            .roundsPerThread(8)
            .add {
                counter.incrementAndGet()
                Thread.sleep(10)
            }
            .run()

        counter.get() shouldBeEqualTo 32 * 8L
        counter.delete().shouldBeTrue()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `AtomicLong in Virtual Threads`() {
        val counter = redisson.getAtomicLong(randomName())

        // Multi threading 환경에서 AtomicLong이 안정적으로 동작하는지 확인합니다.
        StructuredTaskScopeTester()
            .roundsPerTask(32 * 8)
            .add {
                counter.incrementAndGet()
                Thread.sleep(10)
            }
            .run()

        counter.get() shouldBeEqualTo 32 * 8L
        counter.delete().shouldBeTrue()
    }
}

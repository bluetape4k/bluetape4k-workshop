package io.bluetape4k.okio

import io.bluetape4k.junit5.concurrency.TestingExecutors
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Timeout as JUnitTimeout

@JUnitTimeout(5, unit = TimeUnit.SECONDS)
class TimeoutTest {

    companion object: KLogging() {
        val smallerTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(500L)
        val biggerTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(1500L)

        val smallerDeadLineNanos = TimeUnit.MILLISECONDS.toNanos(500L)
        val biggerDeadLineNanos = TimeUnit.MILLISECONDS.toNanos(1500L)
    }

    private lateinit var executor: ExecutorService

    @BeforeEach
    fun beforeEach() {
        this.executor = TestingExecutors.newFixedThreadPool(1)
    }

    @AfterEach
    fun afterEach() {
        executor.shutdown()
    }

    @Test
    fun `intersect with returns a value`() {
        val timeoutA = okio.Timeout()
        val timeoutB = okio.Timeout()

        val result = timeoutA.intersectWith(timeoutB) { "hello" }
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `intersect with prefers smaller timeout`() {
        val timeoutA = okio.Timeout()
        timeoutA.timeout(smallerTimeoutNanos, TimeUnit.NANOSECONDS)

        val timeoutB = okio.Timeout()
        timeoutB.timeout(biggerTimeoutNanos, TimeUnit.NANOSECONDS)

        // timeoutA 는 smallerTimeoutNanos 를 유지하고 timeoutB 는 biggerTimeoutNanos 를 유지한다.
        timeoutA.intersectWith(timeoutB) {
            timeoutA.timeoutNanos() shouldBeEqualTo smallerTimeoutNanos
            timeoutB.timeoutNanos() shouldBeEqualTo biggerTimeoutNanos
        }

        // timeoutB 가 timeoutA 와 같아진다.
        timeoutB.intersectWith(timeoutA) {
            timeoutA.timeoutNanos() shouldBeEqualTo smallerTimeoutNanos
            timeoutB.timeoutNanos() shouldBeEqualTo smallerTimeoutNanos
        }

        timeoutA.timeoutNanos() shouldBeEqualTo smallerTimeoutNanos
        timeoutB.timeoutNanos() shouldBeEqualTo biggerTimeoutNanos
    }
}

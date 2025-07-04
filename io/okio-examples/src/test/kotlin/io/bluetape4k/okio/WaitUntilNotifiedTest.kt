package io.bluetape4k.okio

import io.bluetape4k.junit5.concurrency.TestingExecutors
import io.bluetape4k.junit5.system.assumeNotWindows
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.notify
import okio.Timeout
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.InterruptedIOException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.DefaultAsserter.fail

class WaitUntilNotifiedTest: AbstractOkioTest() {

    companion object: KLogging()

    private lateinit var executor: ScheduledExecutorService

    private fun factories() = TimeoutFactory.factories
    private fun timeouts() = factories().map { it.newTimeout() }

    @BeforeEach
    fun beforeEach() {
        this.executor = TestingExecutors.newScheduledExecutorService(1)
    }

    @AfterEach
    fun afterEach() {
        executor.shutdown()
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `notified with timeout`(factory: TimeoutFactory) = synchronized(this) {
        val timeout = factory.newTimeout()
        timeout.timeout(5000, TimeUnit.MILLISECONDS)

        val start = now()
        executor.schedule(
            {
                synchronized(this) {
                    this.notify()
                }
            },
            1000,
            TimeUnit.MILLISECONDS
        )

        timeout.waitUntilNotified(this)
        assertElapsed(1000.0, start)
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `wait until notified`(factory: TimeoutFactory) = synchronized(this) {
        assumeNotWindows()

        val timeout = factory.newTimeout()
        timeout.timeout(1000, TimeUnit.MILLISECONDS)   // 1초 후에 InterruptedIOException이 발생해야 한다.

        val start = now()
        try {
            // 1초 후에는 InterruptedIOException이 발생해야 한다.
            timeout.waitUntilNotified(this)
            fail("Should not reach here")
        } catch (expected: InterruptedIOException) {
            // 정상적으로 InterruptedIOException이 발생했음을 확인
            expected.message shouldBeEqualTo "timeout"
        }

        assertElapsed(1000.0, start)
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `deadline only`(factory: TimeoutFactory) = synchronized(this) {
        assumeNotWindows()

        val timeout = factory.newTimeout()
        timeout.deadline(1000, TimeUnit.MILLISECONDS)   // 1초 후에 InterruptedIOException이 발생해야 한다.

        val start = now()
        try {
            // 1초 후에는 InterruptedIOException이 발생해야 한다.
            timeout.waitUntilNotified(this)
            fail("Should not reach here")
        } catch (expected: InterruptedIOException) {
            // 정상적으로 InterruptedIOException이 발생했음을 확인
            expected.message shouldBeEqualTo "timeout"
        }

        assertElapsed(1000.0, start)
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `deadline before timeout`(factory: TimeoutFactory) = synchronized(this) {
        assumeNotWindows()

        val timeout = factory.newTimeout()
        timeout.timeout(5000, TimeUnit.MILLISECONDS)   // 5초 후에 InterruptedIOException이 발생해야 한다.
        timeout.deadline(1000, TimeUnit.MILLISECONDS)   // 1초 후에 InterruptedIOException이 발생해야 한다.

        val start = now()
        try {
            // 1초 후에는 InterruptedIOException이 발생해야 한다.
            timeout.waitUntilNotified(this)
            fail("Should not reach here")
        } catch (expected: InterruptedIOException) {
            // 정상적으로 InterruptedIOException이 발생했음을 확인
            expected.message shouldBeEqualTo "timeout"
        }

        assertElapsed(1000.0, start)
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `deadline already reached`(factory: TimeoutFactory) = synchronized(this) {
        assumeNotWindows()

        val timeout = factory.newTimeout()
        timeout.deadlineNanoTime(System.nanoTime())   // 이미 deadline 에 도달한 상태

        val start = now()
        try {
            // 1초 후에는 InterruptedIOException이 발생해야 한다.
            timeout.waitUntilNotified(this)
            fail("Should not reach here")
        } catch (expected: InterruptedIOException) {
            // 정상적으로 InterruptedIOException이 발생했음을 확인
            expected.message shouldBeEqualTo "timeout"
        }

        assertElapsed(0.0, start)
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `thread interrupted`(factory: TimeoutFactory) = synchronized(this) {
        assumeNotWindows()

        val timeout = factory.newTimeout()
        val start = now()
        Thread.currentThread().interrupt()  // 현재 스레드를 인터럽트 상태로 설정

        try {
            // 즉시 InterruptedIOException이 발생해야 한다.
            timeout.waitUntilNotified(this)
            fail("Should not reach here")
        } catch (expected: InterruptedIOException) {
            // 정상적으로 InterruptedIOException이 발생했음을 확인
            expected.message shouldBeEqualTo "interrupted"
            Thread.interrupted().shouldBeTrue()
        }

        assertElapsed(0.0, start)
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `thread interrupted on throw if reached`(factory: TimeoutFactory) = synchronized(this) {
        assumeNotWindows()

        val timeout = factory.newTimeout()
        Thread.currentThread().interrupt()  // 현재 스레드를 인터럽트 상태로 설정

        try {
            timeout.throwIfReached() // throwIfReached() 메서드는 인터럽트 상태를 확인하고 InterruptedIOException을 던진다.
            fail("Should not reach here")
        } catch (expected: InterruptedIOException) {
            // 정상적으로 InterruptedIOException이 발생했음을 확인
            expected.message shouldBeEqualTo "interrupted"
            Thread.interrupted().shouldBeTrue()
        }
        Unit
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `cancel before wait does nothing`(factory: TimeoutFactory) = synchronized(this) {
        assumeNotWindows()

        val timeout = factory.newTimeout()
        timeout.timeout(1000, TimeUnit.MILLISECONDS)  // 1초 후에 InterruptedIOException이 발생해야 한다.
        timeout.cancel()  // waitUntilNotified() 호출 전에 cancel()을 호출

        val start = now()
        try {
            timeout.waitUntilNotified(this)  // cancel() 후에도 InterruptedIOException이 발생한다
            fail("Should not reach here")
        } catch (expected: InterruptedIOException) {
            expected.message shouldBeEqualTo "timeout"
        }

        assertElapsed(1000.0, start)
    }

    @ParameterizedTest
    @MethodSource("factories")
    @Synchronized
    fun `canceled timeout does not throw when not notified on time`(factory: TimeoutFactory) {
        val timeout = factory.newTimeout()
        timeout.timeout(1000, TimeUnit.MILLISECONDS)
        timeout.cancelLater(500)   // 취소를 수행하면 timeout 이 발생해도 예외가 발생하지 않는다 (cancel 이 먼저 수행되었기 때문)

        val start = now()
        timeout.waitUntilNotified(this)  // Returns early but doesn't throw.
        assertElapsed(1000.0, start)
    }


    @ParameterizedTest
    @MethodSource("factories")
    @Synchronized
    fun `multiple cancels are idempotent`(factory: TimeoutFactory) {
        val timeout = factory.newTimeout()
        timeout.timeout(1000, TimeUnit.MILLISECONDS)

        timeout.cancelLater(250)
        timeout.cancelLater(500)   // 취소를 수행하면 timeout 이 발생해도 예외가 발생하지 않는다 (cancel 이 먼저 수행되었기 때문)
        timeout.cancelLater(750)

        val start = now()
        timeout.waitUntilNotified(this)  // Returns early but doesn't throw.
        assertElapsed(1000.0, start)
    }

    private fun Timeout.cancelLater(delay: Long) {
        executor.schedule({ cancel() }, delay, TimeUnit.MILLISECONDS)
    }
}

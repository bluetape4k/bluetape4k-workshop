package io.bluetape4k.okio

import io.bluetape4k.junit5.concurrency.TestingExecutors
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.notify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    @Synchronized
    fun `notified with timeout`(factory: TimeoutFactory) {
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
}

package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.hours
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

class FixtureVirtualWaitStrategyTest {

    companion object: KLogging()

    @Test
    fun `mutation observed before await prevents a lost virtual wakeup`() {
        val strategy = FixtureVirtualWaitStrategy { 0L }
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val waiter = executor.submit {
                strategy.beginWaiter()
                ready.countDown()
                release.await(1.seconds).shouldBeTrue()
                strategy.await(1.hours())
                strategy.endWaiter()
            }

            ready.await(1.seconds).shouldBeTrue()
            release.countDown()
            strategy.signal()
            waiter.get(1.seconds)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(1.seconds).shouldBeTrue()
        }
    }
}

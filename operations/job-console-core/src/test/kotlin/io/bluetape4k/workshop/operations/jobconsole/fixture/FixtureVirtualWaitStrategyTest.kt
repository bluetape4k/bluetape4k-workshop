package io.bluetape4k.workshop.operations.jobconsole.fixture

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FixtureVirtualWaitStrategyTest {

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
                check(release.await(1, TimeUnit.SECONDS))
                strategy.await(Duration.ofHours(1))
                strategy.endWaiter()
            }

            check(ready.await(1, TimeUnit.SECONDS))
            strategy.signal()
            release.countDown()
            waiter.get(1, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }
}

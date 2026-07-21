package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.ApplicationContext
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class VoucherPoolLifecycleIntegrationTest {
    @Test
    fun `shutdown follows the bounded ownership order and is idempotent`() {
        val actions = RecordingLifecycleActions()
        val coordinator = VoucherPoolLifecycleCoordinator(actions)

        coordinator.shutdown()
        coordinator.shutdown()

        coordinator.events() shouldBeEqualTo VoucherPoolShutdownEvent.entries
        coordinator.reason() shouldBeEqualTo VoucherPoolShutdownReason.CLEAN
        actions.invocations shouldBeEqualTo 9
    }

    @Test
    fun `drain timeout cancels rolls back and releases claims before resources close`() {
        val actions = RecordingLifecycleActions(drainResult = false)
        val coordinator =
            VoucherPoolLifecycleCoordinator(
                actions = actions,
                deadlines =
                    VoucherPoolShutdownDeadlines(
                        transactionDrain = Duration.ofMillis(20),
                        claimRelease = Duration.ofMillis(20),
                        phase = Duration.ofMillis(100),
                        total = Duration.ofSeconds(1),
                    ),
            )

        coordinator.shutdown()

        coordinator.reason() shouldBeEqualTo VoucherPoolShutdownReason.TRANSACTION_DRAIN_TIMEOUT
        actions.cancelled.get().shouldBeTrue()
        actions.rollbackVerified.get().shouldBeTrue()
        actions.claimReleased.get().shouldBeTrue()
        actions.claimReleaseBeforeAdvisoryClose.get().shouldBeTrue()
    }

    @Test
    fun `database gate rejects new work and drains existing permits`() {
        val gate = DatabasePermitGate.default(16)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread.ofVirtual().start {
            gate.withWorkerPermit {
                entered.countDown()
                release.await()
            }
        }
        entered.await(2, TimeUnit.SECONDS).shouldBeTrue()

        gate.beginShutdown()
        gate.isAccepting().shouldBeFalse()
        gate.awaitDrained(Duration.ofMillis(20)).shouldBeFalse()
        release.countDown()
        gate.awaitDrained(Duration.ofSeconds(2)).shouldBeTrue()
        thread.join(Duration.ofSeconds(2))
    }

    @Test
    fun `runtime stops new claims and waits for an active shared worker path`() {
        val runtime = VoucherPoolRuntimeControl()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread.ofVirtual().start {
            runtime.withClaim {
                entered.countDown()
                release.await()
            }
        }
        entered.await(2, TimeUnit.SECONDS).shouldBeTrue()

        runtime.stopClaims()
        runtime.awaitClaimRelease(Duration.ofMillis(20)).shouldBeFalse()
        runtime.withClaim { error("new claim must be rejected") } shouldBeEqualTo null
        release.countDown()
        runtime.awaitClaimRelease(Duration.ofSeconds(2)).shouldBeTrue()
        thread.join(Duration.ofSeconds(2))
    }

    @Test
    fun `application lifecycle closes event stream before executor and data source`() {
        val stream = mockk<VoucherPoolStreamShutdown>(relaxed = true)
        val executor = mockk<ExecutorService>(relaxed = true)
        val dataSource = mockk<HikariDataSource>(relaxed = true)
        val beans =
            StaticListableBeanFactory(
                mapOf(
                    "voucherPoolStreamShutdown" to stream,
                    "voucherPoolExecutor" to executor,
                ),
            )
        val lifecycle =
            VoucherPoolLifecycle(
                applicationContext = mockk<ApplicationContext>(relaxed = true),
                gate = DatabasePermitGate.default(16),
                runtime = VoucherPoolRuntimeControl(),
                health = VoucherPoolHealthState(),
                streamShutdown = beans.getBeanProvider(VoucherPoolStreamShutdown::class.java),
                redis = beans.getBeanProvider(VoucherPoolRedisResources::class.java),
                executor = beans.getBeanProvider(ExecutorService::class.java),
                dataSource = dataSource,
            )

        lifecycle.start()
        lifecycle.stop()

        verifyOrder {
            stream.closeSseAndPoller()
            executor.shutdown()
            dataSource.close()
        }
    }

    private class RecordingLifecycleActions(
        private val drainResult: Boolean = true,
    ) : VoucherPoolLifecycleActions {
        var invocations = 0
        val cancelled = AtomicBoolean()
        val rollbackVerified = AtomicBoolean()
        val claimReleased = AtomicBoolean()
        val claimReleaseBeforeAdvisoryClose = AtomicBoolean()

        override fun readinessDown() = invoked()

        override fun rejectCommandsAndSse() = invoked()

        override fun stopTriggers() = invoked()

        override fun stopClaims() = invoked()

        override fun awaitTransactions(timeout: Duration): Boolean = drainResult.also { invoked() }

        override fun cancelTransactions() {
            cancelled.set(true)
        }

        override fun verifyRollback(): Boolean = true.also { rollbackVerified.set(true) }

        override fun awaitClaimRelease(timeout: Duration): Boolean = true.also { claimReleased.set(true) }

        override fun closeSseAndPoller() = invoked()

        override fun closeAdvisoryResources() {
            claimReleaseBeforeAdvisoryClose.set(!cancelled.get() || claimReleased.get())
            invoked()
        }

        override fun closeExecutor() = invoked()

        override fun closeDataSource() = invoked()

        private fun invoked() {
            invocations++
        }
    }
}

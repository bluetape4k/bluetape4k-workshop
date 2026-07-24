package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitRejected
import io.bluetape4k.workshop.commerce.voucher.config.VoucherLifecycleCoordinator
import io.bluetape4k.workshop.commerce.voucher.config.VoucherShutdownEvent
import io.bluetape4k.workshop.commerce.voucher.config.VoucherShutdownReason
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class VoucherLifecycleIntegrationTest {
    @Test
    fun `shutdown rejects new work drains database and closes resources in order`() {
        val gate = DatabasePermitGate(foregroundPermits = 1, workerPermits = 1, sseMaintenancePermits = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inFlight = Thread.ofVirtual().start {
            gate.withPermit(DatabaseLane.FOREGROUND) {
                entered.countDown()
                release.await()
            }
        }
        check(entered.await(2, TimeUnit.SECONDS))
        val coordinator =
            VoucherLifecycleCoordinator(
                gate = gate,
                stopWorker = {},
                stopSse = {},
                releaseLeader = {},
                closeRedis = {},
                closeExecutor = {},
                graceDeadline = Duration.ofSeconds(2),
            )

        val closeThread = Thread.ofPlatform().start(coordinator::shutdown)
        check(coordinator.awaitEvent(VoucherShutdownEvent.AWAIT_DB, Duration.ofSeconds(1)))
        assertFailsWith<DatabasePermitRejected> {
            gate.withPermit(DatabaseLane.FOREGROUND) { error("new work must not start") }
        }
        release.countDown()
        closeThread.join(Duration.ofSeconds(3))
        inFlight.join(Duration.ofSeconds(3))

        closeThread.isAlive shouldBeEqualTo false
        gate.inUsePermits() shouldBeEqualTo 0
        coordinator.events() shouldBeEqualTo
            listOf(
                VoucherShutdownEvent.READINESS_DOWN,
                VoucherShutdownEvent.REJECT_NEW,
                VoucherShutdownEvent.STOP_WORKER,
                VoucherShutdownEvent.AWAIT_DB,
                VoucherShutdownEvent.STOP_SSE,
                VoucherShutdownEvent.RELEASE_LEADER,
                VoucherShutdownEvent.CLOSE_REDIS,
                VoucherShutdownEvent.CLOSE_EXECUTOR,
            )
    }

    @Test
    fun `forced shutdown records only the bounded database deadline reason`() {
        val gate = DatabasePermitGate(foregroundPermits = 1, workerPermits = 1, sseMaintenancePermits = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inFlight = Thread.ofVirtual().start {
            gate.withPermit(DatabaseLane.FOREGROUND) {
                entered.countDown()
                release.await()
            }
        }
        check(entered.await(2, TimeUnit.SECONDS))
        val coordinator =
            VoucherLifecycleCoordinator(
                gate = gate,
                stopWorker = {},
                stopSse = {},
                releaseLeader = {},
                closeRedis = {},
                closeExecutor = {},
                graceDeadline = Duration.ofMillis(50),
            )

        val startedAt = System.nanoTime()
        coordinator.shutdown()
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
        coordinator.forcedReason() shouldBeEqualTo VoucherShutdownReason.DB_DRAIN_DEADLINE
        check(elapsed < Duration.ofSeconds(1))

        release.countDown()
        inFlight.join(Duration.ofSeconds(2))
    }
}

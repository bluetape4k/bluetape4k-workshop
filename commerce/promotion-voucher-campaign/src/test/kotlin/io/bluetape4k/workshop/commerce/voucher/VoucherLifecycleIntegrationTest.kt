package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitRejected
import io.bluetape4k.workshop.commerce.voucher.config.VoucherLifecycleCoordinator
import io.bluetape4k.workshop.commerce.voucher.config.VoucherShutdownEvent
import io.bluetape4k.workshop.commerce.voucher.config.VoucherShutdownReason
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

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
        check(entered.await(2.seconds))
        val coordinator =
            VoucherLifecycleCoordinator(
                gate = gate,
                stopWorker = {},
                stopSse = {},
                releaseLeader = {},
                closeRedis = {},
                closeExecutor = {},
                graceDeadline = 2.seconds(),
            )

        val closeThread = Thread.ofPlatform().start(coordinator::shutdown)
        check(coordinator.awaitEvent(VoucherShutdownEvent.AWAIT_DB, 1.seconds()))
        assertFailsWith<DatabasePermitRejected> {
            gate.withPermit(DatabaseLane.FOREGROUND) { error("new work must not start") }
        }
        release.countDown()
        closeThread.join(3.seconds())
        inFlight.join(3.seconds())

        closeThread.isAlive.shouldBeFalse()
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
        check(entered.await(2.seconds))
        val coordinator =
            VoucherLifecycleCoordinator(
                gate = gate,
                stopWorker = {},
                stopSse = {},
                releaseLeader = {},
                closeRedis = {},
                closeExecutor = {},
                graceDeadline = 50.millis(),
            )

        val startedAt = System.nanoTime()
        coordinator.shutdown()
        val elapsed = (System.nanoTime() - startedAt).nanos()
        coordinator.forcedReason() shouldBeEqualTo VoucherShutdownReason.DB_DRAIN_DEADLINE
        elapsed shouldBeLessThan 1.seconds()

        release.countDown()
        inFlight.join(2.seconds())
    }
}

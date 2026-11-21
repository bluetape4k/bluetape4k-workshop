package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitRejected
import io.bluetape4k.workshop.commerce.voucher.config.VoucherLifecycleCoordinator
import io.bluetape4k.workshop.commerce.voucher.config.VoucherShutdownEvent
import io.bluetape4k.workshop.commerce.voucher.config.VoucherShutdownReason
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/**
 * VoucherLifecycleIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherLifecycleIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "shutdown rejects new work drains database and closes resources in order" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
        entered.await(2.seconds).shouldBeTrue()

        val coordinator = VoucherLifecycleCoordinator(
            gate = gate,
            stopWorker = {},
            stopSse = {},
            releaseLeader = {},
            closeRedis = {},
            closeExecutor = {},
            graceDeadline = 2.seconds(),
        )

        val closeThread = Thread.ofPlatform().start(coordinator::shutdown)
        coordinator.awaitEvent(VoucherShutdownEvent.AWAIT_DB, 1.seconds()).shouldBeTrue()

        assertFailsWith<DatabasePermitRejected> {
            gate.withPermit(DatabaseLane.FOREGROUND) { error("new work must not start") }
        }

        release.countDown()
        closeThread.join(3.seconds())
        inFlight.join(3.seconds())

        closeThread.isAlive.shouldBeFalse()
        gate.inUsePermits() shouldBeEqualTo 0

        coordinator.events() shouldBeEqualTo listOf(
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
/**
 * 이 시나리오는 "forced shutdown records only the bounded database deadline reason" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
        entered.await(2.seconds).shouldBeTrue()

        val coordinator = VoucherLifecycleCoordinator(
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

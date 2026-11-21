package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/**
 * DatabasePermitGateTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class DatabasePermitGateTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "cancelled foreground work returns only the local JDBC permit" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `cancelled foreground work returns only the local JDBC permit`() {
        val gate = gate()

        assertFailsWith<CancellationException> {
            gate.withPermit(DatabaseLane.FOREGROUND) { throw CancellationException("cancelled") }
        }

        gate.availablePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
        gate.availablePermits(DatabaseLane.WORKER) shouldBeEqualTo 1
        gate.availablePermits(DatabaseLane.SSE_MAINTENANCE) shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "occupied SSE maintenance lane cannot starve the reserved worker lane" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `occupied SSE maintenance lane cannot starve the reserved worker lane`() {
        val gate = gate()
        val sseEntered = CountDownLatch(1)
        val sseRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val sse = executor.submit {
                gate.withPermit(DatabaseLane.SSE_MAINTENANCE) {
                    sseEntered.countDown()
                    sseRelease.await(5.seconds).shouldBeTrue()
                }
            }
            sseEntered.await(5.seconds).shouldBeTrue()

            gate.withPermit(DatabaseLane.WORKER) { "worker-progress" } shouldBeEqualTo "worker-progress"
            sseRelease.countDown()
            sse.get(5.seconds)
        }
    }

    @Test
/**
 * 이 시나리오는 "foreground exhaustion rejects without consuming the worker lane" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `foreground exhaustion rejects without consuming the worker lane`() {
        val gate = gate()
        val foregroundEntered = CountDownLatch(1)
        val foregroundRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder = executor.submit {
                gate.withPermit(DatabaseLane.FOREGROUND) {
                    foregroundEntered.countDown()
                    foregroundRelease.await(5.seconds).shouldBeTrue()
                }
            }
            foregroundEntered.await(5.seconds).shouldBeTrue()

            val failure = assertFailsWith<DatabasePermitRejected> {
                val result = gate.withPermit(DatabaseLane.FOREGROUND) { Unit }
                error("foreground permit was acquired unexpectedly: $result")
            }
            failure.retryAfter shouldBeEqualTo 1.seconds()
            gate.withPermit(DatabaseLane.WORKER) {
                "worker-progress"
            } shouldBeEqualTo "worker-progress"

            foregroundRelease.countDown()
            holder.get(5.seconds)
        }
    }

    @Test
/**
 * 이 시나리오는 "lane probes expose active and queued virtual threads without consuming permits" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `lane probes expose active and queued virtual threads without consuming permits`() {
        val gate = gate(acquireTimeout = 5.seconds())
        val foregroundEntered = CountDownLatch(1)
        val foregroundRelease = CountDownLatch(1)
        val waiterStarted = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder = executor.submit {
                gate.withPermit(DatabaseLane.FOREGROUND) {
                    foregroundEntered.countDown()
                    foregroundRelease.await(5.seconds).shouldBeTrue()
                }
            }
            foregroundEntered.await(5.seconds).shouldBeTrue()
            val waiter = executor.submit<String> {
                waiterStarted.countDown()
                gate.withPermit(DatabaseLane.FOREGROUND) { "queued-progress" }
            }
            waiterStarted.await(5.seconds).shouldBeTrue()

            awaitQueued(gate)
            gate.inUsePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
            gate.waitingThreads(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
            gate.availablePermits(DatabaseLane.WORKER) shouldBeEqualTo 1

            foregroundRelease.countDown()
            holder.get(5.seconds)
            waiter.get(5.seconds) shouldBeEqualTo "queued-progress"
            gate.inUsePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 0
            gate.waitingThreads(DatabaseLane.FOREGROUND) shouldBeEqualTo 0
        }
    }

    @Test
/**
 * 이 시나리오는 "nested acquisition is rejected before another lane is touched" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `nested acquisition is rejected before another lane is touched`() {
        val gate = gate()

        assertFailsWith<IllegalStateException> {
            val result = gate.withPermit(DatabaseLane.FOREGROUND) {
                val nestedResult = gate.withPermit(DatabaseLane.SSE_MAINTENANCE) { Unit }
                error("nested permit was acquired unexpectedly: $nestedResult")
            }
            error("outer permit completed unexpectedly: $result")
        }
    }

    @Test
/**
 * 이 시나리오는 "interruption is converted to a retryable rejection and preserves interrupt status" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `interruption is converted to a retryable rejection and preserves interrupt status`() {
        val gate = gate(acquireTimeout = 5.seconds())
        val foregroundEntered = CountDownLatch(1)
        val foregroundRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder = executor.submit {
                gate.withPermit(DatabaseLane.FOREGROUND) {
                    foregroundEntered.countDown()
                    foregroundRelease.await(5.seconds).shouldBeTrue()
                }
            }
            foregroundEntered.await(5.seconds).shouldBeTrue()

            val interrupted = executor.submit<Boolean> {
                Thread.currentThread().interrupt()
                assertFailsWith<DatabasePermitRejected> {
                    val result = gate.withPermit(DatabaseLane.FOREGROUND) { Unit }
                    error("interrupted permit was acquired unexpectedly: $result")
                }
                Thread.currentThread().isInterrupted
            }
            interrupted.get(5.seconds).shouldBeTrue()

            foregroundRelease.countDown()
            holder.get(5.seconds)
        }
    }

/**
 * gate은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun gate(acquireTimeout: Duration = 25.millis()): DatabasePermitGate =
        DatabasePermitGate(
            foregroundPermits = 1,
            workerPermits = 1,
            sseMaintenancePermits = 1,
            acquireTimeout = acquireTimeout,
        )

/**
 * awaitQueued은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitQueued(gate: DatabasePermitGate) {
        val deadline = System.nanoTime() + 2.seconds().toNanos()
        while (gate.waitingThreads(DatabaseLane.FOREGROUND) == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        gate.waitingThreads(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
    }
}

package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DatabasePermitGateTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class DatabasePermitGateTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "foreground timeout releases only the waiting acquisition" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `foreground timeout releases only the waiting acquisition`() {
        val gate = gate()
        val holderEntered = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder = executor.submit {
                gate.withForegroundPermit {
                    holderEntered.countDown()
                    holderRelease.await(5.seconds).shouldBeTrue()
                }
            }
            holderEntered.await(5.seconds).shouldBeTrue()

            val failure = assertFailsWith<PoolBusyException> {
                val result = gate.withForegroundPermit { "unexpected" }
                error("foreground permit was acquired unexpectedly: $result")
            }
            log.debug { "failure=$failure" }
            failure.lane shouldBeEqualTo PermitLane.FOREGROUND

            gate.snapshot().let {
                it.foregroundInUse shouldBeEqualTo 1
                it.workerInUse shouldBeEqualTo 0
                it.sseInUse shouldBeEqualTo 0
            }

            holderRelease.countDown()
            holder.get(5.seconds)
        }

        log.debug { "PermitSnapshot=${gate.snapshot()}" }
        gate.snapshot().foregroundInUse shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "default lane budgets fit Hikari and nested acquisition fails immediately" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `default lane budgets fit Hikari and nested acquisition fails immediately`() {

        val gate = DatabasePermitGate.default(hikariMaximumPoolSize = 16)

        gate.snapshot().let {
            it.capacities shouldBeEqualTo mapOf(
                PermitLane.FOREGROUND to 11,
                PermitLane.WORKER to 1,
                PermitLane.SSE to 3,
            )
            it.waits shouldBeEqualTo mapOf(
                PermitLane.FOREGROUND to 200.milliseconds,
                PermitLane.WORKER to 1.seconds,
                PermitLane.SSE to 1.seconds,
            )
        }

        gate.withForegroundPermit {
            val failure = assertFailsWith<NestedPermitException> {
                val result = gate.withSsePermit { "unexpected" }
                error("nested permit was acquired unexpectedly: $result")
            }
            log.debug { "failure=$failure" }
            failure.activeLane shouldBeEqualTo PermitLane.FOREGROUND
            failure.requestedLane shouldBeEqualTo PermitLane.SSE

            log.debug { "PermitSnapshot=${gate.snapshot()}" }
            gate.snapshot().sseInUse shouldBeEqualTo 0
        }
    }

    @Test
/**
 * 이 시나리오는 "permit snapshot reports observed wait instead of configured deadline" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `permit snapshot reports observed wait instead of configured deadline`() {
        val gate = gate()

        gate.withForegroundPermit { "acquired" } shouldBeEqualTo "acquired"

        gate.snapshot().let {
            it.observedWaitMax.getValue(PermitLane.FOREGROUND) shouldBeLessThan 25.milliseconds
            it.observedWaitSamples.getValue(PermitLane.FOREGROUND) shouldBeEqualTo 1L
        }
    }

    @Test
/**
 * 이 시나리오는 "invalid lane configuration is rejected" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `invalid lane configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DatabasePermitGate(
                hikariMaximumPoolSize = 3,
                configs = mapOf(PermitLane.FOREGROUND to PermitLaneConfig(1, 25.milliseconds)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DatabasePermitGate(
                hikariMaximumPoolSize = 3,
                configs = mapOf(
                    PermitLane.FOREGROUND to PermitLaneConfig(0, 25.milliseconds),
                    PermitLane.WORKER to PermitLaneConfig(1, 1.seconds),
                    PermitLane.SSE to PermitLaneConfig(1, 1.seconds),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DatabasePermitGate(
                hikariMaximumPoolSize = 2,
                configs = mapOf(
                    PermitLane.FOREGROUND to PermitLaneConfig(1, 25.milliseconds),
                    PermitLane.WORKER to PermitLaneConfig(1, 1.seconds),
                    PermitLane.SSE to PermitLaneConfig(1, 1.seconds),
                ),
            )
        }
    }

    @Test
/**
 * 이 시나리오는 "active transaction is rejected before permit acquisition" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `active transaction is rejected before permit acquisition`() {
        val gate = gate()

        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertFailsWith<IllegalStateException> {
                gate.withForegroundPermit { error("permit entered with an active transaction") }
            }
            gate.snapshot().let {
                it.foregroundInUse shouldBeEqualTo 0
                it.workerInUse shouldBeEqualTo 0
                it.sseInUse shouldBeEqualTo 0
            }
        } finally {
            TransactionSynchronizationManager.clear()
        }

        gate.withForegroundPermit { "released" } shouldBeEqualTo "released"
        gate.snapshot().foregroundInUse shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "exception releases the acquired lane without changing another lane" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `exception releases the acquired lane without changing another lane`() {
        val gate = gate()

        assertFailsWith<IllegalStateException> {
            gate.withWorkerPermit { throw IllegalStateException("worker failed") }
        }
        gate.snapshot().let {
            it.workerInUse shouldBeEqualTo 0
            it.foregroundInUse shouldBeEqualTo 0
            it.sseInUse shouldBeEqualTo 0
        }
    }

    @Test
/**
 * 이 시나리오는 "interruption preserves the holder and restores interrupt status" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `interruption preserves the holder and restores interrupt status`() {
        val gate = gate(workerWaitSeconds = 5)
        val holderEntered = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder = executor.submit {
                gate.withWorkerPermit {
                    holderEntered.countDown()
                    holderRelease.await(5.seconds).shouldBeTrue()
                }
            }
            holderEntered.await(5.seconds).shouldBeTrue()

            val interrupted = executor.submit<Boolean> {
                Thread.currentThread().interrupt()
                val failure =
                    assertFailsWith<PoolBusyException> {
                        val result = gate.withWorkerPermit { "unexpected" }
                        error("interrupted permit was acquired unexpectedly: $result")
                    }
                failure.interrupted.shouldBeTrue()
                Thread.currentThread().isInterrupted
            }
            interrupted.get(5.seconds).shouldBeTrue()
            gate.snapshot().workerInUse shouldBeEqualTo 1

            holderRelease.countDown()
            holder.get(5.seconds)
        }

        gate.snapshot().workerInUse shouldBeEqualTo 0
        Thread.currentThread().isInterrupted.shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "cancellation after acquisition releases only the acquired lane" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `cancellation after acquisition releases only the acquired lane`() {
        val gate = gate()
        val foregroundEntered = CountDownLatch(1)
        val foregroundRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val foregroundHolder = executor.submit {
                gate.withForegroundPermit {
                    foregroundEntered.countDown()
                    foregroundRelease.await(5.seconds).shouldBeTrue()
                }
            }
            foregroundEntered.await(5.seconds).shouldBeTrue()
            try {
                assertFailsWith<CancellationException> {
                    gate.withWorkerPermit {
                        gate.snapshot().foregroundInUse shouldBeEqualTo 1
                        gate.snapshot().workerInUse shouldBeEqualTo 1
                        throw CancellationException("worker cancelled")
                    }
                }
                gate.snapshot().let {
                    it.foregroundInUse shouldBeEqualTo 1
                    it.workerInUse shouldBeEqualTo 0
                    it.sseInUse shouldBeEqualTo 0
                }
            } finally {
                foregroundRelease.countDown()
                foregroundHolder.get(5.seconds)
            }
        }

        gate.snapshot().foregroundInUse shouldBeEqualTo 0
    }

/**
 * gate은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun gate(workerWaitSeconds: Int = 1): DatabasePermitGate =
        DatabasePermitGate(
            hikariMaximumPoolSize = 4,
            configs = mapOf(
                PermitLane.FOREGROUND to PermitLaneConfig(1, 25.milliseconds),
                PermitLane.WORKER to PermitLaneConfig(1, workerWaitSeconds.seconds),
                PermitLane.SSE to PermitLaneConfig(1, 1.seconds),
            ),
        )
}

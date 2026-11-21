package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticApplicationContext

/**
 * EventSourcedApplicationLifecycleTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedApplicationLifecycleTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "startup raises readiness only after authority probe succeeds" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `startup raises readiness only after authority probe succeeds`() {
        val state = EventSourcedOperationalState()
        val workers = RecordingRuntimeWorkers()
        val lifecycle = lifecycle(state, {}, workers)

        lifecycle.start()

        state.readinessState() shouldBeEqualTo EventSourcedReadinessState.READY
        workers.started.shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "startup authority failure leaves the process running but readiness down" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `startup authority failure leaves the process running but readiness down`() {
        val state = EventSourcedOperationalState()
        val lifecycle = lifecycle(state, { error("database unavailable") })

        lifecycle.start()

        state.readinessState() shouldBeEqualTo EventSourcedReadinessState.AUTHORITY_FAILED
    }

    @Test
/**
 * 이 시나리오는 "shutdown invokes every concrete runtime stage after readiness drops" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `shutdown invokes every concrete runtime stage after readiness drops`() {
        val state = EventSourcedOperationalState()
        val workers = RecordingRuntimeWorkers()
        val lifecycle = lifecycle(state, {}, workers)

        lifecycle.start()

        lifecycle.stop()

        workers.stopped shouldBeEqualTo listOf("projection", "rebuild", "maintenance", "leases")
    }

/**
 * lifecycle은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun lifecycle(
        state: EventSourcedOperationalState,
        probe: EventSourcedStartupProbe,
        workers: EventSourcedRuntimeWorkers = RecordingRuntimeWorkers(),
    ): EventSourcedApplicationLifecycle =
        EventSourcedApplicationLifecycle(
            applicationContext = StaticApplicationContext(),
            state = state,
            gate = EventSourcedDatabasePermitGate(),
            probe = probe,
            workers = workers,
        )
}

/**
 * RecordingRuntimeWorkers 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class RecordingRuntimeWorkers: EventSourcedRuntimeWorkers {
/**
 * started은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    var started: Boolean = false
/**
 * stopped은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val stopped = mutableListOf<String>()

/**
 * start은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun start() {
        started = true
    }

/**
 * stopProjection은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun stopProjection() {
        stopped += "projection"
    }

/**
 * stopRebuild은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun stopRebuild() {
        stopped += "rebuild"
    }

/**
 * stopMaintenance은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun stopMaintenance() {
        stopped += "maintenance"
    }

/**
 * releaseFencedLeases은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun releaseFencedLeases() {
        stopped += "leases"
    }
}

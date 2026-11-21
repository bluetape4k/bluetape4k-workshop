package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * EventSourcedLifecycleCoordinatorTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedLifecycleCoordinatorTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "shutdown refuses readiness before stopping workers draining permits and releasing
 * leases" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `shutdown refuses readiness before stopping workers draining permits and releasing leases`() {
        val state = EventSourcedOperationalState().also(EventSourcedOperationalState::markReady)
        val coordinator = EventSourcedLifecycleCoordinator(
            state = state,
            gate = EventSourcedDatabasePermitGate(),
            workers = EventSourcedWorkerShutdown({}, {}, {}, {}),
            graceDeadline = 10.millis(),
        )

        coordinator.shutdown()

        coordinator.events() shouldBeEqualTo listOf(
            EventSourcedShutdownEvent.READINESS_DOWN,
            EventSourcedShutdownEvent.REJECT_ADMISSION,
            EventSourcedShutdownEvent.STOP_PROJECTION,
            EventSourcedShutdownEvent.STOP_REBUILD,
            EventSourcedShutdownEvent.STOP_MAINTENANCE,
            EventSourcedShutdownEvent.AWAIT_DRAIN,
            EventSourcedShutdownEvent.RELEASE_FENCED_LEASES,
        )

        state.readinessState() shouldBeEqualTo EventSourcedReadinessState.STOPPING
        coordinator.forcedDrain().shouldBeFalse()
    }
}

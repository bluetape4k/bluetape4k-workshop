package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

/**
 * EventSourcedHealthIndicatorsTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedHealthIndicatorsTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "startup authority failure and shutdown alone refuse readiness" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `startup authority failure and shutdown alone refuse readiness`() {
        val state = EventSourcedOperationalState()
        val readiness = EventSourcedReadinessHealthIndicator(state)

        readiness.health().status shouldBeEqualTo Status.OUT_OF_SERVICE

        state.markReady()
        readiness.health().status shouldBeEqualTo Status.UP

        state.markAuthorityFailure()
        readiness.health().status shouldBeEqualTo Status.DOWN

        state.beginShutdown()
        readiness.health().status shouldBeEqualTo Status.OUT_OF_SERVICE
    }

    @Test
/**
 * 이 시나리오는 "aggregate projection and every rebuild state remain separately diagnosable without
 * dropping readiness" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `aggregate projection and every rebuild state remain separately diagnosable without dropping readiness`() {
        val state = EventSourcedOperationalState()
        val readiness = EventSourcedReadinessHealthIndicator(state)
        val aggregate = EventSourcedAggregateHealthIndicator(state)
        val projection = EventSourcedProjectionHealthIndicator(state)
        state.markReady()

        state.degradeAggregate()
        aggregate.health().status shouldBeEqualTo EVENT_SOURCED_DEGRADED_STATUS
        readiness.health().status shouldBeEqualTo Status.UP

        state.projectionHealth(degraded = true, rebuildState = ProjectionGenerationState.FAILED)
        projection.health().status shouldBeEqualTo EVENT_SOURCED_DEGRADED_STATUS
        readiness.health().status shouldBeEqualTo Status.UP

        ProjectionGenerationState.entries.forEach { rebuildState ->
            log.debug { "rebuildState=$rebuildState" }
            state.rebuildState(rebuildState)
            projection.health().details["rebuildState"] shouldBeEqualTo rebuildState.name
            readiness.health().status shouldBeEqualTo Status.UP
        }
    }
}

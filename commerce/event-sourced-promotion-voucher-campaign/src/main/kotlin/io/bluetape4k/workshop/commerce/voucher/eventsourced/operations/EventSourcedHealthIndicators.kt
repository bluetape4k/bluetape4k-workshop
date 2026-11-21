package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * `EVENT_SOURCED_DEGRADED_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
internal val EVENT_SOURCED_DEGRADED_STATUS = Status("DEGRADED")

/**
 * `EventSourcedReadinessState`은 수명주기 상태입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class EventSourcedReadinessState {
    /**
     * `EventSourcedReadinessState.STARTING`은 `starting` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal
     * 여부를 확인합니다.
     */
    STARTING,
    /**
     * `EventSourcedReadinessState.READY`은 `ready` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를
     * 확인합니다.
     */
    READY,
    /**
     * `EventSourcedReadinessState.AUTHORITY_FAILED`은 업무 처리가 실패해 결과를 성공으로 간주할 수 없는 값입니다.
     */
    AUTHORITY_FAILED,
    /**
     * `EventSourcedReadinessState.STOPPING`은 `stopping` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal
     * 여부를 확인합니다.
     */
    STOPPING,
}

/** process readiness를 advisory aggregate/projection/rebuild degradation과 분리합니다. */
/** process readiness를 advisory aggregate/projection/rebuild degradation과 분리합니다. */
@Component
internal class EventSourcedOperationalState {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val readiness = AtomicReference(EventSourcedReadinessState.STARTING)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val aggregateDegraded = AtomicReference(false)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val projectionDegraded = AtomicReference(false)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val rebuild = AtomicReference<ProjectionGenerationState?>(null)

    /**
     * markReady은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun markReady() {
        readiness.set(EventSourcedReadinessState.READY)
    }

    /**
     * markAuthorityFailure은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun markAuthorityFailure() {
        readiness.set(EventSourcedReadinessState.AUTHORITY_FAILED)
    }

    /**
     * beginShutdown은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun beginShutdown() {
        readiness.set(EventSourcedReadinessState.STOPPING)
    }

    /**
     * degradeAggregate은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun degradeAggregate() {
        aggregateDegraded.set(true)
    }

    /**
     * projectionHealth은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param degraded 업무 처리에 필요한 degraded 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param rebuildState 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun projectionHealth(
        degraded: Boolean,
        rebuildState: ProjectionGenerationState? = null,
    ) {
        projectionDegraded.set(degraded)
        rebuildState?.let(rebuild::set)
    }

    /**
     * rebuildState은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun rebuildState(state: ProjectionGenerationState) {
        rebuild.set(state)
    }

    /**
     * readinessState은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun readinessState(): EventSourcedReadinessState = readiness.get()

    /**
     * isAggregateDegraded은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun isAggregateDegraded(): Boolean = aggregateDegraded.get()

    /**
     * isProjectionDegraded은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun isProjectionDegraded(): Boolean = projectionDegraded.get()

    /**
     * rebuildState은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun rebuildState(): ProjectionGenerationState? = rebuild.get()
}
/**
 * EventSourcedReadinessHealthIndicator은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property state 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


@Component("eventSourcedReadinessHealthIndicator")
internal class EventSourcedReadinessHealthIndicator(
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val state: EventSourcedOperationalState,
): HealthIndicator {
    /**
     * health은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health =
        when (state.readinessState()) {
            EventSourcedReadinessState.READY -> Health.up().build()
            EventSourcedReadinessState.AUTHORITY_FAILED -> Health.down().build()
            EventSourcedReadinessState.STARTING,
            EventSourcedReadinessState.STOPPING,
                                             -> Health.status(Status.OUT_OF_SERVICE).build()
        }
}
/**
 * EventSourcedAggregateHealthIndicator은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property state 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


@Component("eventSourcedAggregateHealthIndicator")
internal class EventSourcedAggregateHealthIndicator(
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val state: EventSourcedOperationalState,
): HealthIndicator {
    /**
     * health은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health =
        if (state.isAggregateDegraded()) Health.status(EVENT_SOURCED_DEGRADED_STATUS).build() else Health.up().build()
}
/**
 * EventSourcedProjectionHealthIndicator은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property state 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


@Component("eventSourcedProjectionHealthIndicator")
internal class EventSourcedProjectionHealthIndicator(
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val state: EventSourcedOperationalState,
): HealthIndicator {
    /**
     * health은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health =
        Health.status(if (state.isProjectionDegraded()) EVENT_SOURCED_DEGRADED_STATUS else Status.UP)
            .withDetail("rebuildState", state.rebuildState()?.name ?: "NONE")
            .build()
}

/** liveness는 process-local health만 보고합니다. external authority는 readiness에 속합니다. */
/** liveness는 process-local health만 보고합니다. external authority는 readiness에 속합니다. */
@Component("eventSourcedLivenessHealthIndicator")
internal class EventSourcedLivenessHealthIndicator: HealthIndicator {
    /**
     * health은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health = Health.up().build()
}

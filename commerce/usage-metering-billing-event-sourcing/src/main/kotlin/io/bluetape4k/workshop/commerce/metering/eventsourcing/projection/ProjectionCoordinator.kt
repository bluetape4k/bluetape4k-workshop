package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID
/**
 * ProjectionCoordinator은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property repository 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class ProjectionCoordinator(
    /**
     * `repository`은 사용량 과금·정산에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: ProjectionCheckpointRepository,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock = Clock.systemUTC(),
) {

    companion object: KLogging()

    /**
     * apply은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param globalPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param handler 업무 처리에 필요한 handler 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun apply(
        lease: ProjectionLease,
        eventId: UUID,
        globalPosition: Long,
        handler: () -> Unit,
    ): ProjectionApplyResult {
        repository.requireOwnership(lease)
        val inserted = repository.insertAppliedEvent(lease, eventId, globalPosition, clock.instant())
        if (inserted) handler()

        repository.advanceCheckpoint(lease, globalPosition, clock.instant())
        return if (inserted) ProjectionApplyResult.APPLIED else ProjectionApplyResult.ALREADY_APPLIED
    }
}

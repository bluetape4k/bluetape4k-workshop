package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import org.springframework.stereotype.Component
import java.time.Instant
/**
 * ProjectionRebuilder은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property generations 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property checkpoints 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property metrics 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property eventStore 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */


@Component
class ProjectionRebuilder(
    /**
     * `generations`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val generations: ProjectionGenerationRepository,
    /**
     * `checkpoints`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val checkpoints: ProjectionCheckpointRepository,
    /**
     * `metrics`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: ProjectionTelemetry? = null,
    /**
     * `eventStore`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventStore: EventStore? = null,
) {

    companion object: KLogging()

    /**
     * beginNext은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun beginNext(projectionName: String, now: Instant): ProjectionGeneration {
        val highWatermark = checkNotNull(eventStore) { "event_store_required" }.latestGlobalPosition()

        return generations.createNextBuilding(projectionName, highWatermark, now).also {
            metrics?.recordRebuild("started")
        }
    }

    /**
     * begin은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param nextGeneration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param startHighWatermark 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun begin(projectionName: String, nextGeneration: Int, startHighWatermark: Long, now: Instant) {
        val active = checkNotNull(generations.active(projectionName)) { "active_projection_missing:$projectionName" }
        require(nextGeneration > active.generation) { "projection_generation_must_increase" }

        generations.createBuilding(projectionName, nextGeneration, startHighWatermark, now)
        metrics?.recordRebuild("started")
    }

    /**
     * catchUpAndSwitch은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedActiveGeneration 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param observedHighWatermark 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun catchUpAndSwitch(
        lease: ProjectionLease,
        expectedActiveGeneration: Int,
        observedHighWatermark: Long,
        now: Instant,
    ): Boolean {
        checkpoints.raiseHighWatermark(lease, observedHighWatermark, now)

        return generations.switchActive(lease, expectedActiveGeneration, now).also { switched ->
            metrics?.recordRebuild(if (switched) "switched" else "pending")
        }
    }
}

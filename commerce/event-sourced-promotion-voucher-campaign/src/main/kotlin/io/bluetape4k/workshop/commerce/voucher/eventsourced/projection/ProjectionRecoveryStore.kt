package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.*
/**
 * ProjectionReconciliationSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property streamPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property checkpointPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property lag 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property failedPoisonCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야
 * 합니다.
 */


internal data class ProjectionReconciliationSnapshot(
    /**
     * `streamPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val streamPosition: Long,
    /**
     * `checkpointPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpointPosition: Long,
    /**
     * `lag`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val lag: Long,
    /**
     * `failedPoisonCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val failedPoisonCount: Long,
)

/**
 * ProjectionRecoveryStore은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class ProjectionRecoveryStore {

    companion object: KLogging()

    /**
     * event은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun event(eventId: UUID): EventEnvelope? =
        EventLog
            .selectAll()
            .where { EventLog.id eq eventId }
            .singleOrNull()
            ?.let(::toProjectionEnvelope)

    /**
     * reconciliation은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun reconciliation(key: ProjectionKey): ProjectionReconciliationSnapshot {
        val streamPosition = EventLog
            .selectAll()
            .orderBy(EventLog.globalPosition to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(EventLog.globalPosition)
            ?: 0L
        val checkpointPosition = ProjectionCheckpoints
            .selectAll()
            .where {
                (ProjectionCheckpoints.projection eq key.projection) and
                        (ProjectionCheckpoints.generation eq key.generation)
            }.singleOrNull()
            ?.get(ProjectionCheckpoints.position)
            ?: 0L
        val failedPoisonCount = ProjectionPoisonEvents
            .selectAll()
            .where {
                (ProjectionPoisonEvents.projection eq key.projection) and
                        (ProjectionPoisonEvents.generation eq key.generation) and
                        (ProjectionPoisonEvents.state eq ProjectionPoisonState.FAILED)
            }.count()
        
        return ProjectionReconciliationSnapshot(
            streamPosition = streamPosition,
            checkpointPosition = checkpointPosition,
            lag = (streamPosition - checkpointPosition).coerceAtLeast(0),
            failedPoisonCount = failedPoisonCount,
        )
    }
}

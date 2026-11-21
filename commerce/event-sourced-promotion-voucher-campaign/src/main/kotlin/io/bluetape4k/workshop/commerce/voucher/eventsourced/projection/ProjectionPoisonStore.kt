package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.*

/**
 * ProjectionPoisonStore은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class ProjectionPoisonStore {
    /**
     * recordFailure은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param reasonClass 업무 처리에 필요한 reasonClass 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun recordFailure(
        key: ProjectionKey,
        event: EventEnvelope,
        reasonClass: String,
        now: Instant,
    ): ProjectionPoisonRecord {
        val inserted = ProjectionPoisonEvents.insertIgnore { row ->
            row[ProjectionPoisonEvents.projection] = key.projection
            row[ProjectionPoisonEvents.generation] = key.generation
            row[ProjectionPoisonEvents.eventId] = event.eventId
            row[ProjectionPoisonEvents.globalPosition] = event.globalPosition
            row[ProjectionPoisonEvents.eventType] = event.eventType
            row[ProjectionPoisonEvents.reasonClass] = reasonClass
            row[ProjectionPoisonEvents.attempts] = FIRST_POISON_ATTEMPT
            row[ProjectionPoisonEvents.state] = ProjectionPoisonState.FAILED
            row[ProjectionPoisonEvents.nextRetryAt] = nextRetryAt(now, FIRST_POISON_ATTEMPT)
            row[ProjectionPoisonEvents.resolvedAt] = null
            row[ProjectionPoisonEvents.occurredAt] = now
            row[ProjectionPoisonEvents.updatedAt] = now
        }.insertedCount == 1

        if (!inserted) {
            incrementFailure(key, event.eventId, reasonClass, now)
        }
        return find(key, event.eventId).checkNotNull { "poison event was not persisted" }
    }

    /**
     * find은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param eventId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun find(
        key: ProjectionKey,
        eventId: UUID,
    ): ProjectionPoisonRecord? =
        ProjectionPoisonEvents
            .selectAll()
            .where {
                poisonKey(key) and
                        (ProjectionPoisonEvents.eventId eq eventId)
            }.singleOrNull()
            ?.let(::toPoisonRecord)

    /**
     * resolve은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param eventId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun resolve(
        key: ProjectionKey,
        eventId: UUID,
        now: Instant,
    ) {
        val updated = ProjectionPoisonEvents.update(
            where = {
                poisonKey(key) and
                        (ProjectionPoisonEvents.eventId eq eventId) and
                        (ProjectionPoisonEvents.state eq ProjectionPoisonState.FAILED)
            },
        ) { row ->
            row[ProjectionPoisonEvents.state] = ProjectionPoisonState.RESOLVED
            row[ProjectionPoisonEvents.resolvedAt] = now
            row[ProjectionPoisonEvents.updatedAt] = now
        } == 1

        check(updated) { "poison event resolution lost its state fence" }
    }

    /**
     * failedCountsByReasonClass은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generations 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun failedCountsByReasonClass(
        projection: String,
        generations: List<Long>,
    ): Map<String, Long> {
        if (generations.isEmpty()) return emptyMap()
        val failedCount = ProjectionPoisonEvents.id.count()

        return ProjectionPoisonEvents
            .select(ProjectionPoisonEvents.reasonClass, failedCount)
            .where { ProjectionPoisonEvents.projection eq projection }
            .andWhere { ProjectionPoisonEvents.generation inList generations }
            .andWhere { ProjectionPoisonEvents.state eq ProjectionPoisonState.FAILED }
            .groupBy(ProjectionPoisonEvents.reasonClass)
            .associate { row ->
                row[ProjectionPoisonEvents.reasonClass] to row[failedCount]
            }
    }

    /**
     * incrementFailure은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param eventId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param reasonClass 업무 처리에 필요한 reasonClass 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun incrementFailure(
        key: ProjectionKey,
        eventId: UUID,
        reasonClass: String,
        now: Instant,
    ) {
        val current = find(key, eventId).checkNotNull { "poison event disappeared" }

        if (current.state != ProjectionPoisonState.FAILED ||
            current.attempts >= MAX_PROJECTION_POISON_ATTEMPTS
        ) {
            return
        }
        val nextAttempt = current.attempts + 1

        ProjectionPoisonEvents.update(
            where = {
                poisonKey(key) and
                        (ProjectionPoisonEvents.eventId eq eventId) and
                        (ProjectionPoisonEvents.attempts eq current.attempts)
            },
        ) { row ->
            row[ProjectionPoisonEvents.attempts] = nextAttempt
            row[ProjectionPoisonEvents.reasonClass] = reasonClass
            row[ProjectionPoisonEvents.nextRetryAt] = nextRetryAt(now, nextAttempt)
            row[ProjectionPoisonEvents.updatedAt] = now
        }
    }
}
/**
 * ProjectionPoisonStatusQuery은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property poisons 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class ProjectionPoisonStatusQuery(
    /**
     * `poisons`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val poisons: ProjectionPoisonStore = ProjectionPoisonStore(),
) {
    /**
     * failedCountsByReasonClass은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun failedCountsByReasonClass(projection: String): Map<String, Long> {
        TransactionManager.current()
        val validProjection = projection.requireNotBlank("projection")
        val generations = listOfNotNull(
            findActive(validProjection)?.generation,
            findInProgressGeneration(validProjection)?.key?.generation,
        ).distinct()

        return poisons.failedCountsByReasonClass(validProjection, generations)
    }
}

/**
 * `FIRST_POISON_ATTEMPT`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val FIRST_POISON_ATTEMPT = 1
/**
 * `MAX_POISON_RETRY_BACKOFF_SECONDS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MAX_POISON_RETRY_BACKOFF_SECONDS = 30L

/**
 * nextRetryAt은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param attempt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun nextRetryAt(
    now: Instant,
    attempt: Int,
): Instant {
    val delaySeconds = (1L shl (attempt - 1)).coerceAtMost(MAX_POISON_RETRY_BACKOFF_SECONDS)
    return now + delaySeconds.seconds()
}

/**
 * poisonKey은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
private fun poisonKey(key: ProjectionKey) =
    (ProjectionPoisonEvents.projection eq key.projection) and
            (ProjectionPoisonEvents.generation eq key.generation)

/**
 * toPoisonRecord은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun toPoisonRecord(row: ResultRow): ProjectionPoisonRecord =
    ProjectionPoisonRecord(
        eventId = row[ProjectionPoisonEvents.eventId],
        globalPosition = row[ProjectionPoisonEvents.globalPosition],
        reasonClass = row[ProjectionPoisonEvents.reasonClass],
        attempts = row[ProjectionPoisonEvents.attempts],
        state = row[ProjectionPoisonEvents.state],
        nextRetryAt = row[ProjectionPoisonEvents.nextRetryAt],
        resolvedAt = row[ProjectionPoisonEvents.resolvedAt],
    )

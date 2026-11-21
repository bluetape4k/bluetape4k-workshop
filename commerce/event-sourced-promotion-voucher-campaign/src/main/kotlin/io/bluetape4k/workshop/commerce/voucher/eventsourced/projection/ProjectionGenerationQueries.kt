package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * `CANCELLABLE_STATES`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
 *
 * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
 */
internal val CANCELLABLE_STATES = setOf(
    ProjectionGenerationState.BUILDING,
    ProjectionGenerationState.VALIDATING
)

/**
 * generationPredicate은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
internal fun generationPredicate(key: ProjectionKey) =
    (ProjectionGenerations.projection eq key.projection) and
            (ProjectionGenerations.generation eq key.generation)

/**
 * findActive은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun findActive(projection: String): ActiveProjectionGeneration? =
    ActiveProjectionGenerations
        .selectAll()
        .where { ActiveProjectionGenerations.projection eq projection }
        .singleOrNull()
        ?.let(::toActiveGeneration)

/**
 * requireLockedActive은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun requireLockedActive(projection: String): ActiveProjectionGeneration =
    ActiveProjectionGenerations
        .selectAll()
        .where { ActiveProjectionGenerations.projection eq projection }
        .forUpdate()
        .singleOrNull()
        ?.let(::toActiveGeneration)
        .checkNotNull { "active projection must be initialized" }

/**
 * findGeneration은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
internal fun findGeneration(key: ProjectionKey): ProjectionGeneration? =
    ProjectionGenerations
        .selectAll()
        .where { generationPredicate(key) }
        .singleOrNull()
        ?.let(::toGeneration)

/**
 * lockProjectionGeneration은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
 * 거절합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
internal fun lockProjectionGeneration(key: ProjectionKey): ProjectionGeneration? =
    ProjectionGenerations
        .selectAll()
        .where { generationPredicate(key) }
        .forUpdate()
        .singleOrNull()
        ?.let(::toGeneration)

/**
 * lockBuildingGeneration은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
 * 거절합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param fencingToken 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
 * @param cancellationRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
internal fun lockBuildingGeneration(
    key: ProjectionKey,
    fencingToken: Long,
    cancellationRevision: Long,
): ProjectionGeneration? =
    lockProjectionGeneration(key)?.takeIf { generation ->
        generation.state == ProjectionGenerationState.BUILDING &&
                generation.fencingToken == fencingToken &&
                generation.cancellationRevision == cancellationRevision
    }

/**
 * findBuildingGeneration은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun findBuildingGeneration(projection: String): ProjectionGeneration? =
    ProjectionGenerations
        .selectAll()
        .where {
            (ProjectionGenerations.projection eq projection) and
                    (ProjectionGenerations.state eq ProjectionGenerationState.BUILDING)
        }.singleOrNull()
        ?.let(::toGeneration)

/**
 * latestProjectionGeneration은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun latestProjectionGeneration(projection: String): Long =
    ProjectionGenerations
        .selectAll()
        .where { ProjectionGenerations.projection eq projection }
        .orderBy(ProjectionGenerations.generation to SortOrder.DESC)
        .limit(1)
        .single()[ProjectionGenerations.generation]

/**
 * toActiveGeneration은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun toActiveGeneration(row: ResultRow): ActiveProjectionGeneration =
    ActiveProjectionGeneration(
        projection = row[ActiveProjectionGenerations.projection],
        generation = row[ActiveProjectionGenerations.generation],
        revision = row[ActiveProjectionGenerations.revision],
    )

/**
 * toGeneration은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun toGeneration(row: ResultRow): ProjectionGeneration =
    ProjectionGeneration(
        key = ProjectionKey(row[ProjectionGenerations.projection], row[ProjectionGenerations.generation]),
        state = row[ProjectionGenerations.state],
        progress = ProjectionGenerationProgress(
            targetPosition = row[ProjectionGenerations.targetPosition],
            currentPosition = row[ProjectionGenerations.currentPosition],
            fencingToken = row[ProjectionGenerations.fencingToken],
            cancellationRevision = row[ProjectionGenerations.cancellationRevision],
        ),
        canonicalDigest = row[ProjectionGenerations.canonicalDigest]?.let(ReceiptDigest::of),
        retryableFailure = row[ProjectionGenerations.retryableFailure],
    )

package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * retirePreviousProjectionGeneration은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param pointer 업무 처리에 필요한 pointer 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun retirePreviousProjectionGeneration(
    pointer: ActiveProjectionGeneration,
    now: Instant,
) {
    val previous = ProjectionKey(pointer.projection, pointer.generation)
    ProjectionGenerations.update(where = { generationPredicate(previous) }) { row ->
        row[ProjectionGenerations.state] = ProjectionGenerationState.RETIRED
        row[ProjectionGenerations.updatedAt] = now
    }
}

/**
 * insertInitialProjectionGeneration은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun insertInitialProjectionGeneration(
    projection: String,
    now: Instant,
) {
    ProjectionGenerations.insertIgnore { row ->
        row[ProjectionGenerations.projection] = projection
        row[ProjectionGenerations.generation] = FIRST_REBUILD_GENERATION
        row[ProjectionGenerations.state] = ProjectionGenerationState.ACTIVE
        row[ProjectionGenerations.targetPosition] = 0
        row[ProjectionGenerations.currentPosition] = 0
        row[ProjectionGenerations.fencingToken] = FIRST_REBUILD_FENCING_TOKEN
        row[ProjectionGenerations.cancellationRevision] = 0
        row[ProjectionGenerations.canonicalDigest] = null
        row[ProjectionGenerations.retryableFailure] = false
        row[ProjectionGenerations.createdAt] = now
        row[ProjectionGenerations.updatedAt] = now
    }
}

/**
 * updateActiveProjectionPointer은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param pointer 업무 처리에 필요한 pointer 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun updateActiveProjectionPointer(
    key: ProjectionKey,
    pointer: ActiveProjectionGeneration,
    now: Instant,
) {
    ActiveProjectionGenerations.update(where = { ActiveProjectionGenerations.projection eq key.projection }) { row ->
        row[ActiveProjectionGenerations.generation] = key.generation
        row[ActiveProjectionGenerations.revision] = pointer.revision + 1
        row[ActiveProjectionGenerations.updatedAt] = now
    }
}

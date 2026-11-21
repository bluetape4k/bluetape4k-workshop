package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * findInProgressGeneration은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun findInProgressGeneration(projection: String): ProjectionGeneration? =
    ProjectionGenerations
        .selectAll()
        .where {
            (ProjectionGenerations.projection eq projection) and
                    (ProjectionGenerations.state inList IN_PROGRESS_GENERATION_STATES)
        }.orderBy(ProjectionGenerations.generation to SortOrder.ASC)
        .limit(1)
        .singleOrNull()
        ?.let { row ->
            findGeneration(
                ProjectionKey(
                    row[ProjectionGenerations.projection],
                    row[ProjectionGenerations.generation],
                ),
            )
        }

/**
 * `IN_PROGRESS_GENERATION_STATES`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
private val IN_PROGRESS_GENERATION_STATES = listOf(
    ProjectionGenerationState.BUILDING,
    ProjectionGenerationState.VALIDATING,
    ProjectionGenerationState.CANCELLING,
)

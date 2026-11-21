package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * `MAX_REBUILD_RETENTION_BATCH_SIZE`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
internal const val MAX_REBUILD_RETENTION_BATCH_SIZE = 100

/**
 * ProjectionRebuildMaintenance은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class ProjectionRebuildMaintenance {

    companion object: KLogging()

    /**
     * recover은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun recover(
        key: ProjectionKey,
        now: Instant,
    ): ProjectionGeneration? {
        TransactionManager.current()
        return lockProjectionGeneration(key)?.let { current ->
            if (current.state != ProjectionGenerationState.CANCELLING) {
                current
            } else {
                ProjectionGenerations.update(
                    where = {
                        generationPredicate(key) and
                                (ProjectionGenerations.state eq ProjectionGenerationState.CANCELLING) and
                                (ProjectionGenerations.fencingToken eq current.fencingToken)
                    },
                ) { row ->
                    row[ProjectionGenerations.state] = ProjectionGenerationState.CANCELLED
                    row[ProjectionGenerations.updatedAt] = now
                }
                findGeneration(key).checkNotNull { "recovered generation disappeared" }
            }
        }
    }

    /**
     * purgeRetired은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param retiredBefore 업무 처리에 필요한 retiredBefore 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param batchSize 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun purgeRetired(
        projection: String,
        retiredBefore: Instant,
        batchSize: Int,
    ): Int {
        TransactionManager.current()
        projection.requireNotBlank("projection")
        batchSize.requireInRange(1, MAX_REBUILD_RETENTION_BATCH_SIZE, "batchSize")

        log.debug { "Purge retired. batchSize=$batchSize, retiredBefore=$retiredBefore" }
        return ProjectionGenerations
            .selectAll()
            .where {
                (ProjectionGenerations.projection eq projection) and
                        (ProjectionGenerations.state eq ProjectionGenerationState.RETIRED) and
                        (ProjectionGenerations.updatedAt less retiredBefore)
            }
            .orderBy(ProjectionGenerations.generation to SortOrder.ASC)
            .limit(batchSize)
            .map { row ->
                ProjectionKey(row[ProjectionGenerations.projection], row[ProjectionGenerations.generation])
            }.sumOf { retiredKey ->
                ProjectionGenerations.deleteWhere { generationPredicate(retiredKey) }
            }
    }
}

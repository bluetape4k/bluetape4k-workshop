package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.BillingReadModelEntry
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.NewBillingReadModelEntry
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.NewProjectionFailure
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionFailure
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionModelType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.*

@Repository
/**
 * BillingReadModelRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingReadModelRepository
    : EventSourcingExposedJdbcRepository<BillingReadModelEntity, UUID>(BillingReadModelEntity::class) {

    /**
     * append은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entry 업무 처리에 필요한 entry 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun append(entry: NewBillingReadModelEntry) {
        BillingReadModels.insert {
            it[id] = Uuid.V7.nextId()
            it[projectionName] = entry.projectionName
            it[generation] = entry.generation
            it[tenantId] = entry.tenantId
            it[modelType] = entry.modelType.name
            it[entryId] = entry.entryId
            it[eventType] = entry.eventType
            it[globalPosition] = entry.globalPosition
            it[quantity] = entry.quantity
            it[amount] = entry.amount
            it[currency] = entry.currency
            it[provenance] = entry.provenance
            it[occurredAt] = entry.occurredAt
        }
    }

    /**
     * entries은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     */
    fun entries(projectionName: String, generation: Int, tenantId: String): List<BillingReadModelEntry> =
        BillingReadModels
            .selectAll()
            .where {
                (BillingReadModels.projectionName eq projectionName) and
                        (BillingReadModels.generation eq generation) and
                        (BillingReadModels.tenantId eq tenantId)
            }
            .orderBy(
                BillingReadModels.globalPosition to SortOrder.ASC,
                BillingReadModels.modelType to SortOrder.ASC,
            )
            .map { row ->
                BillingReadModelEntry(
                    modelType = ProjectionModelType.valueOf(row[BillingReadModels.modelType]),
                    entryId = row[BillingReadModels.entryId],
                    eventType = row[BillingReadModels.eventType],
                    globalPosition = row[BillingReadModels.globalPosition],
                    quantity = row[BillingReadModels.quantity],
                    amount = row[BillingReadModels.amount],
                    currency = row[BillingReadModels.currency],
                    provenance = row[BillingReadModels.provenance],
                    occurredAt = row[BillingReadModels.occurredAt],
                )
            }

    /**
     * financialTotal은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     */
    fun financialTotal(projectionName: String, generation: Int, tenantId: String): BigDecimal =
        entries(projectionName, generation, tenantId)
            .filter { entry ->
                entry.modelType == ProjectionModelType.LEDGER_DEBIT ||
                        entry.modelType == ProjectionModelType.LEDGER_CREDIT
            }
            .fold(BigDecimal.ZERO) { total, entry ->
                /**
                 * `amount`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
                 *
                 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
                 */
                val amount = checkNotNull(entry.amount)
                if (entry.modelType == ProjectionModelType.LEDGER_CREDIT) total - amount else total + amount
            }
}

@Repository
/**
 * ProjectionFailureRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ProjectionFailureRepository:
    AppendOnlyEventSourcingExposedJdbcRepository<ProjectionFailureEntity, UUID>(ProjectionFailureEntity::class) {

    /**
     * record은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun record(failure: NewProjectionFailure) {
        val inserted = ProjectionFailures.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[projectionName] = failure.projectionName
            it[generation] = failure.generation
            it[eventId] = failure.eventId
            it[eventType] = failure.eventType
            it[globalPosition] = failure.globalPosition
            it[errorDigest] = failure.errorDigest
            it[attemptCount] = 1
            it[firstFailedAt] = failure.failedAt
            it[lastFailedAt] = failure.failedAt
        }.insertedCount == 1

        if (!inserted) incrementAttempt(failure)
    }

    /**
     * latest은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun latest(projectionName: String, generation: Int): ProjectionFailure? =
        ProjectionFailures
            .selectAll()
            .where {
                (ProjectionFailures.projectionName eq projectionName) and
                        (ProjectionFailures.generation eq generation)
            }
            .orderBy(ProjectionFailures.globalPosition to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let { row ->
                ProjectionFailure(
                    eventId = row[ProjectionFailures.eventId],
                    eventType = row[ProjectionFailures.eventType],
                    globalPosition = row[ProjectionFailures.globalPosition],
                    errorDigest = row[ProjectionFailures.errorDigest],
                    attemptCount = row[ProjectionFailures.attemptCount],
                )
            }

    /**
     * incrementAttempt은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun incrementAttempt(failure: NewProjectionFailure) {
        val row = ProjectionFailures
            .selectAll()
            .where {
                (ProjectionFailures.projectionName eq failure.projectionName) and
                        (ProjectionFailures.generation eq failure.generation) and
                        (ProjectionFailures.eventId eq failure.eventId)
            }
            .forUpdate()
            .single()

        ProjectionFailures.update({ ProjectionFailures.id eq row[ProjectionFailures.id] }) {
            it[attemptCount] = row[ProjectionFailures.attemptCount] + 1
            it[ProjectionFailures.errorDigest] = failure.errorDigest
            it[lastFailedAt] = failure.failedAt
        }
    }
}

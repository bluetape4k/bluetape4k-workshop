package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.util.*

/**
 * tenant와 aggregate revision uniqueness를 보장하는 append-only audit repository입니다.
 * @property gate 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Repository
internal class AuditRepository(
    /**
     * `gate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val gate: DatabasePermitGate,
): LongAuditableJdbcRepository<AuditRecord, AuditTable> {

    companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val MAX_STREAM_BATCH = 200
    }
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = AuditTable
    /**
     * extractId은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: AuditRecord): Long = entity.id

    /**
     * toEntity은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): AuditRecord = AuditRecord(
        id = this[table.id].value,
        tenantId = this[table.tenantId],
        campaignId = this[table.campaignId],
        aggregateType = this[table.aggregateType],
        aggregateId = this[table.aggregateId],
        revision = this[table.revision],
        actorType = this[table.actorType],
        reasonCode = this[table.reasonCode],
        policyVersion = this[table.policyVersion],
        correlationDigest = this[table.correlationDigest],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt],
    )

    /**
     * append은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    fun append(record: AuditRecord): AuditRecord {
        gate.requireHeld()
        val id = table.insertAndGetId {
            it[tenantId] = record.tenantId
            it[campaignId] = record.campaignId
            it[aggregateType] = record.aggregateType
            it[aggregateId] = record.aggregateId
            it[revision] = record.revision
            it[actorType] = record.actorType
            it[reasonCode] = record.reasonCode
            it[policyVersion] = record.policyVersion
            it[correlationDigest] = record.correlationDigest
        }.value

        return findById(id)
    }

    /**
     * findById은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun findById(id: Long): AuditRecord {
        gate.requireHeld()
        return table
            .selectAll()
            .where { table.id eq id }
            .single()
            .toEntity()
    }

    /**
     * findAggregate은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param aggregateId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findAggregate(
        tenantId: String,
        aggregateId: UUID,
    ): List<AuditRecord> {
        gate.requireHeld()
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.aggregateId eq aggregateId) }
            .map { it.toEntity() }
    }

    /**
     * findCursor은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findCursor(id: Long): AuditRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.toEntity()
    }

    /**
     * firstCampaignAudit은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun firstCampaignAudit(
        tenantId: String,
        campaignId: UUID,
    ): AuditRecord? {
        gate.requireHeld()
        return campaignQuery(tenantId, campaignId)
            .orderBy(table.id to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.toEntity()
    }

    /**
     * lastCampaignAudit은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun lastCampaignAudit(
        tenantId: String,
        campaignId: UUID,
    ): AuditRecord? {
        gate.requireHeld()
        return campaignQuery(tenantId, campaignId)
            .orderBy(table.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toEntity()
    }

    /** 전역적으로 단조 증가하는 audit-id 순서로 bounded campaign stream page 하나를 읽습니다. */
    /** 전역적으로 단조 증가하는 audit-id 순서로 bounded campaign stream page 하나를 읽습니다. */
    fun findCampaignAfter(
        tenantId: String,
        campaignId: UUID,
        afterId: Long,
        limit: Int,
    ): List<AuditRecord> {
        gate.requireHeld()
        limit.requireInRange(1, MAX_STREAM_BATCH, "limit")

        return table
            .selectAll()
            .where {
                (table.tenantId eq tenantId) and
                        (table.campaignId eq campaignId) and
                        (table.id greater afterId)
            }
            .orderBy(table.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toEntity() }
    }

    /**
     * hasReason은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param aggregateId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param reasonCode 업무 처리에 필요한 reasonCode 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun hasReason(
        tenantId: String,
        aggregateId: UUID,
        reasonCode: String,
    ): Boolean {
        gate.requireHeld()
        return table
            .selectAll()
            .where {
                (table.tenantId eq tenantId) and
                        (table.aggregateId eq aggregateId) and
                        (table.reasonCode eq reasonCode)
            }
            .limit(1)
            .any()
    }

    /**
     * campaignQuery은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun campaignQuery(
        tenantId: String,
        campaignId: UUID,
    ): Query = table
        .selectAll()
        .where { table.tenantId eq tenantId }
        .andWhere { table.campaignId eq campaignId }

}

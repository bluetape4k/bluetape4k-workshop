package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.util.*

/**
 * tenant-scoped campaign repository입니다. 모든 entry point는 미리 획득한 DB permit을 요구합니다.
 * @property gate 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Repository
internal class CampaignRepository(
    /**
     * `gate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val gate: DatabasePermitGate,
): LongAuditableJdbcRepository<CampaignRecord, CampaignTable> {

    companion object: KLogging()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = CampaignTable

    /**
     * extractId은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: CampaignRecord): Long = entity.id

    /**
     * toEntity은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): CampaignRecord = CampaignRecord(
        id = this[table.id].value,
        tenantId = this[table.tenantId],
        campaignId = this[table.campaignId],
        state = this[table.state],
        startsAt = this[table.startsAt],
        endsAt = this[table.endsAt],
        capacity = this[table.capacity],
        allocatedCount = this[table.allocatedCount],
        perUserLimit = this[table.perUserLimit],
        redemptionTtlSeconds = this[table.redemptionTtlSeconds],
        policyVersion = this[table.policyVersion],
        revision = this[table.revision],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt],
    )

    /**
     * create은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    fun create(record: CampaignRecord): CampaignRecord {
        gate.requireHeld()
        val id = table.insertAndGetId {
            it[tenantId] = record.tenantId
            it[campaignId] = record.campaignId
            it[state] = record.state
            it[startsAt] = record.startsAt
            it[endsAt] = record.endsAt
            it[capacity] = record.capacity
            it[allocatedCount] = record.allocatedCount
            it[perUserLimit] = record.perUserLimit
            it[redemptionTtlSeconds] = record.redemptionTtlSeconds
            it[policyVersion] = record.policyVersion
            it[revision] = record.revision
        }.value
        log.debug { "voucher_campaign_created campaignId=${record.campaignId}" }
        return findById(id)
    }

    /**
     * findById은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun findById(id: Long): CampaignRecord {
        gate.requireHeld()
        return table.selectAll()
            .where { table.id eq id }
            .single()
            .toEntity()
    }

    /**
     * findPublic은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findPublic(
        tenantId: String,
        campaignId: UUID,
    ): CampaignRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.campaignId eq campaignId) }
            .singleOrNull()
            ?.toEntity() 
    }

    /** capacity를 변경하는 모든 command에 대해 canonical first row lock을 획득합니다. */
    /** capacity를 변경하는 모든 command에 대해 canonical first row lock을 획득합니다. */
    fun findPublicForUpdate(
        tenantId: String,
        campaignId: UUID,
    ): CampaignRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.campaignId eq campaignId) }
            .forUpdate()
            .singleOrNull()
            ?.toEntity()
    }

    /**
     * tryReserve은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param id 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun tryReserve(
        tenantId: String,
        id: Long,
        expectedRevision: Long,
    ): Boolean {
        gate.requireHeld()
        return auditedUpdateAll(
            predicate = {
                (table.tenantId eq tenantId) and
                        (table.id eq id) and
                        (table.state eq CampaignState.ACTIVE) and
                        (table.revision eq expectedRevision) and
                        (table.allocatedCount less table.capacity)
            },
        ) {
            it[allocatedCount] = allocatedCount + 1
            it[revision] = expectedRevision + 1
        }.also { updated ->
            log.debug { "voucher_campaign_reserve campaignRowId=$id revision=$expectedRevision updated=$updated" }
        } == 1
    }

    /**
     * tryRelease은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param id 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun tryRelease(
        tenantId: String,
        id: Long,
        expectedRevision: Long,
    ): Boolean {
        gate.requireHeld()
        return auditedUpdateAll(
            predicate = {
                (table.tenantId eq tenantId) and
                        (table.id eq id) and
                        (table.revision eq expectedRevision) and
                        (table.allocatedCount greater 0)
            },
        ) {
            it[allocatedCount] = allocatedCount - 1
            it[revision] = expectedRevision + 1
        } == 1
    }

    /**
     * transition은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun transition(
        record: CampaignRecord,
        expectedRevision: Long,
    ): Boolean {
        gate.requireHeld()
        require(record.revision == expectedRevision + 1) { "campaign transition must advance revision exactly once" }

        return auditedUpdateAll(
            predicate = {
                (table.tenantId eq record.tenantId) and
                        (table.id eq record.id) and
                        (table.revision eq expectedRevision)
            },
        ) {
            it[state] = record.state
            it[startsAt] = record.startsAt
            it[endsAt] = record.endsAt
            it[capacity] = record.capacity
            it[allocatedCount] = record.allocatedCount
            it[perUserLimit] = record.perUserLimit
            it[redemptionTtlSeconds] = record.redemptionTtlSeconds
            it[policyVersion] = record.policyVersion
            it[revision] = record.revision
        } == 1
    }
}

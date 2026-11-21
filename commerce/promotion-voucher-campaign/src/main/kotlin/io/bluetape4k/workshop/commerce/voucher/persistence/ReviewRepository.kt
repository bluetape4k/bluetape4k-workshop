package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.util.*
/**
 * ReviewRepository은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property gate 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Repository
internal class ReviewRepository(
    /**
     * `gate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val gate: DatabasePermitGate,
): LongAuditableJdbcRepository<ReviewRecord, ReviewTable> {

    companion object: KLogging()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = ReviewTable
    /**
     * extractId은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: ReviewRecord): Long = entity.id

    /**
     * toEntity은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): ReviewRecord = ReviewRecord(
        id = this[table.id].value,
        tenantId = this[table.tenantId],
        campaignId = this[table.campaignId],
        claimRowId = this[table.claimRowId].value,
        claimId = this[table.claimId],
        kind = this[table.kind],
        status = this[table.status],
        reasonCode = this[table.reasonCode],
        signalSummary = this[table.signalSummary],
        reviewerActorDigest = this[table.reviewerActorDigest],
        expectedClaimRevision = this[table.expectedClaimRevision],
        revision = this[table.revision],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt],
    )

    /**
     * insert은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    fun insert(record: ReviewRecord): ReviewRecord {
        gate.requireHeld()
        val id = table.insertAndGetId {
            it[tenantId] = record.tenantId
            it[campaignId] = record.campaignId
            it[claimRowId] = record.claimRowId
            it[claimId] = record.claimId
            it[kind] = record.kind
            it[status] = record.status
            it[reasonCode] = record.reasonCode
            it[signalSummary] = record.signalSummary
            it[reviewerActorDigest] = record.reviewerActorDigest
            it[expectedClaimRevision] = record.expectedClaimRevision
            it[revision] = record.revision
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
    override fun findById(id: Long): ReviewRecord {
        gate.requireHeld()
        return table
            .selectAll()
            .where { table.id eq id }
            .single()
            .toEntity()
    }

    /**
     * findOpen은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param claimId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findOpen(
        tenantId: String,
        claimId: UUID,
    ): ReviewRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where {
                (table.tenantId eq tenantId) and
                        (table.claimId eq claimId) and
                        (table.status eq ReviewStatus.OPEN)
            }
            .singleOrNull()
            ?.toEntity()
    }

    /**
     * findOpenForUpdate은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param claimId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param reviewId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findOpenForUpdate(
        tenantId: String,
        claimId: UUID,
        reviewId: Long,
    ): ReviewRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where {
                (table.tenantId eq tenantId) and
                        (table.claimId eq claimId) and
                        (table.id eq reviewId) and
                        (table.status eq ReviewStatus.OPEN)
            }
            .forUpdate()
            .singleOrNull()
            ?.toEntity()
    }

    /**
     * findPage은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param afterId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun findPage(
        tenantId: String,
        status: ReviewStatus,
        afterId: Long?,
        limit: Int,
    ): List<ReviewRecord> {
        gate.requireHeld()
        val predicate = (table.tenantId eq tenantId) and
                (table.status eq status) and
                (afterId?.let { table.id greater it } ?: Op.TRUE)

        return table
            .selectAll()
            .where { predicate }
            .orderBy(table.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toEntity() }
    }

    /**
     * decide은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param review 업무 처리에 필요한 review 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param reviewerActorDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun decide(
        review: ReviewRecord,
        status: ReviewStatus,
        reviewerActorDigest: String,
        expectedRevision: Long,
    ): Boolean {
        gate.requireHeld()
        require(status != ReviewStatus.OPEN) { "review decision must be terminal" }

        return auditedUpdateAll(
            predicate = {
                (table.tenantId eq review.tenantId) and
                        (table.id eq review.id) and
                        (table.status eq ReviewStatus.OPEN) and
                        (table.revision eq expectedRevision)
            },
        ) {
            it[ReviewTable.status] = status
            it[ReviewTable.reviewerActorDigest] = reviewerActorDigest
            it[revision] = expectedRevision + 1
        } == 1
    }
}

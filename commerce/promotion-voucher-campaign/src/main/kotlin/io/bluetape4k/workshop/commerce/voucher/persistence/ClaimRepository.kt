package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.util.*

/**
 * plaintext voucher code를 절대 받지 않고 claim verifier와 key version을 저장합니다.
 * @property gate 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Repository
internal class ClaimRepository(
    /**
     * `gate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val gate: DatabasePermitGate,
): LongAuditableJdbcRepository<ClaimRecord, ClaimTable> {

    companion object: KLogging() {
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val USER_LIMIT_STATES: List<ClaimState> = listOf(
            ClaimState.ALLOCATED,
            ClaimState.REVIEW_REQUIRED,
            ClaimState.REDEEMED
        )
    }
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = ClaimTable

    /**
     * extractId은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: ClaimRecord): Long = entity.id

    /**
     * toEntity은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): ClaimRecord = ClaimRecord(
        id = this[table.id].value,
        tenantId = this[table.tenantId],
        campaignRowId = this[table.campaignRowId].value,
        campaignId = this[table.campaignId],
        claimId = this[table.claimId],
        allocationId = this[table.allocationId],
        userDigest = this[table.userDigest],
        state = this[table.state],
        reviewKind = this[table.reviewKind],
        pendingFromState = this[table.pendingFromState],
        capacityReserved = this[table.capacityReserved],
        allocationPolicyVersion = this[table.allocationPolicyVersion],
        codeVerifier = this[table.codeVerifier],
        generationKeyVersion = this[table.generationKeyVersion],
        verificationKeyVersion = this[table.verificationKeyVersion],
        expiresAt = this[table.expiresAt],
        redemptionReferenceDigest = this[table.redemptionReferenceDigest],
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
    fun insert(record: ClaimRecord): ClaimRecord {
        gate.requireHeld()
        val id = table.insertAndGetId {
            it[tenantId] = record.tenantId
            it[campaignRowId] = record.campaignRowId
            it[campaignId] = record.campaignId
            it[claimId] = record.claimId
            it[allocationId] = record.allocationId
            it[userDigest] = record.userDigest
            it[state] = record.state
            it[reviewKind] = record.reviewKind
            it[pendingFromState] = record.pendingFromState
            it[capacityReserved] = record.capacityReserved
            it[allocationPolicyVersion] = record.allocationPolicyVersion
            it[codeVerifier] = record.codeVerifier
            it[generationKeyVersion] = record.generationKeyVersion
            it[verificationKeyVersion] = record.verificationKeyVersion
            it[expiresAt] = record.expiresAt
            it[redemptionReferenceDigest] = record.redemptionReferenceDigest
            it[revision] = record.revision
        }.value
        log.debug { "voucher_claim_inserted claimId=${record.claimId}" }
        return findById(id)
    }

    /**
     * findById은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun findById(id: Long): ClaimRecord {
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
     * @param claimId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findPublic(
        tenantId: String,
        claimId: UUID,
    ): ClaimRecord? {
        gate.requireHeld()
        return table.selectAll()
            .where { (table.tenantId eq tenantId) and (table.claimId eq claimId) }
            .singleOrNull()
            ?.toEntity()
    }

    /**
     * findPublicForUpdate은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param claimId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findPublicForUpdate(
        tenantId: String,
        claimId: UUID,
    ): ClaimRecord? {
        gate.requireHeld()
        return table.selectAll()
            .where { (table.tenantId eq tenantId) and (table.claimId eq claimId) }
            .forUpdate()
            .singleOrNull()
            ?.toEntity()
    }

    /**
     * findByVerifier은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param verifier 업무 처리에 필요한 verifier 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun findByVerifier(
        tenantId: String,
        verifier: ByteArray?,
    ): ClaimRecord? {
        gate.requireHeld()
        if (verifier == null) return null
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.codeVerifier eq verifier) }
            .singleOrNull()
            ?.toEntity()
    }

    /**
     * countForUser은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param userDigest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    fun countForUser(
        tenantId: String,
        campaignId: UUID,
        userDigest: String,
    ): Long {
        gate.requireHeld()
        return table
            .selectAll()
            .where {
                (table.tenantId eq tenantId) and
                        (table.campaignId eq campaignId) and
                        (table.userDigest eq userDigest) and
                        (table.state inList USER_LIMIT_STATES)
            }.count()
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
        record: ClaimRecord,
        expectedRevision: Long,
    ): Boolean {
        gate.requireHeld()
        require(record.revision == expectedRevision + 1) { "claim transition must advance revision exactly once" }
        return auditedUpdateAll(
            predicate = {
                (table.tenantId eq record.tenantId) and
                        (table.id eq record.id) and
                        (table.revision eq expectedRevision)
            },
        ) {
            it[state] = record.state
            it[reviewKind] = record.reviewKind
            it[pendingFromState] = record.pendingFromState
            it[capacityReserved] = record.capacityReserved
            it[codeVerifier] = record.codeVerifier
            it[generationKeyVersion] = record.generationKeyVersion
            it[verificationKeyVersion] = record.verificationKeyVersion
            it[expiresAt] = record.expiresAt
            it[redemptionReferenceDigest] = record.redemptionReferenceDigest
            it[revision] = record.revision
        } == 1
    }
}

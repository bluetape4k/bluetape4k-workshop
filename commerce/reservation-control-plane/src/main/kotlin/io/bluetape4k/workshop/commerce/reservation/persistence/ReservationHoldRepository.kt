package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.time.Instant

/** owner, state, revision을 확인하는 hold mutation을 Exposed JDBC CAS update로 적용합니다. */
/** owner, state, revision을 확인하는 hold mutation을 Exposed JDBC CAS update로 적용합니다. */
@Repository
internal class ReservationHoldRepository: LongAuditableJdbcRepository<ReservationHoldRecord, ReservationHoldTable> {

    companion object: KLogging()
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = ReservationHoldTable

    /**
     * extractId은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: ReservationHoldRecord): Long = entity.id

    /**
     * toEntity은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): ReservationHoldRecord =
        ReservationHoldRecord(
            id = this[table.id].value,
            resourceId = this[table.resourceId].value,
            ownerDigest = this[table.ownerDigest],
            state = this[table.state],
            revision = this[table.revision],
            policyVersion = this[table.policyVersion],
            expiresAt = this[table.expiresAt],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    /**
     * create은 예약 제어면 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param resourceId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param policyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param expiresAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun create(
        resourceId: Long,
        ownerDigest: String,
        policyVersion: Long,
        expiresAt: Instant,
    ): ReservationHoldRecord {
        val id = table
            .insertAndGetId {
                it[table.resourceId] = resourceId
                it[table.ownerDigest] = ownerDigest
                it[table.state] = HoldState.HELD
                it[table.policyVersion] = policyVersion
                it[table.expiresAt] = expiresAt
            }.value

        log.debug { "reservation_hold_created holdId=$id resourceId=$resourceId policyVersion=$policyVersion" }
        return findById(id)
    }

    /**
     * transition은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        id: Long,
        ownerDigest: String,
        expectedRevision: Long,
        from: HoldState,
        to: HoldState,
    ): Boolean =
        auditedUpdateAll({
            (table.id eq id) and
                    (table.ownerDigest eq ownerDigest) and
                    (table.revision eq expectedRevision) and
                    (table.state eq from)
        }) {
            it[state] = to
            it[revision] = expectedRevision + 1
        } == 1

    /**
     * extend은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param expectedExpiresAt 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param newExpiresAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun extend(
        id: Long,
        ownerDigest: String,
        expectedRevision: Long,
        expectedExpiresAt: Instant,
        newExpiresAt: Instant,
    ): Boolean =
        auditedUpdateAll({
            (table.id eq id) and
                    (table.ownerDigest eq ownerDigest) and
                    (table.revision eq expectedRevision) and
                    (table.state eq HoldState.HELD) and
                    (table.expiresAt eq expectedExpiresAt)
        }) {
            it[expiresAt] = newExpiresAt
            it[revision] = expectedRevision + 1
        }.also { updated ->
            log.debug { "reservation_hold_extended holdId=$id expectedRevision=$expectedRevision updated=$updated" }
        } == 1

    /** bounded candidate를 반환합니다. 최종 global hold/offer ordering은 transaction service가 수행합니다. */
    /** bounded candidate를 반환합니다. 최종 global hold/offer ordering은 transaction service가 수행합니다. */
    fun expiredResourceCandidates(
        now: Instant,
        limit: Int,
    ): List<ExpiredResourceCandidate> {
        limit.requireInRange(1, 32, "limit")

        return table
            .selectAll()
            .where {
                (table.state eq HoldState.HELD) and
                        (table.expiresAt lessEq now)
            }
            .orderBy(table.expiresAt to SortOrder.ASC, table.id to SortOrder.ASC)
            .limit(limit * 4)
            .map {
                ExpiredResourceCandidate(
                    resourceId = it[table.resourceId].value,
                    expiresAt = it[table.expiresAt]
                )
            }
            .distinctBy { it.resourceId }
            .take(limit)
    }

    /**
     * expiredForResource은 예약 제어면의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param resourceId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun expiredForResource(
        resourceId: Long,
        now: Instant,
    ): List<ReservationHoldRecord> =
        table
            .selectAll()
            .where {
                (table.resourceId eq resourceId) and
                        (table.state eq HoldState.HELD) and
                        (table.expiresAt lessEq now)
            }
            .orderBy(table.expiresAt to SortOrder.ASC, table.id to SortOrder.ASC)
            .map { it.toEntity() }

}

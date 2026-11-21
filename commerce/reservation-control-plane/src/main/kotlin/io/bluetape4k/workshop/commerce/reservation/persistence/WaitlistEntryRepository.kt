package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant
/**
 * WaitlistEntryRecord은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property resourceId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property ownerDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property state 현재 좌석 예약·대기열 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property sequence 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property createdBy 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property createdAt 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property updatedBy 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property updatedAt 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class WaitlistEntryRecord(
    /**
     * `id`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `resourceId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId: Long,
    /**
     * `ownerDigest`은 예약 제어면에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerDigest: String,
    /**
     * `state`은 예약 제어면에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: WaitlistState,
    /**
     * `sequence`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val sequence: Long,
    /**
     * `revision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `createdBy`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    /**
     * `createdAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val createdAt: Instant? = null,
    /**
     * `updatedBy`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val updatedBy: String? = null,
    /**
     * `updatedAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val updatedAt: Instant? = null,
): Auditable, Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * resource별 waitlist의 FIFO sequence와 lifecycle state를 저장합니다.
 *
 * sequence allocation은 caller가 이미 resource lock을 보유한다고 가정합니다.
 * transition update는 owner와 revision을 계속 확인하므로 retry가 같은 entry를 두 번 advance하지 못합니다.
 */
@Repository
internal class WaitlistEntryRepository: LongAuditableJdbcRepository<WaitlistEntryRecord, WaitlistEntryTable> {

    companion object: KLogging() {
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */
        private const val OWNER_DIGEST_LENGTH = 64
    }
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = WaitlistEntryTable

    /**
     * extractId은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: WaitlistEntryRecord): Long = entity.id

    /**
     * toEntity은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): WaitlistEntryRecord =
        WaitlistEntryRecord(
            id = this[table.id].value,
            resourceId = this[table.resourceId].value,
            ownerDigest = this[table.ownerDigest],
            state = this[table.state],
            sequence = this[table.sequence],
            revision = this[table.revision],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    /**
     * caller는 같은 resource에 대한 join을 직렬화해야 합니다(보통 resource row lock으로 수행).
     * 이렇게 해야 FIFO sequence allocation과 insert가 caller의 PostgreSQL transaction 안에 머뭅니다.
     */
    fun join(
        resourceId: Long,
        ownerDigest: String,
    ): WaitlistEntryRecord {
        require(ownerDigest.length == OWNER_DIGEST_LENGTH) { "owner digest must be 64 characters" }

        val nextSequence = table
            .selectAll()
            .where { table.resourceId eq resourceId }
            .orderBy(table.sequence to SortOrder.DESC, table.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(table.sequence)
            ?.plus(1)
            ?: 1L

        val id = table
            .insertAndGetId {
                it[table.resourceId] = resourceId
                it[table.ownerDigest] = ownerDigest
                it[table.state] = WaitlistState.WAITING
                it[table.sequence] = nextSequence
            }.value

        log.debug { "waitlist_entry_joined entryId=$id resourceId=$resourceId sequence=$nextSequence" }
        return findById(id)
    }

    /**
     * snapshot은 예약 제어면의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    fun snapshot(
        id: Long,
        ownerDigest: String,
    ): WaitlistEntryRecord? =
        table
            .selectAll()
            .where { (table.id eq id) and (table.ownerDigest eq ownerDigest) }
            .singleOrNull()
            ?.toEntity()

    /**
     * snapshots은 예약 제어면의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param resourceId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun snapshots(resourceId: Long): List<WaitlistEntryRecord> =
        table
            .selectAll()
            .where { table.resourceId eq resourceId }
            .orderBy(table.sequence to SortOrder.ASC, table.id to SortOrder.ASC)
            .map { it.toEntity() }

    /** caller가 resource lock을 보유하는 동안 sequence와 id 기준으로 안정적인 FIFO head를 선택합니다. */
    /** caller가 resource lock을 보유하는 동안 sequence와 id 기준으로 안정적인 FIFO head를 선택합니다. */
    fun oldestWaiting(resourceId: Long): WaitlistEntryRecord? =
        table
            .selectAll()
            .where { (table.resourceId eq resourceId) and (table.state eq WaitlistState.WAITING) }
            .orderBy(table.sequence to SortOrder.ASC, table.id to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.toEntity()

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
        from: WaitlistState,
        to: WaitlistState,
    ): Boolean {
        val applied = auditedUpdateAll({
            (table.id eq id) and
                    (table.ownerDigest eq ownerDigest) and
                    (table.revision eq expectedRevision) and
                    (table.state eq from)
        }) {
            it[state] = to
            it[revision] = expectedRevision + 1
        } == 1
        
        log.debug {
            "waitlist_entry_transition entryId=$id expectedRevision=$expectedRevision from=$from to=$to applied=$applied"
        }
        return applied
    }
}

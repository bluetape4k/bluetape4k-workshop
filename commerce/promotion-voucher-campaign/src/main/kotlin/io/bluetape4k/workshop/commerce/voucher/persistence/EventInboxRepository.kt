package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.*

@Repository
/**
 * EventInboxRepository은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class EventInboxRepository(
    /**
     * `gate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val gate: DatabasePermitGate,
): LongAuditableJdbcRepository<EventInboxRecord, EventInboxTable> {

    companion object: KLogging() {
        /**
         * `TERMINAL_OUTCOMES`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        private val TERMINAL_OUTCOMES = setOf(InboxStatus.APPLIED, InboxStatus.IGNORED, InboxStatus.CONFLICT)
        /**
         * `BACKLOG_STATUSES`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        private val BACKLOG_STATUSES = listOf(InboxStatus.PENDING, InboxStatus.CLAIMED, InboxStatus.FAILED)
    }

    /**
     * `table`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    override val table = EventInboxTable
    /**
     * extractId은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param entity 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun extractId(entity: EventInboxRecord): Long = entity.id

/**
 * toEntity은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    override fun ResultRow.toEntity(): EventInboxRecord = EventInboxRecord(
        id = this[table.id].value,
        tenantId = this[table.tenantId],
        eventId = this[table.eventId],
        aggregateType = this[table.aggregateType],
        aggregateId = this[table.aggregateId],
        payloadDigest = this[table.payloadDigest],
        observedSequence = this[table.observedSequence],
        status = this[table.status],
        attempt = this[table.attempt],
        nextAttemptAt = this[table.nextAttemptAt],
        claimOwner = this[table.claimOwner],
        claimUntil = this[table.claimUntil],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt],
    )

    /**
     * insert은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param record 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun insert(record: EventInboxRecord): EventInboxRecord {
        gate.requireHeld()
        val id = table.insertAndGetId {
            it[tenantId] = record.tenantId
            it[eventId] = record.eventId
            it[aggregateType] = record.aggregateType
            it[aggregateId] = record.aggregateId
            it[payloadDigest] = record.payloadDigest
            it[observedSequence] = record.observedSequence
            it[status] = record.status
            it[attempt] = record.attempt
            it[nextAttemptAt] = record.nextAttemptAt
            it[claimOwner] = record.claimOwner
            it[claimUntil] = record.claimUntil
        }.value
        return findById(id)
    }

    /** tenant/event id별로 한 번 insert하고, unique-key race 뒤 durable winner를 반환합니다. */
    /** tenant/event id별로 한 번 insert하고, unique-key race 뒤 durable winner를 반환합니다. */
    fun insertIfAbsent(record: EventInboxRecord): InboxInsertResult {
        gate.requireHeld()
        val inserted =
            table.insertIgnore {
                it[tenantId] = record.tenantId
                it[eventId] = record.eventId
                it[aggregateType] = record.aggregateType
                it[aggregateId] = record.aggregateId
                it[payloadDigest] = record.payloadDigest
                it[observedSequence] = record.observedSequence
                it[status] = record.status
                it[attempt] = record.attempt
                it[nextAttemptAt] = record.nextAttemptAt
                it[claimOwner] = record.claimOwner
                it[claimUntil] = record.claimUntil
            }.insertedCount == 1

        val durable = checkNotNull(findEvent(record.tenantId, record.eventId))
        return InboxInsertResult(durable, inserted)
    }

    /**
     * findById은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param id 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun findById(id: Long): EventInboxRecord {
        gate.requireHeld()
        return table
            .selectAll()
            .where { table.id eq id }
            .single()
            .toEntity()
    }

    /**
     * findEvent은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun findEvent(
        tenantId: String,
        eventId: UUID,
    ): EventInboxRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.eventId eq eventId) }
            .singleOrNull()
            ?.toEntity()
    }

    /**
     * findBacklogPage은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun findBacklogPage(
        tenantId: String,
        afterId: Long?,
        limit: Int,
    ): List<EventInboxRecord> {
        gate.requireHeld()
        var predicate = (table.tenantId eq tenantId) and
                (table.status inList BACKLOG_STATUSES)

        afterId?.let {
            predicate = predicate and (table.id greater it)
        }

        return table.selectAll()
            .where { predicate }
            .orderBy(table.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toEntity() }
    }

    /** 다른 worker가 이미 소유한 row 뒤에서 기다리지 않고 due row 하나를 claim합니다. */
    /** 다른 worker가 이미 소유한 row 뒤에서 기다리지 않고 due row 하나를 claim합니다. */
    fun claimNext(
        now: Instant,
        owner: String,
        lease: Duration,
    ): EventInboxRecord? {
        gate.requireHeld()
        val row = table
            .selectAll()
            .where {
                ((table.status eq InboxStatus.PENDING) and (table.nextAttemptAt lessEq now)) or
                        ((table.status eq InboxStatus.CLAIMED) and (table.claimUntil lessEq now))
            }
            .orderBy(table.nextAttemptAt to SortOrder.ASC, table.id to SortOrder.ASC)
            .limit(1)
            .forUpdate(
                ForUpdateOption.PostgreSQL.ForUpdate(
                    ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED,
                ),
            ).singleOrNull() ?: return null

        val id = row[table.id].value
        table.update({ table.id eq id }) {
            it[status] = InboxStatus.CLAIMED
            it[claimOwner] = owner.take(128)
            it[claimUntil] = now.plus(lease)
        }
        return findById(id)
    }

    /** 관련 없는 backlog를 선택하지 않고 방금 insert한 row를 synchronous acceptance 용도로 claim합니다. */
    /** 관련 없는 backlog를 선택하지 않고 방금 insert한 row를 synchronous acceptance 용도로 claim합니다. */
    fun claimById(
        id: Long,
        now: Instant,
        owner: String,
        lease: Duration,
    ): EventInboxRecord {
        gate.requireHeld()
        val row = table
            .selectAll()
            .where { (table.id eq id) and (table.status eq InboxStatus.PENDING) }
            .forUpdate()
            .single()
        check(row[table.id].value == id)

        val updated = table
            .update({ (table.id eq id) and (table.status eq InboxStatus.PENDING) }) {
                it[status] = InboxStatus.CLAIMED
                it[claimOwner] = owner.take(128)
                it[claimUntil] = now.plus(lease)
            } == 1
        check(updated) { "pending inbox row changed before synchronous claim" }

        return findById(id)
    }

    /** 별도 authority table을 추가하지 않고 한 tenant/aggregate의 decision을 직렬화합니다. */
    /** 별도 authority table을 추가하지 않고 한 tenant/aggregate의 decision을 직렬화합니다. */
    fun lockAggregate(record: EventInboxRecord) {
        gate.requireHeld()
        val material = "${record.tenantId}:${record.aggregateType}:${record.aggregateId}"
        val digest = Digesters.SHA256.digestUtf8(material)
        val lockKey = ByteBuffer.wrap(digest).long
        TransactionManager.current().exec("SELECT pg_advisory_xact_lock($lockKey)")
    }

    /**
     * latestAppliedSequence은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param record 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun latestAppliedSequence(record: EventInboxRecord): Long? {
        gate.requireHeld()
        return table
            .selectAll()
            .where {
                (table.tenantId eq record.tenantId) and
                        (table.aggregateType eq record.aggregateType) and
                        (table.aggregateId eq record.aggregateId) and
                        (table.status eq InboxStatus.APPLIED)
            }
            .orderBy(table.observedSequence to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(table.observedSequence)
    }

    /**
     * complete은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun complete(
        id: Long,
        outcome: InboxStatus,
    ): EventInboxRecord {
        gate.requireHeld()
        require(outcome in TERMINAL_OUTCOMES) { "outcome must be terminal" }

        val updated = table
            .update({ (table.id eq id) and (table.status eq InboxStatus.CLAIMED) }) {
                it[status] = outcome
                it[claimOwner] = null
                it[claimUntil] = null
            } == 1
        check(updated) { "claimed inbox row changed before completion" }

        return findById(id)
    }

    /**
     * retry은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun retry(
        record: EventInboxRecord,
        now: Instant,
        nextAttemptAt: Instant,
        maxAttempts: Int,
    ): EventInboxRecord {
        gate.requireHeld()
        val nextAttempt = record.attempt + 1
        val nextStatus = if (nextAttempt >= maxAttempts) InboxStatus.FAILED else InboxStatus.PENDING
        val updated = table
            .update({ (table.id eq record.id) and (table.status eq InboxStatus.CLAIMED) }) {
                it[status] = nextStatus
                it[attempt] = nextAttempt
                it[EventInboxTable.nextAttemptAt] = if (nextStatus == InboxStatus.FAILED) now else nextAttemptAt
                it[claimOwner] = null
                it[claimUntil] = null
            } == 1

        check(updated) { "claimed inbox row changed before retry" }
        return findById(record.id)
    }
}

/**
 * InboxInsertResult은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class InboxInsertResult(
    /**
     * `record`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val record: EventInboxRecord,
    /**
     * `inserted`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val inserted: Boolean,
)

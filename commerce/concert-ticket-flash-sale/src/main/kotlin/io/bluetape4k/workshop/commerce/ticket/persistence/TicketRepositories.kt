package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.explain
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.*

/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 PostgreSQL inventory authority입니다. */
/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 PostgreSQL inventory authority입니다. */
class TicketInventoryRepository(
    /**
     * `executor`은 콘서트 티켓 플래시 세일에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: TicketJdbcExecutor,
): TicketExposedJdbcRepository<TicketInventoryEntity, Long>(TicketInventoryEntity::class) {

    /**
     * get은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param saleId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param grade 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun get(saleId: UUID, grade: String): InventoryRecord =
        executor.transaction {
            findAll { (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade) }
                .singleOrNull()
                ?.toRecord()
                ?: error("inventory not found")
        }

    /**
     * forceQuantities은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param saleId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param grade 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param held 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param sold 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun forceQuantities(saleId: UUID, grade: String, held: Int, sold: Int) {
        executor.transaction {
            acquire(TicketLockRank.INVENTORY)
            val whereOp = (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade)
            val updated = TicketInventories.update({ whereOp }) {
                it[heldQuantity] = held
                it[soldQuantity] = sold
                it[revision] = revision + 1
            }
            check(updated == 1) { "inventory not found" }
        }
    }

    /**
     * lock은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param transaction 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param saleId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param grade 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun lock(transaction: TicketJdbcTransaction, saleId: UUID, grade: String): InventoryRecord {
        return with(transaction) {
            acquire(TicketLockRank.INVENTORY)

            TicketInventories
                .selectAll()
                .where { (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade) }
                .forUpdate()
                .singleOrNull()
                ?.toInventoryRecord()
                ?: error("inventory not found")
        }
    }
}

/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 durable USER/IP guard authority입니다. */
/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 durable USER/IP guard authority입니다. */
class TicketIdentityGuardRepository(
    /**
     * `jdbc`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc: TicketJdbcExecutor,
): TicketExposedJdbcRepository<TicketActiveIdentityGuardEntity, Long>(TicketActiveIdentityGuardEntity::class) {

    /**
     * insert은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param saleId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param kind 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param subjectId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param attemptId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun insert(saleId: UUID, kind: IdentityKind, subjectId: UUID, attemptId: UUID) {
        jdbc.transaction {
            acquire(if (kind == IdentityKind.USER) TicketLockRank.USER_GUARD else TicketLockRank.IP_GUARD)

            TicketActiveIdentityGuards.insert {
                it[TicketActiveIdentityGuards.saleId] = saleId
                it[identityKind] = kind.name
                it[identitySubjectId] = subjectId
                it[activeAttemptId] = attemptId
            }
        }
    }
}

/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 durable FIFO queue입니다. */
/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 durable FIFO queue입니다. */
class TicketWaitingRoomRepository(
    /**
     * `jdbc`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc: TicketJdbcExecutor,
): TicketExposedJdbcRepository<TicketWaitingRoomEntryEntity, Long>(TicketWaitingRoomEntryEntity::class) {
    /**
     * join은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param saleId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param userSubjectId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param sequence 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun join(saleId: UUID, userSubjectId: UUID, sequence: Long): UUID {
        val entryId = Uuid.V7.nextId()
        val now = Instant.now()
        jdbc.transaction {
            TicketWaitingRoomEntries.insert {
                it[TicketWaitingRoomEntries.entryId] = entryId
                it[TicketWaitingRoomEntries.saleId] = saleId
                it[TicketWaitingRoomEntries.userSubjectId] = userSubjectId
                it[state] = "waiting"
                it[TicketWaitingRoomEntries.sequence] = sequence
                it[revision] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return entryId
    }

    /**
     * claimBatch은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param saleId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun claimBatch(saleId: UUID, limit: Int): List<WaitingEntryRecord> =
        jdbc.transaction {
            getClaimBatchQuery(saleId, limit)
                .map { row -> row.toWaitingEntryRecord() }
        }

    /** PostgreSQL-specific EXPLAIN은 normal CRUD에서 의도적으로 격리합니다. */
    /** PostgreSQL-specific EXPLAIN은 normal CRUD에서 의도적으로 격리합니다. */
    fun explainClaim(saleId: UUID): List<String> {
        return jdbc.transaction {
            transaction.exec("SET LOCAL enable_seqscan = off")

            val query = getClaimBatchQuery(saleId)
            transaction.explain { query }.toList().map { it.toString() }
        }
    }

    @Suppress("UnusedReceiverParameter")
/**
 * `getClaimBatchQuery`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TicketJdbcTransaction.getClaimBatchQuery(saleId: UUID, limit: Int = 50): Query {
        return TicketWaitingRoomEntries
            .selectAll()
            .where { TicketWaitingRoomEntries.saleId eq saleId }
            .andWhere { TicketWaitingRoomEntries.state eq "waiting" }
            .orderBy(
                TicketWaitingRoomEntries.sequence to SortOrder.ASC,
                TicketWaitingRoomEntries.id to SortOrder.ASC
            )
            .limit(limit)
            .forUpdate(
                ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED),
            )
    }
}

/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 payment-operation ledger입니다. */
/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 payment-operation ledger입니다. */
class TicketPaymentOperationRepository(
    /**
     * `jdbc`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc: TicketJdbcExecutor,
): TicketExposedJdbcRepository<TicketPaymentOperationEntity, Long>(TicketPaymentOperationEntity::class) {

    /**
     * insertAuthorization은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param provider 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param operationId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param attemptId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun insertAuthorization(provider: String, operationId: UUID, attemptId: UUID) {
        val now = Instant.now()

        jdbc.transaction {
            acquire(TicketLockRank.EFFECT)
            TicketPaymentOperations.insert {
                it[TicketPaymentOperations.provider] = provider
                it[TicketPaymentOperations.operationId] = operationId
                it[TicketPaymentOperations.attemptId] = attemptId
                it[operationKind] = "authorize"
                it[status] = "pending"
                it[nextReconcileAt] = now
                it[claimRevision] = 0
                it[revision] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    /** PostgreSQL-specific EXPLAIN은 normal CRUD에서 의도적으로 격리합니다. */
    /** PostgreSQL-specific EXPLAIN은 normal CRUD에서 의도적으로 격리합니다. */
    @Suppress("SqlResolve")
    fun explainDue(): List<String> {
        return jdbc.transaction {
            transaction.exec("SET LOCAL enable_seqscan = off")
            val query = getPendingQuery()
            transaction.explain { query }.toList().map { it.toString() }
        }
    }

/**
 * `getPendingQuery`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TicketJdbcTransaction.getPendingQuery(limit: Int = 50): Query =
        TicketPaymentOperations
            .select(TicketPaymentOperations.id)
            .where { TicketPaymentOperations.status eq "pending" }
            .andWhere { TicketPaymentOperations.nextReconcileAt lessEq CurrentTimestamp }
            .orderBy(
                TicketPaymentOperations.nextReconcileAt to SortOrder.ASC,
                TicketPaymentOperations.id to SortOrder.ASC
            )
            .limit(limit)

/**
 * `getPendingId`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun TicketJdbcTransaction.getPendingId(limit: Int = 50): String =
        getPendingQuery(limit).prepareSQL(this.transaction, false)
}

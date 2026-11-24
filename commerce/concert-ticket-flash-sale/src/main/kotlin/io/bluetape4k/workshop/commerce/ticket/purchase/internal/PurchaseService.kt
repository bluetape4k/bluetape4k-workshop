package io.bluetape4k.workshop.commerce.ticket.purchase.internal

import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.ticket.admission.api.TransactionalAdmissionCommands
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.domain.TicketDisposition
import io.bluetape4k.workshop.commerce.ticket.domain.TicketEffectOutcome
import io.bluetape4k.workshop.commerce.ticket.domain.TicketState
import io.bluetape4k.workshop.commerce.ticket.domain.ticketTransition
import io.bluetape4k.workshop.commerce.ticket.domain.transition
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.InventoryRecord
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketActiveIdentityGuards
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketBuyerSaleStates
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectOperations
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectReceipts
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketHttpIdempotencies
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentitySubjects
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketInventories
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketInventoryRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketLockRank
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketOrders
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperations
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPurchaseAttemptEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPurchaseAttempts
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketSales
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketTickets
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyPaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyResult
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyTicketOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.AuthorizationRequested
import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PaymentOutcomeCommands
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseCommands
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseQueries
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseSnapshot
import io.bluetape4k.workshop.commerce.ticket.purchase.api.StartPurchase
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePurchaseAuthority
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.io.Serial
import java.io.Serializable
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.util.*

/**
 * `PurchaseEventPublisher`은 콘서트 티켓 플래시 세일 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
fun interface PurchaseEventPublisher {
    /**
     * publish은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param event 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun publish(event: AuthorizationRequested)
}

/**
 * ActivePurchaseExists은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class ActivePurchaseExists: IllegalStateException("active_purchase_exists") {
    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * InventoryUnavailable은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class InventoryUnavailable: IllegalStateException("inventory_unavailable") {
    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * PurchaseLimitExceeded은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class PurchaseLimitExceeded: IllegalStateException("purchase_limit_exceeded") {
    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * PurchaseNotFound은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class PurchaseNotFound: IllegalStateException("purchase_not_found") {
    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * InvalidPurchaseCommand은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class InvalidPurchaseCommand: IllegalArgumentException("invalid_purchase_command") {
    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * IdempotencyOwnershipLost은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class IdempotencyOwnershipLost: IllegalStateException("idempotency_ownership_lost") {
    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** Bluetape4k [TicketExposedJdbcRepository] 위에 구현한 purchase aggregate persistence입니다. */
/** Bluetape4k [TicketExposedJdbcRepository] 위에 구현한 purchase aggregate persistence입니다. */
class TicketPurchaseRepository(
    /**
     * `jdbcExecutor`은 콘서트 티켓 플래시 세일에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val jdbcExecutor: TicketJdbcExecutor,
    /**
     * `sale`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val sale: SalePurchaseAuthority,
    /**
     * `admission`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val admission: TransactionalAdmissionCommands,
    /**
     * `clock`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
): TicketExposedJdbcRepository<TicketPurchaseAttemptEntity, UUID>(TicketPurchaseAttemptEntity::class) {

    companion object: KLogging()

    /**
     * `inventories`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val inventories = TicketInventoryRepository(jdbcExecutor)

    /**
     * owned은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param attemptId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param buyerSubjectId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun owned(attemptId: UUID, buyerSubjectId: UUID): PurchaseSnapshot? =
        jdbcExecutor.transaction {
            TicketPurchaseAttemptEntity.find {
                (TicketPurchaseAttempts.id eq attemptId) and
                        (TicketPurchaseAttempts.userSubjectId eq buyerSubjectId)
            }
                .singleOrNull()
                ?.toRecord()
                ?.snapshot()
        }

    /**
     * start은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun start(command: StartPurchase): PurchaseCommit = jdbcExecutor.transaction {
        val now = clock.instant()
        val policy = sale.requireOpen(this, command.policy, now)
        if (command.quantity !in 1..policy.maxQuantity) throw PurchaseLimitExceeded()

        lockIdempotency(command)?.let {
            return@transaction PurchaseCommit(loadSnapshot(it), null)
        }

        lockGuard(command.policy.saleId, IdentityKind.USER, command.buyerSubjectId, null)
        lockGuard(command.policy.saleId, IdentityKind.IP, command.ipSubjectId, null)

        lockBuyer(
            saleId = command.policy.saleId,
            buyerId = command.buyerSubjectId,
            policyVersion = policy.policyVersion,
            quantity = command.quantity,
            perUserLimit = policy.perUserLimit
        )

        val inventory = inventories.lock(this, command.policy.saleId, command.grade)
        if (inventory.available < command.quantity) throw InventoryUnavailable()

        insertAttempt(command, policy.policyVersion, now + policy.holdSeconds.seconds(), now)
        admission.consume(this, command.grant)
        applyInventoryDelta(inventory, heldDelta = command.quantity)
        insertGuard(command.policy.saleId, IdentityKind.USER, command.buyerSubjectId, command.attemptId, now)
        insertGuard(command.policy.saleId, IdentityKind.IP, command.ipSubjectId, command.attemptId, now)
        bindIdempotency(command.idempotencyOwnerId, command.attemptId, now)
        insertPaymentOperation(command, now)

        PurchaseCommit(
            snapshot = PurchaseSnapshot(command.attemptId, PurchaseState.INVENTORY_HELD, 0, now),
            event = AuthorizationRequested(Uuid.V7.nextId(), command.attemptId, command.authorizationOperationId),
        )
    }

    /**
     * cancel은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun cancel(command: CancelPurchase): PurchaseSnapshot = jdbcExecutor.transaction {
        val candidate = findAttempt(command.attemptId) ?: throw PurchaseNotFound()
        if (candidate.buyerSubjectId != command.buyerSubjectId) throw PurchaseNotFound()

        lockSale(candidate.saleId)
        lockGuard(candidate.saleId, IdentityKind.USER, candidate.buyerSubjectId, candidate.attemptId)
        lockGuard(candidate.saleId, IdentityKind.IP, candidate.ipSubjectId, candidate.attemptId)
        lockExistingBuyer(candidate.saleId, candidate.buyerSubjectId)

        val inventory = inventories.lock(this, candidate.saleId, candidate.grade)
        acquire(TicketLockRank.ATTEMPT_ORDER)
        val attempt = lockAttempt(command.attemptId, command.buyerSubjectId)

        when (attempt.state) {
            PurchaseState.INVENTORY_HELD -> {
                applyInventoryDelta(inventory, heldDelta = -attempt.quantity)
                updateAttemptState(attempt.attemptId, PurchaseState.CANCELLED)
                releaseGuards(attempt.attemptId)
                attempt.snapshot(PurchaseState.CANCELLED, clock.instant())
            }
            PurchaseState.PAYMENT_AUTHORIZING, PurchaseState.RECONCILIATION_REQUIRED -> {
                updateAttemptState(attempt.attemptId, PurchaseState.CANCELLATION_REQUESTED)
                attempt.snapshot(PurchaseState.CANCELLATION_REQUESTED, clock.instant())
            }
            else                         ->
                attempt.snapshot(attempt.state, attempt.updatedAt)
        }
    }

    /**
     * applyPaymentOutcome은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun applyPaymentOutcome(command: ApplyPaymentOutcome): ApplyResult = jdbcExecutor.transaction {
        val candidate = findAttempt(command.attemptId) ?: return@transaction ApplyResult.STALE

        lockSale(candidate.saleId)
        lockGuard(candidate.saleId, IdentityKind.USER, candidate.buyerSubjectId, candidate.attemptId)
        lockGuard(candidate.saleId, IdentityKind.IP, candidate.ipSubjectId, candidate.attemptId)
        lockExistingBuyer(candidate.saleId, candidate.buyerSubjectId)

        val inventory = inventories.lock(this, candidate.saleId, candidate.grade)
        acquire(TicketLockRank.ATTEMPT_ORDER)

        val attempt = lockAttempt(candidate.attemptId, candidate.buyerSubjectId)
        acquire(TicketLockRank.EFFECT)
        if (!ownsPaymentClaim(command)) return@transaction ApplyResult.STALE

        val change = transition(attempt.state, command.outcome)
        applyInventoryDelta(inventory, change.heldDelta * attempt.quantity, change.soldDelta * attempt.quantity)
        updateAttemptState(attempt.attemptId, change.next)

        if (change.releaseGuards) releaseGuards(attempt.attemptId)
        if (change.soldDelta > 0) incrementPurchased(attempt.saleId, attempt.buyerSubjectId, attempt.quantity)
        if (change.next == PurchaseState.APPROVED || change.next == PurchaseState.REFUND_PENDING) {
            insertOrder(
                attempt = attempt,
                authorizationOperationId = command.operationId,
                disposition = change.ticketDisposition ?: TicketDisposition.PENDING
            )
        }
        completePaymentOperation(command, change.next)
        ApplyResult.APPLIED
    }

    /**
     * applyTicketOutcome은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot = jdbcExecutor.transaction {
        acquire(TicketLockRank.ATTEMPT_ORDER)
        val order = TicketOrders.selectAll()
            .where { TicketOrders.id eq command.orderId }
            .forUpdate()
            .single()
        val attemptId = order[TicketOrders.attemptId]
        val attempt = findByIdOrNull(attemptId)?.toRecord() ?: throw PurchaseNotFound()

        acquire(TicketLockRank.EFFECT)
        val operation = TicketEffectOperations.selectAll()
            .where {
                (TicketEffectOperations.operationId eq command.operationId) and
                        (TicketEffectOperations.orderId eq command.orderId) and
                        (TicketEffectOperations.status eq "claimed") and
                        (TicketEffectOperations.claimToken eq command.claimToken) and
                        (TicketEffectOperations.claimRevision eq command.expectedRevision)
            }
            .forUpdate()
            .singleOrNull() ?: return@transaction attempt.snapshot()

        val ticket = TicketTickets.selectAll()
            .where { TicketTickets.orderId eq command.orderId }
            .forUpdate()
            .single()

        val current = TicketState.entries.single { it.code == ticket[TicketTickets.state] }
        val next = ticketTransition(current, command.outcome)
        val now = clock.instant()

        TicketTickets.update({ TicketTickets.id eq ticket[TicketTickets.id] }) {
            it[state] = next.code
            if (next == TicketState.ISSUED) {
                it[externalTicketDigest] = effectDigest(command.operationId, command.disposition)
            }
            it[revision] = revision + 1
            it[updatedAt] = now
        }
        TicketEffectOperations.update({ TicketEffectOperations.id eq operation[TicketEffectOperations.id] }) {
            it[status] = when (command.outcome) {
                TicketEffectOutcome.SUCCEEDED              -> "succeeded"
                TicketEffectOutcome.RETRYABLE_FAILURE      -> "retry"
                TicketEffectOutcome.RETRY_BUDGET_EXHAUSTED -> "quarantined"
            }
            it[claimToken] = null
            it[claimUntil] = null
            it[revision] = revision + 1
            it[updatedAt] = now
        }
        if (command.outcome == TicketEffectOutcome.SUCCEEDED) {
            TicketOrders.update({ TicketOrders.id eq command.orderId }) {
                it[ticketDisposition] = command.disposition.code
                it[revision] = revision + 1
                it[updatedAt] = now
            }
            TicketEffectReceipts.insert {
                it[consumerName] = "ticket-effect-worker"
                it[operationId] = command.operationId
                it[payloadDigest] = effectDigest(command.operationId, command.disposition)
                it[createdAt] = now
            }
        }
        attempt.snapshot()
    }

/**
 * `lockIdempotency`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TicketJdbcTransaction.lockIdempotency(command: StartPurchase): UUID? {
        acquire(TicketLockRank.IDEMPOTENCY)
        val row = TicketHttpIdempotencies.selectAll()
            .where {
                (TicketHttpIdempotencies.id eq command.idempotencyOwnerId) and
                        (TicketHttpIdempotencies.principalSubjectId eq command.buyerSubjectId)
            }
            .forUpdate()
            .singleOrNull()
            ?: throw IdempotencyOwnershipLost()

        if (row[TicketHttpIdempotencies.status] != "in_progress") {
            throw IdempotencyOwnershipLost()
        }

        return row[TicketHttpIdempotencies.attemptId]?.also {
            if (it != command.attemptId) {
                throw IdempotencyOwnershipLost()
            }
        }
    }

    /** All transactions that mutate inventory must acquire the sale root first. */
    /** All transactions that mutate inventory must acquire the sale root first. */
    private fun TicketJdbcTransaction.lockSale(saleId: UUID) {
        acquire(TicketLockRank.SALE)
        TicketSales.selectAll()
            .where { TicketSales.id eq saleId }
            .forUpdate()
            .singleOrNull()
            ?: throw PurchaseNotFound()
    }

/**
 * `lockGuard`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TicketJdbcTransaction.lockGuard(
        saleId: UUID,
        kind: IdentityKind,
        subjectId: UUID,
        expectedAttempt: UUID?,
    ) {
        acquire(if (kind == IdentityKind.USER) TicketLockRank.USER_GUARD else TicketLockRank.IP_GUARD)
        transaction.exec("SELECT pg_advisory_xact_lock(${guardAdvisoryKey(saleId, kind, subjectId)})") {
            check(it.next())
        }
        val subjectKind = TicketIdentitySubjects.selectAll()
            .where { TicketIdentitySubjects.id eq subjectId }
            .singleOrNull()
            ?.get(TicketIdentitySubjects.identityKind)

        if (subjectKind != kind.name) throw InvalidPurchaseCommand()

        val activeAttempt = TicketActiveIdentityGuards.selectAll()
            .where {
                (TicketActiveIdentityGuards.saleId eq saleId) and
                        (TicketActiveIdentityGuards.identityKind eq kind.name) and
                        (TicketActiveIdentityGuards.identitySubjectId eq subjectId)
            }
            .singleOrNull()
            ?.get(TicketActiveIdentityGuards.activeAttemptId)

        if (activeAttempt != null && activeAttempt != expectedAttempt) throw ActivePurchaseExists()
    }

/**
 * `lockBuyer`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TicketJdbcTransaction.lockBuyer(
        saleId: UUID,
        buyerId: UUID,
        policyVersion: Long,
        quantity: Int,
        perUserLimit: Int,
    ) {
        acquire(TicketLockRank.BUYER)
        val exists = TicketBuyerSaleStates.selectAll()
            .where { (TicketBuyerSaleStates.saleId eq saleId) and (TicketBuyerSaleStates.userSubjectId eq buyerId) }
            .empty()

        if (exists) {
            TicketBuyerSaleStates.insert {
                it[TicketBuyerSaleStates.saleId] = saleId
                it[userSubjectId] = buyerId
                it[TicketBuyerSaleStates.policyVersion] = policyVersion
                it[purchasedQuantity] = 0
                it[revision] = 0
            }
        }
        val purchased = TicketBuyerSaleStates.selectAll()
            .where { (TicketBuyerSaleStates.saleId eq saleId) and (TicketBuyerSaleStates.userSubjectId eq buyerId) }
            .forUpdate()
            .single()[TicketBuyerSaleStates.purchasedQuantity]

        if (purchased + quantity > perUserLimit) {
            throw PurchaseLimitExceeded()
        }
    }

/**
 * `lockExistingBuyer`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TicketJdbcTransaction.lockExistingBuyer(saleId: UUID, buyerId: UUID) {
        acquire(TicketLockRank.BUYER)
        val rows = TicketBuyerSaleStates.selectAll()
            .where { (TicketBuyerSaleStates.saleId eq saleId) and (TicketBuyerSaleStates.userSubjectId eq buyerId) }
            .forUpdate()
            .singleOrNull()
        check(rows != null) { "buyer state not found" }
    }

/**
 * `insertAttempt`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TicketJdbcTransaction.insertAttempt(
        command: StartPurchase,
        policyVersion: Long,
        holdDeadline: Instant,
        now: Instant,
    ) {
        acquire(TicketLockRank.ATTEMPT_ORDER)
        val purchaseAttempt = TicketPurchaseAttemptEntity.new(id = command.attemptId) {
            saleId = command.policy.saleId
            userSubjectId = command.buyerSubjectId
            ipSubjectId = command.ipSubjectId
            grade = command.grade
            quantity = command.quantity
            this.policyVersion = policyVersion
            state = PurchaseState.INVENTORY_HELD.code
            this.holdDeadline = holdDeadline
            authorizationOperationId = command.authorizationOperationId
            revision = 0
            createdAt = now
            updatedAt = now
        }
        save(purchaseAttempt)
    }

    @Suppress("UnusedReceiverParameter")
/**
 * `applyInventoryDelta`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TicketJdbcTransaction.applyInventoryDelta(
        inventory: InventoryRecord,
        heldDelta: Int,
        soldDelta: Int = 0,
    ) {
        /**
         * `whereOp`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val whereOp = {
            (TicketInventories.saleId eq inventory.saleId) and
                    (TicketInventories.grade eq inventory.grade) and
                    (TicketInventories.revision eq inventory.revision)
        }
        /**
         * `updatedCount`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val updatedCount = TicketInventories.update(where = whereOp) {
            it[heldQuantity] = heldQuantity + heldDelta
            it[soldQuantity] = soldQuantity + soldDelta
            it[revision] = revision + 1
        }
        check(updatedCount == 1) { "inventory revision changed while locked" }
    }

/**
 * `insertPaymentOperation`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TicketJdbcTransaction.insertPaymentOperation(command: StartPurchase, now: Instant) {
        acquire(TicketLockRank.EFFECT)
        TicketPaymentOperations.insert {
            it[provider] = "fake"
            it[operationId] = command.authorizationOperationId
            it[attemptId] = command.attemptId
            it[operationKind] = "authorize"
            it[status] = "pending"
            it[nextReconcileAt] = now
            it[claimRevision] = 0
            it[revision] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    /**
     * ownsPaymentClaim은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun ownsPaymentClaim(command: ApplyPaymentOutcome): Boolean =
        TicketPaymentOperations.select(TicketPaymentOperations.id)
            .where {
                (TicketPaymentOperations.operationId eq command.operationId) and
                        (TicketPaymentOperations.attemptId eq command.attemptId) and
                        (TicketPaymentOperations.status eq "claimed") and
                        (TicketPaymentOperations.claimToken eq command.claimToken) and
                        (TicketPaymentOperations.claimRevision eq command.claimRevision)
            }
            .forUpdate()
            .singleOrNull() != null

    /**
     * completePaymentOperation은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param nextState 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun completePaymentOperation(command: ApplyPaymentOutcome, nextState: PurchaseState) {
        val status = when (command.outcome) {
            PaymentOutcome.UNKNOWN -> "unknown"
            PaymentOutcome.APPROVED -> "approved"
            PaymentOutcome.DECLINED -> "declined"
            else                   -> error("unsupported provider outcome")
        }
        val now = clock.instant()
        val whereOp = {
            (TicketPaymentOperations.operationId eq command.operationId) and
                    (TicketPaymentOperations.claimToken eq command.claimToken) and
                    (TicketPaymentOperations.claimRevision eq command.claimRevision)
        }
        val affected = TicketPaymentOperations.update(whereOp) {
            it[TicketPaymentOperations.status] = status
            it[nextReconcileAt] = if (status == "unknown") now + 1.seconds() else null
            it[claimToken] = null
            it[claimUntil] = null
            it[revision] = revision + 1
            it[updatedAt] = now
        }
        check(affected == 1) { "payment claim changed while locked: $nextState" }
    }

    /**
     * incrementPurchased은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param saleId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param buyerId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param quantity 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun incrementPurchased(saleId: UUID, buyerId: UUID, quantity: Int) {
        val whereOp = {
            (TicketBuyerSaleStates.saleId eq saleId) and
                    (TicketBuyerSaleStates.userSubjectId eq buyerId)
        }
        val affected = TicketBuyerSaleStates.update(whereOp) {
            it[purchasedQuantity] = purchasedQuantity + quantity
            it[revision] = revision + 1
        }
        check(affected == 1)
    }

    /**
     * insertOrder은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param attempt 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param authorizationOperationId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param disposition 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun insertOrder(attempt: AttemptRecord, authorizationOperationId: UUID, disposition: TicketDisposition) {
        val now = clock.instant()
        val orderId = Uuid.V7.nextId()
        val refundOperationId = when (disposition) {
            TicketDisposition.NEVER_ISSUED -> stableOperationId("refund", orderId)
            else                           -> null
        }
        TicketOrders.insert {
            it[id] = orderId
            it[TicketOrders.attemptId] = attempt.attemptId
            it[saleId] = attempt.saleId
            it[grade] = attempt.grade
            it[quantity] = attempt.quantity
            it[state] = if (refundOperationId != null) "refund_pending" else "paid"
            it[ticketDisposition] = disposition.code
            it[TicketOrders.authorizationOperationId] = authorizationOperationId
            it[TicketOrders.refundOperationId] = refundOperationId
            it[revision] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }
        if (refundOperationId != null) {
            TicketPaymentOperations.insert {
                it[provider] = "fake"
                it[operationId] = refundOperationId
                it[attemptId] = attempt.attemptId
                it[TicketPaymentOperations.orderId] = orderId
                it[operationKind] = "refund"
                it[status] = "pending"
                it[nextReconcileAt] = now
                it[claimRevision] = 0
                it[revision] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        } else {
            TicketTickets.insert {
                it[TicketTickets.orderId] = orderId
                it[state] = "issue_pending"
                it[revision] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
            TicketEffectOperations.insert {
                it[effectKind] = "issue"
                it[operationId] = stableOperationId("issue", orderId)
                it[TicketEffectOperations.orderId] = orderId
                it[status] = "pending"
                it[claimRevision] = 0
                it[revision] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    /**
     * insertGuard은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun insertGuard(
        saleId: UUID,
        kind: IdentityKind,
        subjectId: UUID,
        attemptId: UUID,
        now: Instant,
    ) {
        TicketActiveIdentityGuards.insert {
            it[TicketActiveIdentityGuards.saleId] = saleId
            it[identityKind] = kind.name
            it[identitySubjectId] = subjectId
            it[activeAttemptId] = attemptId
            it[createdAt] = now
        }
    }

    /**
     * bindIdempotency은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param ownerId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param attemptId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun bindIdempotency(ownerId: Long, attemptId: UUID, now: Instant) {
        val whereOp = {
            (TicketHttpIdempotencies.id eq ownerId) and
                    (TicketHttpIdempotencies.status eq "in_progress") and
                    TicketHttpIdempotencies.attemptId.isNull()
        }
        val affected = TicketHttpIdempotencies.update(where = whereOp) {
            it[TicketHttpIdempotencies.attemptId] = attemptId
            it[updatedAt] = now
        }

        if (affected != 1) {
            throw IdempotencyOwnershipLost()
        }
    }

    /**
     * findAttempt은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param attemptId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun findAttempt(attemptId: UUID): AttemptRecord? =
        findById(attemptId).orElse(null)?.toRecord()

    /**
     * lockAttempt은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param attemptId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param buyerId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun lockAttempt(attemptId: UUID, buyerId: UUID): AttemptRecord {
        return TicketPurchaseAttemptEntity
            .find {
                (TicketPurchaseAttempts.id eq attemptId) and (TicketPurchaseAttempts.userSubjectId eq buyerId)
            }
            .singleOrNull()
            ?.toRecord()
            ?: throw PurchaseNotFound()
    }

    /**
     * loadSnapshot은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param attemptId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun loadSnapshot(attemptId: UUID): PurchaseSnapshot =
        findAttempt(attemptId)?.snapshot() ?: throw IdempotencyOwnershipLost()

    /**
     * updateAttemptState은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param attemptId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param state 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun updateAttemptState(attemptId: UUID, state: PurchaseState) {
        val affected = TicketPurchaseAttempts.update({ TicketPurchaseAttempts.id eq attemptId }) {
            it[TicketPurchaseAttempts.state] = state.code
            it[revision] = revision + 1
            it[updatedAt] = clock.instant()
        }
        check(affected == 1)
    }

    /**
     * releaseGuards은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param attemptId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun releaseGuards(attemptId: UUID) {
        TicketActiveIdentityGuards.deleteWhere { activeAttemptId eq attemptId }
    }

/**
 * `toRecord`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TicketPurchaseAttemptEntity.toRecord(): AttemptRecord =
        AttemptRecord(
            attemptId = id.value,
            saleId = saleId,
            buyerSubjectId = userSubjectId,
            ipSubjectId = ipSubjectId,
            grade = grade,
            quantity = quantity,
            state = PurchaseState.entries.single { it.code == state },
            revision = revision,
            updatedAt = updatedAt,
        )

    /**
     * stableOperationId은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param kind 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param orderId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun stableOperationId(kind: String, orderId: UUID): UUID =
        UUID.nameUUIDFromBytes("ticket-$kind-v1:$orderId".toUtf8Bytes())

    /**
     * effectDigest은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param operationId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param disposition 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun effectDigest(operationId: UUID, disposition: TicketDisposition): ByteArray =
        Digesters.SHA256.digestUtf8("ticket-effect-v1:$operationId:${disposition.code}")

    /**
     * guardAdvisoryKey은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param saleId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param kind 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param subjectId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun guardAdvisoryKey(saleId: UUID, kind: IdentityKind, subjectId: UUID): Long {
        val digest = Digesters.SHA256.digestUtf8(
            "ticket-active-guard-v1\u0000$saleId\u0000${kind.name}\u0000$subjectId",
        )
        return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    }

    /**
     * PurchaseCommit은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class PurchaseCommit(val snapshot: PurchaseSnapshot, val event: AuthorizationRequested?): Serializable {
        companion object {
            @Serial
            /**
             * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * AttemptRecord은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private data class AttemptRecord(
        /**
         * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val attemptId: UUID,
        /**
         * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val saleId: UUID,
        /**
         * `buyerSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val buyerSubjectId: UUID,
        /**
         * `ipSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val ipSubjectId: UUID,
        /**
         * `grade`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val grade: String,
        /**
         * `quantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val quantity: Int,
        /**
         * `state`은 콘서트 티켓 플래시 세일에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val state: PurchaseState,
        /**
         * `revision`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val revision: Long,
        /**
         * `updatedAt`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val updatedAt: Instant,
    ): Serializable {
        /**
         * snapshot은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param nextState 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param at 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun snapshot(nextState: PurchaseState = state, at: Instant = updatedAt): PurchaseSnapshot =
            PurchaseSnapshot(
                attemptId = attemptId,
                state = nextState,
                revision = if (nextState == state) revision else revision + 1,
                updatedAt = at,
            )

        companion object {
            @Serial
            /**
             * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * `InventoryRecord`은 콘서트 티켓 플래시 세일에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val InventoryRecord.available: Int get() = total - held - sold
}

/** application service입니다. 모든 database work는 [TicketPurchaseRepository]에 위임합니다. */
/** application service입니다. 모든 database work는 [TicketPurchaseRepository]에 위임합니다. */
@Transactional
class PurchaseService(
    jdbcExecutor: TicketJdbcExecutor,
    sale: SalePurchaseAuthority,
    admission: TransactionalAdmissionCommands,
    clock: Clock,
    /**
     * `events`은 콘서트 티켓 플래시 세일에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val events: PurchaseEventPublisher,
    /**
     * `purchases`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val purchases: TicketPurchaseRepository = TicketPurchaseRepository(jdbcExecutor, sale, admission, clock),
): PurchaseCommands,
   PurchaseQueries,
   PaymentOutcomeCommands {

    companion object: KLogging()

    /**
     * start은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun start(command: StartPurchase): PurchaseSnapshot {
        validate(command)
        val committed = purchases.start(command)
        committed.event?.let(events::publish)
        return committed.snapshot
    }

    /**
     * cancel은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun cancel(command: CancelPurchase): PurchaseSnapshot =
        purchases.cancel(command)

    /**
     * applyPaymentOutcome은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun applyPaymentOutcome(command: ApplyPaymentOutcome): ApplyResult =
        purchases.applyPaymentOutcome(command)

    /**
     * applyTicketOutcome은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot =
        purchases.applyTicketOutcome(command)

    @Transactional(readOnly = true)
    /**
     * owned은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param attemptId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param buyerSubjectId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun owned(attemptId: UUID, buyerSubjectId: UUID): PurchaseSnapshot? =
        purchases.owned(attemptId, buyerSubjectId)

    /**
     * validate은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validate(command: StartPurchase) {
        val isInvalid = command.idempotencyOwnerId <= 0 ||
                command.grade.isBlank() ||
                command.quantity <= 0 ||
                command.grant.saleId != command.policy.saleId ||
                command.grant.buyerSubjectId != command.buyerSubjectId ||
                command.grant.policyVersion != command.policy.policyVersion ||
                command.grant.attemptId != command.attemptId

        if (isInvalid) {
            throw InvalidPurchaseCommand()
        }
    }
}

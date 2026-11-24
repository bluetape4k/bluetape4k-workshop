package io.bluetape4k.workshop.commerce.ticket.purchase.internal

import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.domain.TicketDisposition
import io.bluetape4k.workshop.commerce.ticket.domain.refundTransition
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketActiveIdentityGuards
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketBuyerSaleStates
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectReceipts
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketInventories
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketOrderEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketOrders
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperationEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperations
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPurchaseAttempts
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serial
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `RefundOutcome`은 처리 결과입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
enum class RefundOutcome {
    /**
     * `RefundOutcome.SUCCEEDED`은 외부 provider 또는 내부 작업의 성공 결과가 확정된 값입니다.
     */
    SUCCEEDED,
    /**
     * `RefundOutcome.UNKNOWN`은 외부 결과가 아직 확정되지 않아 성공이나 실패로 단정할 수 없는 값입니다.
     */
    UNKNOWN,
    /**
     * `RefundOutcome.FAILED`은 처리가 실패했으며 재시도 가능 여부를 별도 정책으로 판단해야 하는 값입니다.
     */
    FAILED
}

/**
 * `RefundProvider`은 콘서트 티켓 플래시 세일 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
fun interface RefundProvider {
    /**
     * refund은 콘서트 티켓 판매의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun refund(operationId: UUID): RefundOutcome

    /**
     * lookup은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun lookup(operationId: UUID): RefundOutcome? = null
}

/**
 * FakeRefundProvider은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class FakeRefundProvider: RefundProvider {
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    private val outcomes = ConcurrentHashMap<UUID, RefundOutcome>()
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val configured = ConcurrentHashMap<UUID, RefundOutcome>()
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val calls = ConcurrentHashMap<UUID, AtomicInteger>()

    /**
     * refund은 콘서트 티켓 판매의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun refund(operationId: UUID): RefundOutcome {
        calls.computeIfAbsent(operationId) { AtomicInteger() }.incrementAndGet()
        return outcomes.computeIfAbsent(operationId) { configured[operationId] ?: RefundOutcome.SUCCEEDED }
    }

    /**
     * lookup은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun lookup(operationId: UUID): RefundOutcome? = outcomes[operationId]

    /**
     * succeed은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun succeed(operationId: UUID) {
        configured[operationId] = RefundOutcome.SUCCEEDED
    }

    /**
     * refundCount은 콘서트 티켓 판매의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun refundCount(operationId: UUID): Int = calls[operationId]?.get() ?: 0
}
/**
 * RefundClaim은 콘서트 티켓 판매에서 전달되는 불변 데이터 모델입니다.
 * @property operationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property orderId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property token 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


data class RefundClaim(
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId: UUID,
    /**
     * `orderId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId: UUID,
    /**
     * `token`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val token: UUID,
    /**
     * `revision`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
): Serializable {
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
 * toRefundClaim은 콘서트 티켓 판매의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param token 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
 */
fun ResultRow.toRefundClaim(operationId: UUID, token: UUID): RefundClaim =
    RefundClaim(
        operationId = operationId,
        orderId = this[TicketPaymentOperations.orderId].checkNotNull("orderId"),
        token = token,
        revision = this[TicketPaymentOperations.claimRevision]
    )
/**
 * RefundOperationRepository은 콘서트 티켓 판매에서 사용하는 업무 객체입니다.
 * @property jdbc 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


class RefundOperationRepository(
    /**
     * `jdbc`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc: TicketJdbcExecutor,
    /**
     * `clock`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
): TicketExposedJdbcRepository<TicketPaymentOperationEntity, Long>(TicketPaymentOperationEntity::class) {

    companion object: KLogging()

    /**
     * claim은 콘서트 티켓 판매의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param ttl 업무 처리에 필요한 ttl 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun claim(operationId: UUID, ttl: Duration): RefundClaim? = jdbc.transaction {
        val now = clock.instant()
        val token = Uuid.V7.nextId()

        val whereOp = {
            (TicketPaymentOperations.operationId eq operationId) and
                    (TicketPaymentOperations.operationKind eq "refund") and
                    ((TicketPaymentOperations.status eq "pending") or
                            ((TicketPaymentOperations.status eq "unknown") and (TicketPaymentOperations.nextReconcileAt lessEq now)) or
                            ((TicketPaymentOperations.status eq "claimed") and (TicketPaymentOperations.claimUntil lessEq now)))
        }
        val claimed = TicketPaymentOperations.update(whereOp) {
            it[status] = "claimed"
            it[claimToken] = token
            it[claimRevision] = claimRevision + 1
            it[claimUntil] = now.plus(ttl)
            it[updatedAt] = now
        }
        if (claimed != 1) return@transaction null

        TicketPaymentOperations.selectAll()
            .where {
                (TicketPaymentOperations.operationId eq operationId) and (TicketPaymentOperations.claimToken eq token)
            }
            .single()
            .toRefundClaim(operationId, token)
    }
}
/**
 * TicketRefundRepository은 콘서트 티켓 판매에서 사용하는 업무 객체입니다.
 * @property jdbc 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


class TicketRefundRepository(
    /**
     * `jdbc`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc: TicketJdbcExecutor,
    /**
     * `clock`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
): TicketExposedJdbcRepository<TicketOrderEntity, UUID>(TicketOrderEntity::class) {

    companion object: KLogging()

    /**
     * apply은 콘서트 티켓 판매 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun apply(claim: RefundClaim, outcome: RefundOutcome): Boolean = jdbc.transaction {
        val operation = TicketPaymentOperations.selectAll()
            .where {
                (TicketPaymentOperations.operationId eq claim.operationId) and
                        (TicketPaymentOperations.operationKind eq "refund") and
                        (TicketPaymentOperations.status eq "claimed") and
                        (TicketPaymentOperations.claimToken eq claim.token) and
                        (TicketPaymentOperations.claimRevision eq claim.revision)
            }
            .forUpdate()
            .singleOrNull() ?: return@transaction false

        val now = clock.instant()
        if (outcome != RefundOutcome.SUCCEEDED) {
            val whereOp = { TicketPaymentOperations.id eq operation[TicketPaymentOperations.id] }
            TicketPaymentOperations.update(whereOp) {
                it[status] = if (outcome == RefundOutcome.UNKNOWN) "unknown" else "failed"
                it[nextReconcileAt] = if (outcome == RefundOutcome.UNKNOWN) now + 1.seconds() else null
                it[claimToken] = null
                it[claimUntil] = null
                it[revision] = revision + 1
                it[updatedAt] = now
            }
            return@transaction true
        }
        val order = TicketOrders.selectAll().where { TicketOrders.id eq claim.orderId }.forUpdate().single()
        val attemptId = order[TicketOrders.attemptId]

        val attempt = TicketPurchaseAttempts.selectAll()
            .where { TicketPurchaseAttempts.id eq attemptId }
            .forUpdate()
            .single()

        val state = PurchaseState.entries.single { it.code == attempt[TicketPurchaseAttempts.state] }
        val disposition = TicketDisposition.entries.single { it.code == order[TicketOrders.ticketDisposition] }
        val change = refundTransition(state, disposition)

        check(change.next == PurchaseState.REFUNDED) { "ticket is not safe to restock" }
        val quantity = order[TicketOrders.quantity]
        val saleId = order[TicketOrders.saleId]
        val grade = order[TicketOrders.grade]

        val affected = TicketInventories.update({
            (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade)
        }) {
            it[soldQuantity] = soldQuantity + change.soldDelta * quantity
            it[revision] = revision + 1
        }
        check(affected == 1) { "ticket is not safe to restock" }

        TicketPurchaseAttempts.update({ TicketPurchaseAttempts.id eq attemptId }) {
            it[TicketPurchaseAttempts.state] = change.next.code
            it[revision] = revision + 1
            it[updatedAt] = now
        }
        TicketOrders.update({ TicketOrders.id eq claim.orderId }) {
            it[TicketOrders.state] = "refunded"
            it[revision] = revision + 1
            it[updatedAt] = now
        }
        TicketBuyerSaleStates.update({
            (TicketBuyerSaleStates.saleId eq saleId) and
                    (TicketBuyerSaleStates.userSubjectId eq attempt[TicketPurchaseAttempts.userSubjectId])
        }) {
            it[purchasedQuantity] = purchasedQuantity + -quantity
            it[revision] = revision + 1
        }
        TicketActiveIdentityGuards.deleteWhere { activeAttemptId eq attemptId }
        TicketEffectReceipts.insert {
            it[consumerName] = "refund-service"
            it[operationId] = claim.operationId
            it[payloadDigest] = Digesters.SHA256.digestUtf8("refund-effect-v1:${claim.operationId}")
            it[createdAt] = now
        }
        TicketPaymentOperations.update({ TicketPaymentOperations.id eq operation[TicketPaymentOperations.id] }) {
            it[status] = "succeeded"
            it[nextReconcileAt] = null
            it[claimToken] = null
            it[claimUntil] = null
            it[revision] = revision + 1
            it[updatedAt] = now
        }
        true
    }
}

/**
 * lookup-first refund reconciliation입니다. 모든 DB work는 Exposed repository boundary 안에 머뭅니다.
 * @property provider 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property claimTtl 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property operations 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property refunds 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class RefundService(
    jdbc: TicketJdbcExecutor,
    /**
     * `provider`은 콘서트 티켓 플래시 세일에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val provider: RefundProvider,
    clock: Clock = Clock.systemUTC(),
    /**
     * `claimTtl`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val claimTtl: Duration = 5.seconds(),
    /**
     * `operations`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val operations: RefundOperationRepository = RefundOperationRepository(jdbc, clock),
    /**
     * `refunds`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val refunds: TicketRefundRepository = TicketRefundRepository(jdbc, clock),
) {
    companion object: KLogging()

    /**
     * run은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun run(operationId: UUID): Boolean {
        log.info { "refunc started for $operationId" }

        val claim = operations.claim(operationId, claimTtl) ?: return false
        val outcome = provider.lookup(operationId) ?: provider.refund(operationId)

        return refunds.apply(claim, outcome)
            .also { applied ->
                log.info { "refund_outcome_applied operationId=$operationId outcome=$outcome applied=$applied" }
            }
    }
}

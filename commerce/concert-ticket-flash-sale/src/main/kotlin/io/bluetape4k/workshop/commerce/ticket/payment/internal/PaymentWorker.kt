package io.bluetape4k.workshop.commerce.ticket.payment.internal

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperationEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperations
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyPaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyResult
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PaymentOutcomeCommands
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serial
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.util.*
/**
 * PaymentClaim은 콘서트 티켓 판매에서 전달되는 불변 데이터 모델입니다.
 * @property operationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property attemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property claimToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


data class PaymentClaim(
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId: UUID,
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val attemptId: UUID,
    /**
     * `claimToken`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val claimToken: UUID,
    /**
     * `revision`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Bluetape4k [TicketExposedJdbcRepository] 위에 구현한 fenced claim입니다.
 * @property jdbc 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class PaymentOperationClaimRepository(
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
     * @param claimTtl 업무 처리에 필요한 claimTtl 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun claim(operationId: UUID, claimTtl: Duration): PaymentClaim? = jdbc.transaction {
        val now = clock.instant()
        val token = Uuid.V7.nextId()

        val whereOp = {
            (TicketPaymentOperations.operationId eq operationId) and
                    (TicketPaymentOperations.operationKind eq "authorize") and
                    ((TicketPaymentOperations.status eq "pending") or
                            ((TicketPaymentOperations.status eq "unknown") and (TicketPaymentOperations.nextReconcileAt lessEq now)) or
                            ((TicketPaymentOperations.status eq "claimed") and (TicketPaymentOperations.claimUntil lessEq now))
                            )
        }
        val claimed = TicketPaymentOperations.update(whereOp) {
            it[status] = "claimed"
            it[claimToken] = token
            it[claimRevision] = claimRevision + 1
            it[claimUntil] = now.plus(claimTtl)
            it[updatedAt] = now
        }

        if (claimed != 1) return@transaction null

        TicketPaymentOperations
            .selectAll()
            .where { TicketPaymentOperations.operationId eq operationId }
            .andWhere { TicketPaymentOperations.claimToken eq token }
            .singleOrNull()
            ?.let {
                PaymentClaim(
                    operationId,
                    it[TicketPaymentOperations.attemptId],
                    token,
                    it[TicketPaymentOperations.claimRevision]
                )
            }
    }
}

/**
 * PostgreSQL에서 짧게 claim하고, lock 밖에서 provider를 호출한 뒤 fencing으로 적용합니다.
 * @property outcomes 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property provider 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property claimTtl 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property claims 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class PaymentWorker(
    jdbc: TicketJdbcExecutor,
    /**
     * `outcomes`은 콘서트 티켓 플래시 세일에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val outcomes: PaymentOutcomeCommands,
    /**
     * `provider`은 콘서트 티켓 플래시 세일에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val provider: PaymentProvider,
    /**
     * `claimTtl`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val claimTtl: Duration = 5.seconds(),
    clock: Clock = Clock.systemUTC(),
    /**
     * `claims`은 콘서트 티켓 플래시 세일에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val claims: PaymentOperationClaimRepository = PaymentOperationClaimRepository(jdbc, clock),
) {
    companion object: KLogging()
    
    /**
     * claim은 콘서트 티켓 판매의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun claim(operationId: UUID): PaymentClaim? = claims.claim(operationId, claimTtl)

    /**
     * apply은 콘서트 티켓 판매 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun apply(
        claim: PaymentClaim,
        outcome: PaymentOutcome,
    ): ApplyResult {
        val outcome = ApplyPaymentOutcome(
            attemptId = claim.attemptId,
            operationId = claim.operationId,
            claimToken = claim.claimToken,
            claimRevision = claim.revision,
            outcome = outcome,
        )
        return outcomes.applyPaymentOutcome(outcome)
    }

    /**
     * run은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operationId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun run(operationId: UUID): ApplyResult? {
        val claim = claim(operationId) ?: return null

        val outcome = provider.lookup(operationId)
            ?: try {
                provider.authorize(operationId)
            } catch (failure: PaymentTimeout) {
                log.warn(failure) { "payment_authorization_timed_out operationId=$operationId" }
                PaymentOutcome.UNKNOWN
            }

        return apply(claim, outcome).also {
            log.info { "payment_outcome_applied operationId=$operationId, outcome=$outcome result=$it" }
        }
    }
}

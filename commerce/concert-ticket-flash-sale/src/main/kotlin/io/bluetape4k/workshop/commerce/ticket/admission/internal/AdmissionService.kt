package io.bluetape4k.workshop.commerce.ticket.admission.internal

import io.bluetape4k.workshop.commerce.ticket.admission.api.AdmissionCommands
import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.admission.api.TransactionalAdmissionCommands
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketAdmissionGrantEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketAdmissionGrants
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serial
import java.time.Clock
import java.time.Instant

/**
 * AdmissionExpired은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class AdmissionExpired: IllegalStateException("admission_expired") {
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

/** Bluetape4k Exposed JDBC repository로 구현한 admission authority입니다. */
/** Bluetape4k Exposed JDBC repository로 구현한 admission authority입니다. */
class TicketAdmissionGrantRepository:
    TicketExposedJdbcRepository<TicketAdmissionGrantEntity, Long>(TicketAdmissionGrantEntity::class) {
    /**
     * consume은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param transaction 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun consume(transaction: TicketJdbcTransaction, command: ConsumeGrant, now: Instant) {
        val whereOp = {
            (TicketAdmissionGrants.saleId eq command.saleId) and
                    (TicketAdmissionGrants.grantNonce eq command.grantNonce) and
                    (TicketAdmissionGrants.buyerSubjectId eq command.buyerSubjectId) and
                    (TicketAdmissionGrants.policyVersion eq command.policyVersion) and
                    TicketAdmissionGrants.consumedAttemptId.isNull() and
                    (TicketAdmissionGrants.expiresAt greater now)
        }

        val updated = TicketAdmissionGrants.update(whereOp) {
            it[consumedAttemptId] = command.attemptId
            it[consumedAt] = now
        }
        if (updated != 1) throw AdmissionExpired()
    }
}

/** buyer에 귀속된 grant를 단일 조건부 Exposed update로 소비합니다. */
/** buyer에 귀속된 grant를 단일 조건부 Exposed update로 소비합니다. */
class AdmissionService(
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
    /**
     * `grants`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val grants: TicketAdmissionGrantRepository = TicketAdmissionGrantRepository(),
): AdmissionCommands, TransactionalAdmissionCommands {
    /**
     * consume은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun consume(command: ConsumeGrant) {
        jdbc.transaction {
            consume(this, command)
        }
    }

    /**
     * consume은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param transaction 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun consume(transaction: TicketJdbcTransaction, command: ConsumeGrant) {
        grants.consume(transaction, command, clock.instant())
    }
}

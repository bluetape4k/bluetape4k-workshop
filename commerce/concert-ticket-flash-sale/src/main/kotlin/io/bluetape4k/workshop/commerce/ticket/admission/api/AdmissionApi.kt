package io.bluetape4k.workshop.commerce.ticket.admission.api

import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import java.io.Serializable
import java.util.*

/**
 * purchase transaction 안에서 소비되는 1회용 waiting-room grant입니다.
 *
 * Grant는 “대기실을 통과했다”는 admission 증거일 뿐 inventory 권리가 아닙니다.
 * `saleId`, `policyVersion`, buyer/IP subject와 purchase attempt를 묶어 purchase가
 * PostgreSQL에서 sale policy를 재검증할 때 사용합니다. 같은 grant 재사용은 service가
 * nonce와 attempt를 확인해 거절합니다.
 * ```kotlin
 * admissionCommands.consume(
 *     ConsumeGrant(saleId, grantNonce, buyerId, policyVersion, attemptId),
 * )
 * ```
 * @property saleId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property grantNonce 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property buyerSubjectId 호출 주체를 나타내는 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보나 비밀값은 저장·로그에 남기지 않습니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property attemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */
data class ConsumeGrant(
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId: UUID,
    /**
     * `grantNonce`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val grantNonce: UUID,
    /**
     * `buyerSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val buyerSubjectId: UUID,
    /**
     * `policyVersion`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val attemptId: UUID,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * purchase transaction과 admission grant 소비를 연결하는 공개 boundary입니다.
 * 구현체는 Redis lease를 직접 노출하지 않고, grant 소비가 실패하면 purchase도
 * commit하지 않는 transaction 경계를 보장해야 합니다.
 */
fun interface AdmissionCommands {
    /**
     * `consume`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = consume(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun consume(command: ConsumeGrant)
}

/**
 * admission 구현을 노출하지 않고 internal transaction 참여 지점을 제공합니다.
 * 일반 caller는 [AdmissionCommands]를 사용하고, purchase module만 동일한
 * [TicketJdbcTransaction]에 참여시키기 위해 이 API를 사용합니다.
 */
fun interface TransactionalAdmissionCommands {
    /**
     * `consume`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = consume(transaction = transaction, command = command)
     * ```
     * @param transaction 요청 경계에서 검증된 `transaction`입니다.
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun consume(
        transaction: TicketJdbcTransaction,
        command: ConsumeGrant,
    )
}

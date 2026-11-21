package io.bluetape4k.workshop.commerce.ticket.domain

import java.io.Serializable

/**
 * PostgreSQL에 저장되는 안정적인 sale lifecycle code입니다.
 * @property code 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
enum class SaleState(val code: String) {
    /** 아직 고객에게 노출되지 않은 초안입니다. */
    /** 아직 고객에게 노출되지 않은 초안입니다. */
    DRAFT("draft"),
    /** 시작 시각을 예약했지만 아직 구매를 받지 않습니다. */
    /** 시작 시각을 예약했지만 아직 구매를 받지 않습니다. */
    SCHEDULED("scheduled"),
    /** admission과 purchase를 허용하는 판매 상태입니다. */
    /** admission과 purchase를 허용하는 판매 상태입니다. */
    OPEN("open"),
    /** 운영자가 신규 purchase를 잠시 막은 상태입니다. 기존 effect는 별도 복구합니다. */
    /** 운영자가 신규 purchase를 잠시 막은 상태입니다. 기존 effect는 별도 복구합니다. */
    SUSPENDED("suspended"),
    /** 판매 창구를 닫아 신규 purchase를 허용하지 않는 terminal 상태입니다. */
    /** 판매 창구를 닫아 신규 purchase를 허용하지 않는 terminal 상태입니다. */
    CLOSED("closed"),
}

/** sale lifecycle을 위한 명시적인 operator 또는 scheduler command입니다. */
/** sale lifecycle을 위한 명시적인 operator 또는 scheduler command입니다. */
enum class SaleCommand {
    /** DRAFT를 SCHEDULED로 만들고 판매 시간 정책을 등록합니다. */
    /** DRAFT를 SCHEDULED로 만들고 판매 시간 정책을 등록합니다. */
    SCHEDULE,
    /** 예약된 sale을 OPEN으로 전환합니다. */
    /** 예약된 sale을 OPEN으로 전환합니다. */
    OPEN,
    /** 신규 purchase를 막고 기존 reconciliation은 계속 처리합니다. */
    /** 신규 purchase를 막고 기존 reconciliation은 계속 처리합니다. */
    SUSPEND,
    /** 일시 중지된 sale을 다시 OPEN으로 전환합니다. */
    /** 일시 중지된 sale을 다시 OPEN으로 전환합니다. */
    RESUME,
    /** sale을 닫고 새 inventory hold를 금지합니다. */
    /** sale을 닫고 새 inventory hold를 금지합니다. */
    CLOSE
}

/**
 * PostgreSQL에 저장되는 안정적인 purchase lifecycle code입니다.
 * @property code 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
enum class PurchaseState(val code: String) {
    /** inventory를 확보했지만 payment 승인을 기다립니다. */
    /** inventory를 확보했지만 payment 승인을 기다립니다. */
    INVENTORY_HELD("inventory_held"),
    /** provider authorization 요청이 진행 중입니다. */
    /** provider authorization 요청이 진행 중입니다. */
    PAYMENT_AUTHORIZING("payment_authorizing"),
    /** provider 결과가 모호해 operator/provider reconciliation이 필요합니다. */
    /** provider 결과가 모호해 operator/provider reconciliation이 필요합니다. */
    RECONCILIATION_REQUIRED("reconciliation_required"),
    /** 고객 또는 system이 payment 취소를 요청했습니다. */
    /** 고객 또는 system이 payment 취소를 요청했습니다. */
    CANCELLATION_REQUESTED("cancellation_requested"),
    /** payment가 승인되고 ticket effect를 진행할 수 있습니다. */
    /** payment가 승인되고 ticket effect를 진행할 수 있습니다. */
    APPROVED("approved"),
    /** payment가 거절되어 inventory를 반환할 수 있습니다. */
    /** payment가 거절되어 inventory를 반환할 수 있습니다. */
    DECLINED("declined"),
    /** 취소와 inventory 반환이 완료되었습니다. */
    /** 취소와 inventory 반환이 완료되었습니다. */
    CANCELLED("cancelled"),
    /** hold 시간이 지나 자동으로 만료되었습니다. */
    /** hold 시간이 지나 자동으로 만료되었습니다. */
    EXPIRED("expired"),
    /** 환불 요청이 provider 처리 중입니다. */
    /** 환불 요청이 provider 처리 중입니다. */
    REFUND_PENDING("refund_pending"),
    /** 환불 성공이 확인되었습니다. */
    /** 환불 성공이 확인되었습니다. */
    REFUNDED("refunded"),
    /** 환불 결과가 모호해 임의 재시도 대신 격리되었습니다. */
    /** 환불 결과가 모호해 임의 재시도 대신 격리되었습니다. */
    REFUND_QUARANTINED("refund_quarantined"),
}

/** 일치하는 operation이 fenced 상태일 때만 해석되는 provider outcome입니다. */
/** 일치하는 operation이 fenced 상태일 때만 해석되는 provider outcome입니다. */
enum class PaymentOutcome {
    /** provider가 결제를 승인했습니다. operation fencing이 먼저 통과해야 합니다. */
    /** provider가 결제를 승인했습니다. operation fencing이 먼저 통과해야 합니다. */
    APPROVED,
    /** provider가 결제를 거절했습니다. */
    /** provider가 결제를 거절했습니다. */
    DECLINED,
    /** timeout 등으로 provider의 최종 결과를 아직 알 수 없습니다. */
    /** timeout 등으로 provider의 최종 결과를 아직 알 수 없습니다. */
    UNKNOWN,
    /** provider에 취소를 요청했지만 최종 결과를 기다립니다. */
    /** provider에 취소를 요청했지만 최종 결과를 기다립니다. */
    CANCELLATION_REQUESTED,
    /** authorization 시간이 지나 만료되었습니다. */
    /** authorization 시간이 지나 만료되었습니다. */
    EXPIRED
}

/**
 * 발급된 ticket이 inventory restock을 허용할 수 있는지에 대한 durable proof입니다.
 * @property code 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
enum class TicketDisposition(val code: String) {
    /** ticket effect 결과가 아직 확정되지 않아 inventory를 반환할 수 없습니다. */
    /** ticket effect 결과가 아직 확정되지 않아 inventory를 반환할 수 없습니다. */
    PENDING("pending"),
    /** 외부 ticket을 발급하지 않았음이 확인되어 inventory를 반환할 수 있습니다. */
    /** 외부 ticket을 발급하지 않았음이 확인되어 inventory를 반환할 수 있습니다. */
    NEVER_ISSUED("never_issued"),
    /** 외부 ticket 발급이 완료되어 inventory가 sold로 남아야 합니다. */
    /** 외부 ticket 발급이 완료되어 inventory가 sold로 남아야 합니다. */
    ISSUED("issued"),
    /** 발급된 ticket이 회수되어 정책상 restock이 가능함을 증명합니다. */
    /** 발급된 ticket이 회수되어 정책상 restock이 가능함을 증명합니다. */
    REVOKED("revoked"),
}

/**
 * 안정적인 external-ticket effect lifecycle입니다.
 * @property code 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
enum class TicketState(val code: String) {
    /** 발급 effect가 아직 실행되지 않았습니다. */
    /** 발급 effect가 아직 실행되지 않았습니다. */
    ISSUE_PENDING("issue_pending"),
    /** 일시적 오류로 발급 effect를 재시도할 수 있습니다. */
    /** 일시적 오류로 발급 effect를 재시도할 수 있습니다. */
    ISSUE_RETRY("issue_retry"),
    /** 외부 ticket이 발급되었습니다. */
    /** 외부 ticket이 발급되었습니다. */
    ISSUED("issued"),
    /** 회수 effect가 진행 중입니다. */
    /** 회수 effect가 진행 중입니다. */
    REVOKE_PENDING("revoke_pending"),
    /** 외부 ticket 회수가 완료되었습니다. */
    /** 외부 ticket 회수가 완료되었습니다. */
    REVOKED("revoked"),
    /** 재시도 예산을 소진하거나 안전하게 분류할 수 없어 격리되었습니다. */
    /** 재시도 예산을 소진하거나 안전하게 분류할 수 없어 격리되었습니다. */
    QUARANTINED("quarantined"),
}

/** 단일 external ticket effect의 결정적 결과입니다. */
/** 단일 external ticket effect의 결정적 결과입니다. */
enum class TicketEffectOutcome {
    /** 외부 effect가 성공하고 durable 상태가 갱신되었습니다. */
    /** 외부 effect가 성공하고 durable 상태가 갱신되었습니다. */
    SUCCEEDED,
    /** 일시적 provider 오류로 같은 operation을 다시 시도할 수 있습니다. */
    /** 일시적 provider 오류로 같은 operation을 다시 시도할 수 있습니다. */
    RETRYABLE_FAILURE,
    /** 재시도 예산을 소진해 operator 판단이 필요합니다. */
    /** 재시도 예산을 소진해 operator 판단이 필요합니다. */
    RETRY_BUDGET_EXHAUSTED
}

/**
 * purchase transition과 함께 commit해야 하는 inventory 및 guard effect입니다.
 * @property next 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property heldDelta 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property soldDelta 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property releaseGuards 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property ticketDisposition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */
data class PurchaseTransition(
    /**
     * `next`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val next: PurchaseState,
    /**
     * `heldDelta`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val heldDelta: Int,
    /**
     * `soldDelta`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val soldDelta: Int,
    /**
     * `releaseGuards`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val releaseGuards: Boolean,
    /**
     * `ticketDisposition`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val ticketDisposition: TicketDisposition? = null,
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

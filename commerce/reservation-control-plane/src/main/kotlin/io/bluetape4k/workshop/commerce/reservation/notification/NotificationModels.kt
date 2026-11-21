package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant

/**
 * NotificationChannel은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class NotificationChannel {
    /**
     * `NotificationChannel.IN_APP`은 NotificationChannel 업무 상태의 `IN_APP` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    IN_APP,
}

/**
 * NotificationDeliveryStatus은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class NotificationDeliveryStatus {
    /**
     * `NotificationDeliveryStatus.PENDING`은 NotificationDeliveryStatus 업무 상태의 `PENDING` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    PENDING,
    /**
     * `NotificationDeliveryStatus.IN_FLIGHT`은 NotificationDeliveryStatus 업무 상태의 `IN_FLIGHT` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    IN_FLIGHT,
    /**
     * `NotificationDeliveryStatus.RETRYING`은 NotificationDeliveryStatus 업무 상태의 `RETRYING` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    RETRYING,
    /**
     * `NotificationDeliveryStatus.DELIVERED`은 NotificationDeliveryStatus 업무 상태의 `DELIVERED` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    DELIVERED,
    /**
     * `NotificationDeliveryStatus.EXHAUSTED`은 NotificationDeliveryStatus 업무 상태의 `EXHAUSTED` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    EXHAUSTED,
}

/**
 * NotificationFailureCode은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class NotificationFailureCode {
    /**
     * `NotificationFailureCode.FAKE_TRANSIENT`은 NotificationFailureCode 업무 상태의 `FAKE_TRANSIENT`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    FAKE_TRANSIENT,
}

/**
 * NotificationRequest은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class NotificationRequest(
    /**
     * `deliveryId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val deliveryId: String,
    /**
     * `channel`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val channel: NotificationChannel,
    /**
     * `templateCode`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val templateCode: String,
    /**
     * `aggregateId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId: String,
): Serializable {
    init {
        deliveryId.requireNotBlank("deliveryId")
        templateCode.requireNotBlank("templateCode")
        aggregateId.requireNotBlank("aggregateId")
    }

    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
    }
}

/**
 * NotificationDelivery은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class NotificationDelivery(
    /**
     * `deliveryId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val deliveryId: String,
    /**
     * `channel`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val channel: NotificationChannel,
    /**
     * `templateCode`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val templateCode: String,
    /**
     * `aggregateId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId: String,
    /**
     * `status`은 예약 제어면에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: NotificationDeliveryStatus,
    /**
     * `attemptCount`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val attemptCount: Int,
    /**
     * `nextAttemptAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val nextAttemptAt: Instant?,
    /**
     * `claimOwner`은 예약 제어면에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val claimOwner: String?,
    /**
     * `claimUntil`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val claimUntil: Instant?,
    /**
     * `failureCode`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val failureCode: NotificationFailureCode?,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
    }
}

/**
 * FinalizeDisposition은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class FinalizeDisposition {
    /**
     * `FinalizeDisposition.APPLIED`은 FinalizeDisposition 업무 상태의 `APPLIED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    APPLIED,
    /**
     * `FinalizeDisposition.NOT_FOUND`은 FinalizeDisposition 업무 상태의 `NOT_FOUND` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답
     * 분기를 선택하는 기준으로 사용합니다.
     */
    NOT_FOUND,
    /**
     * `FinalizeDisposition.STALE_CLAIM`은 FinalizeDisposition 업무 상태의 `STALE_CLAIM` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    STALE_CLAIM,
    /**
     * `FinalizeDisposition.TERMINAL`은 FinalizeDisposition 업무 상태의 `TERMINAL` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답
     * 분기를 선택하는 기준으로 사용합니다.
     */
    TERMINAL,
}

/**
 * RedriveDisposition은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class RedriveDisposition {
    /**
     * `RedriveDisposition.APPLIED`은 RedriveDisposition 업무 상태의 `APPLIED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    APPLIED,
    /**
     * `RedriveDisposition.ALREADY_APPLIED`은 RedriveDisposition 업무 상태의 `ALREADY_APPLIED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    ALREADY_APPLIED,
    /**
     * `RedriveDisposition.NOT_FOUND`은 RedriveDisposition 업무 상태의 `NOT_FOUND` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답
     * 분기를 선택하는 기준으로 사용합니다.
     */
    NOT_FOUND,
    /**
     * `RedriveDisposition.NOT_EXHAUSTED`은 RedriveDisposition 업무 상태의 `NOT_EXHAUSTED` 단계입니다. 허용된 상태
     * 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    NOT_EXHAUSTED,
}

/** duplicate acceptance와 retryable failure를 구분하는 provider result vocabulary입니다. */
/** duplicate acceptance와 retryable failure를 구분하는 provider result vocabulary입니다. */
internal sealed interface ProviderSendResult: Serializable {

    /**
     * Accepted은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data object Accepted: ProviderSendResult {
        /**
         * readResolve은 예약 제어면 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        private fun readResolve(): Any = Accepted
    }

    /**
     * Duplicate은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data object Duplicate: ProviderSendResult {
        /**
         * readResolve은 예약 제어면 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        private fun readResolve(): Any = Duplicate
    }

    /**
     * RetryableFailure은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class RetryableFailure(
        /**
         * `code`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val code: NotificationFailureCode,
    ): ProviderSendResult {
        companion object {
            /**
             * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID = 1L
        }
    }
}

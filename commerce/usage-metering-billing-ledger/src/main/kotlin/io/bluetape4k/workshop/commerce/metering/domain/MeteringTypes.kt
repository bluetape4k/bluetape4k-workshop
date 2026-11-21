package io.bluetape4k.workshop.commerce.metering.domain

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank

/**
 * `TENANT_ID_MAX_LENGTH`은 사용량 과금·정산에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
 *
 * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
 */
private const val TENANT_ID_MAX_LENGTH = 64
/**
 * `METER_CODE_MAX_LENGTH`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private const val METER_CODE_MAX_LENGTH = 64
/**
 * `SOURCE_SYSTEM_MAX_LENGTH`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val SOURCE_SYSTEM_MAX_LENGTH = 64
/**
 * `SOURCE_EVENT_ID_MAX_LENGTH`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
 *
 * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
 */
private const val SOURCE_EVENT_ID_MAX_LENGTH = 128
/**
 * `IDEMPOTENCY_KEY_MAX_LENGTH`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val IDEMPOTENCY_KEY_MAX_LENGTH = 256
/**
 * TenantId은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property value 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@JvmInline
value class TenantId(val value: String) {
    init {
        requireBoundedIdentifier(value, "tenantId", TENANT_ID_MAX_LENGTH)
    }
}
/**
 * MeterCode은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property value 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@JvmInline
value class MeterCode(val value: String) {
    init {
        requireBoundedIdentifier(value, "meterCode", METER_CODE_MAX_LENGTH)
    }
}
/**
 * SourceSystem은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property value 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@JvmInline
value class SourceSystem(val value: String) {
    init {
        requireBoundedIdentifier(value, "sourceSystem", SOURCE_SYSTEM_MAX_LENGTH)
    }
}
/**
 * SourceEventId은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property value 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@JvmInline
value class SourceEventId(val value: String) {
    init {
        requireBoundedIdentifier(value, "sourceEventId", SOURCE_EVENT_ID_MAX_LENGTH)
    }
}

/**
 * raw idempotency key는 HTTP boundary에만 존재하며 persistence나 logging 전에 digest해야 합니다.
 * @property value 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        requireBoundedIdentifier(value, "idempotencyKey", IDEMPOTENCY_KEY_MAX_LENGTH)
    }

    /**
     * toString은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun toString(): String = "IdempotencyKey([REDACTED])"
}

/**
 * requireBoundedIdentifier은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
 * 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param maxLength 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
 */
private fun requireBoundedIdentifier(value: String, name: String, maxLength: Int) {
    value.requireNotBlank(name)
    value.length.requireInRange(1, maxLength, "$name.length")
}

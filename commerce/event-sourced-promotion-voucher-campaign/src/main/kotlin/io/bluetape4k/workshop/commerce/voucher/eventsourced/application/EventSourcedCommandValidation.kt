package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank

/**
 * `MIN_IDEMPOTENCY_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val MIN_IDEMPOTENCY_KEY_LENGTH = 8
/**
 * `MAX_IDEMPOTENCY_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200

/**
 * validCommandIdentity은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun String.validCommandIdentity(name: String): String = requireNotBlank(name)

/**
 * validIdempotencyKey은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun String.validIdempotencyKey(): String {
    val validKey = requireNotBlank("Idempotency-Key")
    val validLength = validKey.length.requireInRange(
        MIN_IDEMPOTENCY_KEY_LENGTH,
        MAX_IDEMPOTENCY_KEY_LENGTH,
        "Idempotency-Key.length",
    )
    return validKey.take(validLength)
}

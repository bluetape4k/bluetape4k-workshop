package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.logging.KLogging

/**
 * IdempotencyFingerprint은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object IdempotencyFingerprint: KLogging() {

    /**
     * key은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param rawKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    fun key(rawKey: String): String = sha256(rawKey)

    /**
     * request은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lines 업무 처리에 필요한 lines 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun request(lines: Map<String, Int>): String =
        sha256(lines.toSortedMap().entries.joinToString("\n") { (sku, quantity) -> "$sku=$quantity" })

    /**
     * request은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param canonicalPayload 주문 라이프사이클과 fulfillment 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun request(canonicalPayload: String): String = sha256(canonicalPayload)

    /**
     * sha256은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun sha256(value: String): String =
        Digesters.SHA256.digestHexUtf8(value)
}

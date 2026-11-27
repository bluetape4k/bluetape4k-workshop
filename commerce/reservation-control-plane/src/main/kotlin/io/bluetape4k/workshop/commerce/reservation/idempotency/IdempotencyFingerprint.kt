package io.bluetape4k.workshop.commerce.reservation.idempotency

import io.bluetape4k.workshop.shared.fingerprint.FingerprintEncoder

/** caller idempotency key나 payload를 보존하지 않고 domain-separated SHA-256 fingerprint를 생성합니다. */
/** caller idempotency key나 payload를 보존하지 않고 domain-separated SHA-256 fingerprint를 생성합니다. */
internal object IdempotencyFingerprint {

    /**
     * key은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 예약 제어면 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param rawKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    fun key(
        tenantId: String,
        operation: String,
        rawKey: String,
    ): String =
        digest(KEY_DOMAIN, tenantId, operation, rawKey)

    /**
     * request은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun request(
        operation: String,
        canonicalPayload: String,
    ): String =
        digest(REQUEST_DOMAIN, operation, canonicalPayload)

    /**
     * digest은 예약 제어면 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun digest(
        domain: String,
        vararg fields: String,
    ): String =
        FingerprintEncoder.domainSeparatedSha256Hex(domain, *fields)

    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    private const val KEY_DOMAIN = "reservation-http-idempotency-key-v1"
    /**
     * 좌석 예약·대기열 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private const val REQUEST_DOMAIN = "reservation-http-idempotency-request-v1"
}

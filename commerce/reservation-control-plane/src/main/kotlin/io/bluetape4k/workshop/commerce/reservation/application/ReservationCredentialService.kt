package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * caller credential을 domain-separated HMAC digest로 변환합니다.
 *
 * raw owner, operator, idempotency credential은 boundary에서 비교하며
 * 저장하거나 operational log에 기록하지 않습니다.
 */
internal class ReservationCredentialService(secret: String) {

    companion object: KLogging() {
        /**
         * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val HMAC_SHA_256 = "HmacSHA256"
    }
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */

    private val key = SecretKeySpec(
        secret.toUtf8Bytes().also {
            require(it.size >= 32) { "reservation HMAC secret must contain at least 32 bytes" }
        },
        HMAC_SHA_256
    )

    /**
     * `ownerDigest`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = ownerDigest(rawOwner = "demo")
     * ```
     * @param rawOwner 요청 경계에서 검증된 `rawOwner`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun ownerDigest(rawOwner: String): String =
        digest("reservation-owner", rawOwner)

    /**
     * `operatorDigest`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = operatorDigest(rawKey = "demo-idempotency-1")
     * ```
     * @param rawKey 요청 경계에서 검증된 `rawKey`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun operatorDigest(rawKey: String): String =
        digest("reservation-operator", rawKey)

    /**
     * `idempotencyDigest`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = idempotencyDigest(tenant = "tenant-a", operation = "demo", rawKey =
     * "demo-idempotency-1")
     * ```
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param operation 요청 경계에서 검증된 `operation`입니다.
     * @param rawKey 요청 경계에서 검증된 `rawKey`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun idempotencyDigest(
        tenant: String,
        operation: String,
        rawKey: String,
    ): String =
        digest("http-idempotency", "$tenant\u0000$operation\u0000$rawKey")

    /**
     * `matchesOwner`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = matchesOwner(rawOwner = "demo", expectedDigest = "demo")
     * ```
     * @param rawOwner 요청 경계에서 검증된 `rawOwner`입니다.
     * @param expectedDigest 요청 경계에서 검증된 `expectedDigest`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun matchesOwner(
        rawOwner: String,
        expectedDigest: String,
    ): Boolean {
        val actual = ownerDigest(rawOwner).toByteArray(StandardCharsets.US_ASCII)
        val expected = expectedDigest.toByteArray(StandardCharsets.US_ASCII)

        val matched = MessageDigest.isEqual(actual, expected)
        log.debug { "reservation_owner_verified matched=$matched" }

        return matched
    }

    /**
     * `matchesOperator`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = matchesOperator(rawKey = "demo-idempotency-1", expectedDigest = "demo")
     * ```
     * @param rawKey 요청 경계에서 검증된 `rawKey`입니다.
     * @param expectedDigest 요청 경계에서 검증된 `expectedDigest`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun matchesOperator(
        rawKey: String,
        expectedDigest: String,
    ): Boolean {
        val actual = operatorDigest(rawKey).toByteArray(StandardCharsets.US_ASCII)
        val expected = expectedDigest.toByteArray(StandardCharsets.US_ASCII)

        return MessageDigest.isEqual(actual, expected)
            .also { matched ->
                log.debug { "reservation_operator_verified matched=$matched" }
            }
    }

    /**
     * digest은 예약 제어면 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun digest(
        domain: String,
        value: String,
    ): String {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(key)
        return mac.doFinal("$domain\u0000$value".toUtf8Bytes()).toHex()
    }

    /**
     * toHex은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }

}

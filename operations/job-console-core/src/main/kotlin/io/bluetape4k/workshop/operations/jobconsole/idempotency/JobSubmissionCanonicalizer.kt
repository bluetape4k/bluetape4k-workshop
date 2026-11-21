package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*

/**
 * Produces the only hashes accepted by the submission coordinator.
 *
 * The key hash is an isolation identifier, not an HMAC or an authorization
 * proof. Scope must already have been resolved by the HTTP adapter before this
 * class is called.
 * @property maxKeyBytes 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 */
internal class JobSubmissionCanonicalizer(
    /**
     * `maxKeyBytes`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val maxKeyBytes: Int = DEFAULT_MAX_KEY_BYTES,
) {

    private companion object: KLogging() {
        /**
         * `DEFAULT_MAX_KEY_BYTES`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val DEFAULT_MAX_KEY_BYTES = 255
        /**
         * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val MIN_WORK_UNITS = 1
        /**
         * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val MAX_WORK_UNITS = 10_000
        /**
         * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val ASCII_PRINTABLE_RANGE = 0x21..0x7e
    }

    init {
        maxKeyBytes.requireInRange(1, DEFAULT_MAX_KEY_BYTES, "maxKeyBytes")
    }

    /**
     * fingerprint은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 운영 작업 콘솔 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun fingerprint(request: SubmitJobRequest): String {
        request.workUnits.requireInRange(MIN_WORK_UNITS, MAX_WORK_UNITS, "request.workUnits")

        return digest(
            domain = "job-console-submit-v1",
            components = listOf(
                request.jobType.wireValue,
                request.workUnits.toString(),
                request.failureMode.wireValue,
            ),
        )
    }

    /**
     * keyHash은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param rawKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    fun keyHash(scope: DemoCallerScope, rawKey: String): String {
        val keyBytes = validateRawKey(rawKey)
        return digestBytes(
            domain = "job-console-key-v1",
            components = listOf(
                scope.tenantId.toUtf8Bytes(),
                scope.submitterHash.toUtf8Bytes(),
                keyBytes,
            ),
        )
    }

    /**
     * validateRawKey은 운영 작업 콘솔 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param rawKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    fun validateRawKey(rawKey: String): ByteArray {
        val bytes = rawKey.toUtf8Bytes()
        bytes.size.requireInRange(1, maxKeyBytes, "bytes.size")
        require(bytes.all { it.toInt() in ASCII_PRINTABLE_RANGE }) {
            "Idempotency-Key must contain printable ASCII characters only"
        }
        require(',' !in rawKey) {
            "Idempotency-Key must contain exactly one value"
        }
        return bytes
    }

    /**
     * digest은 운영 작업 콘솔 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param components 업무 처리에 필요한 components 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun digest(domain: String, components: List<String>): String =
        digestBytes(domain, components.map { it.toUtf8Bytes() })

    /**
     * digestBytes은 운영 작업 콘솔 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param components 업무 처리에 필요한 components 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun digestBytes(domain: String, components: List<ByteArray>): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        update(messageDigest, domain.toUtf8Bytes())
        components.forEach { update(messageDigest, it) }
        return messageDigest.digest().joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }

    /**
     * update은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param messageDigest 운영 작업 콘솔 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun update(messageDigest: MessageDigest, bytes: ByteArray) {
        messageDigest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        messageDigest.update(bytes)
    }

}

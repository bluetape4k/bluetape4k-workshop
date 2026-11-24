package io.bluetape4k.workshop.commerce.voucherpool.idempotency

import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.io.digest.DigestSession
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8Bytes
import java.nio.ByteBuffer
import java.security.MessageDigest

/** source field를 절대 보존하지 않는 immutable SHA-256 command fingerprint입니다. */
/** source field를 절대 보존하지 않는 immutable SHA-256 command fingerprint입니다. */
internal class CommandFingerprint private constructor(
    /**
     * `bytes`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val bytes: ByteArray,
) {

    /**
     * copyBytes은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun copyBytes(): ByteArray = bytes.copyOf()

    /**
     * equals은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param other 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun equals(other: Any?): Boolean =
        other is CommandFingerprint && MessageDigest.isEqual(bytes, other.bytes)

    /**
     * hashCode은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun hashCode(): Int = bytes.contentHashCode()

    /**
     * toString은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun toString(): String = "CommandFingerprint([REDACTED])"

    companion object: KLogging() {

        /**
         * invoke은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param bytes 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        operator fun invoke(bytes: ByteArray): CommandFingerprint {
            bytes.size.requireEquals(SHA256_BYTES, "bytes.size")
            return CommandFingerprint(bytes.copyOf())
        }

        /**
         * of은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param bytes 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun of(bytes: ByteArray): CommandFingerprint = invoke(bytes)
    }
}

/** bounded semantic command field에서 domain-separated fingerprint를 생성합니다. */
/** bounded semantic command field에서 domain-separated fingerprint를 생성합니다. */
internal object VoucherPoolFingerprint {

    /**
     * command은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param operation 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param fields 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun command(operation: String, fields: Map<String, String?>): CommandFingerprint {
        val validOperation = operation.requireNotBlank("operation")
        require(validOperation.length <= MAX_OPERATION_LENGTH) { "operation is too long" }
        require(fields.size <= MAX_FIELD_COUNT) { "too many fingerprint fields" }

        val digest = Digesters.SHA256.newSession()
        digest.putField(DOMAIN.toUtf8Bytes())
        digest.putField(validOperation.toUtf8Bytes())

        fields.entries.sortedWith(compareByUtf8 { it.key }).forEach { (name, value) ->
            validateName(name)
            digest.putField(name.toUtf8Bytes())

            if (value == null) {
                digest.update(NULL_MARKER)
            } else {
                val valueBytes = value.toUtf8Bytes()
                require(valueBytes.size <= MAX_FIELD_VALUE_BYTES) { "fingerprint field value is too large: $name" }
                digest.update(VALUE_MARKER)
                digest.putField(valueBytes)
            }
        }
        return CommandFingerprint.of(digest.digest())
    }

    /**
     * validateName은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param name 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validateName(name: String) {
        name.requireNotBlank("fingerprintFieldName")
        require(name.length <= MAX_FIELD_NAME_LENGTH) { "fingerprint field name is too long" }
        require(name.none(Char::isISOControl)) { "fingerprint field name must not contain control characters" }
    }

/**
 * putField은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun DigestSession.putField(value: ByteArray) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
        update(value)
    }

/**
 * compareByUtf8은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun <T> compareByUtf8(selector: (T) -> String): Comparator<T> =
        Comparator { left, right ->
            compareUnsigned(selector(left).toUtf8Bytes(), selector(right).toUtf8Bytes())
        }

    /**
     * compareUnsigned은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param left 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param right 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        val minSize = minOf(left.size, right.size)
        repeat(minSize) {
            val compared = (left[it].toInt() and UNSIGNED_BYTE_MASK).compareTo(right[it].toInt() and UNSIGNED_BYTE_MASK)
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    /**
     * `DOMAIN`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private const val DOMAIN = "voucher-pool-command-fingerprint-v1"
    /**
     * `MAX_OPERATION_LENGTH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private const val MAX_OPERATION_LENGTH = 64
    /**
     * `MAX_FIELD_COUNT`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private const val MAX_FIELD_COUNT = 64
    /**
     * `MAX_FIELD_NAME_LENGTH`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    private const val MAX_FIELD_NAME_LENGTH = 128
    /**
     * `MAX_FIELD_VALUE_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private const val MAX_FIELD_VALUE_BYTES = 4_096
    /**
     * `UNSIGNED_BYTE_MASK`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private const val UNSIGNED_BYTE_MASK = 0xff
    /**
     * `NULL_MARKER`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val NULL_MARKER = byteArrayOf(0)
    /**
     * `VALUE_MARKER`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val VALUE_MARKER = byteArrayOf(1)
}

/**
 * `SHA256_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val SHA256_BYTES = 32

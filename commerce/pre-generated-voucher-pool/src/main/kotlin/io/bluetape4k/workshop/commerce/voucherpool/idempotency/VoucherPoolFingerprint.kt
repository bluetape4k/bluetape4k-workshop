package io.bluetape4k.workshop.commerce.voucherpool.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8Bytes
import java.nio.ByteBuffer
import java.security.MessageDigest

/** source field를 절대 보존하지 않는 immutable SHA-256 command fingerprint입니다. */
internal class CommandFingerprint private constructor(
    private val bytes: ByteArray,
) {

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CommandFingerprint && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "CommandFingerprint([REDACTED])"

    companion object: KLogging() {

        operator fun invoke(bytes: ByteArray): CommandFingerprint {
            bytes.size.requireEquals(SHA256_BYTES, "bytes.size")
            return CommandFingerprint(bytes.copyOf())
        }

        fun of(bytes: ByteArray): CommandFingerprint = invoke(bytes)
    }
}

/** bounded semantic command field에서 domain-separated fingerprint를 생성합니다. */
internal object VoucherPoolFingerprint {

    fun command(operation: String, fields: Map<String, String?>): CommandFingerprint {
        val validOperation = operation.requireNotBlank("operation")
        require(validOperation.length <= MAX_OPERATION_LENGTH) { "operation is too long" }
        require(fields.size <= MAX_FIELD_COUNT) { "too many fingerprint fields" }

        val digest = MessageDigest.getInstance("SHA-256")
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

    private fun validateName(name: String) {
        name.requireNotBlank("fingerprintFieldName")
        require(name.length <= MAX_FIELD_NAME_LENGTH) { "fingerprint field name is too long" }
        require(name.none(Char::isISOControl)) { "fingerprint field name must not contain control characters" }
    }

    private fun MessageDigest.putField(value: ByteArray) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
        update(value)
    }

    private fun <T> compareByUtf8(selector: (T) -> String): Comparator<T> =
        Comparator { left, right ->
            compareUnsigned(selector(left).toUtf8Bytes(), selector(right).toUtf8Bytes())
        }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        val minSize = minOf(left.size, right.size)
        repeat(minSize) {
            val compared = (left[it].toInt() and UNSIGNED_BYTE_MASK).compareTo(right[it].toInt() and UNSIGNED_BYTE_MASK)
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    private const val DOMAIN = "voucher-pool-command-fingerprint-v1"
    private const val MAX_OPERATION_LENGTH = 64
    private const val MAX_FIELD_COUNT = 64
    private const val MAX_FIELD_NAME_LENGTH = 128
    private const val MAX_FIELD_VALUE_BYTES = 4_096
    private const val UNSIGNED_BYTE_MASK = 0xff
    private val NULL_MARKER = byteArrayOf(0)
    private val VALUE_MARKER = byteArrayOf(1)
}

private const val SHA256_BYTES = 32

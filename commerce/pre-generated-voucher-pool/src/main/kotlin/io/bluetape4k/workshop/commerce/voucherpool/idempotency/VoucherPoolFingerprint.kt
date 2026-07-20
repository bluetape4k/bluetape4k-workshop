package io.bluetape4k.workshop.commerce.voucherpool.idempotency

import io.bluetape4k.support.requireNotBlank
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Immutable SHA-256 command fingerprint whose source fields are never retained. */
internal class CommandFingerprint private constructor(private val bytes: ByteArray) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CommandFingerprint && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "CommandFingerprint([REDACTED])"

    companion object {
        fun of(bytes: ByteArray): CommandFingerprint {
            require(bytes.size == SHA256_BYTES) { "command fingerprint must contain 256 bits" }
            return CommandFingerprint(bytes.copyOf())
        }
    }
}

/** Builds domain-separated fingerprints from bounded semantic command fields. */
internal object VoucherPoolFingerprint {
    fun command(operation: String, fields: Map<String, String?>): CommandFingerprint {
        val validOperation = operation.requireNotBlank("operation")
        require(validOperation.length <= MAX_OPERATION_LENGTH) { "operation is too long" }
        require(fields.size <= MAX_FIELD_COUNT) { "too many fingerprint fields" }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.putField(DOMAIN.toByteArray(UTF_8))
        digest.putField(validOperation.toByteArray(UTF_8))
        fields.entries.sortedWith(compareByUtf8 { it.key }).forEach { (name, value) ->
            validateName(name)
            digest.putField(name.toByteArray(UTF_8))
            if (value == null) {
                digest.update(NULL_MARKER)
            } else {
                val valueBytes = value.toByteArray(UTF_8)
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

    private fun <T> compareByUtf8(selector: (T) -> String): Comparator<T> = Comparator { left, right ->
        compareUnsigned(selector(left).toByteArray(UTF_8), selector(right).toByteArray(UTF_8))
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val compared =
                (left[index].toInt() and UNSIGNED_BYTE_MASK)
                    .compareTo(right[index].toInt() and UNSIGNED_BYTE_MASK)
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

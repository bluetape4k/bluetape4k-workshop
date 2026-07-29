package io.bluetape4k.workshop.commerce.metering.idempotency

import io.bluetape4k.support.requireInRange
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant

private const val IDEMPOTENCY_KEY_MAX_LENGTH = 256

@JvmInline
value class Sha256Digest private constructor(val value: String) {
    companion object {
        fun of(canonicalValue: String): Sha256Digest =
            Sha256Digest(
                MessageDigest.getInstance("SHA-256")
                    .digest(canonicalValue.toByteArray(UTF_8))
                    .toHexString(),
            )
    }
}

object CommandFingerprint {
    fun key(rawKey: String): Sha256Digest {
        rawKey.length.requireInRange(1, IDEMPOTENCY_KEY_MAX_LENGTH, "rawKey.length")
        return Sha256Digest.of("metering-idempotency-key-v1\u0000$rawKey")
    }

    fun request(operation: String, fields: Map<String, String>): Sha256Digest {
        val canonical =
            buildString {
                append("metering-command-v1\n")
                append(operation.trim()).append('\n')
                fields.toSortedMap().forEach { (name, value) ->
                    append(name).append('=').append(value).append('\n')
                }
            }
        return Sha256Digest.of(canonical)
    }

    fun decimal(value: BigDecimal): String =
        value.stripTrailingZeros().let { normalized ->
            if (normalized.compareTo(BigDecimal.ZERO) == 0) "0" else normalized.toPlainString()
        }

    fun instant(value: Instant): String = value.toString()
}

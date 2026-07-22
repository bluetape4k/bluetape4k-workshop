package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

@JvmInline
value class CommandDigest(val value: String) {
    init {
        require(value.length == SHA256_HEX_LENGTH) { "command_digest_invalid" }
    }

    private companion object {
        const val SHA256_HEX_LENGTH = 64
    }
}

object CommandFingerprint {
    fun key(rawKey: String): CommandDigest {
        require(rawKey.isNotBlank() && rawKey.length <= MAX_KEY_LENGTH) { "idempotency_key_invalid" }
        return sha256("billing-command-key-v1\n$rawKey")
    }

    fun request(operation: String, fields: Map<String, String>): CommandDigest {
        require(operation.isNotBlank()) { "operation_invalid" }
        val canonical = buildString {
            append("billing-command-request-v1\n").append(operation.trim()).append('\n')
            fields.toSortedMap().forEach { (name, value) -> append(name).append('=').append(value).append('\n') }
        }
        return sha256(canonical)
    }

    private fun sha256(value: String): CommandDigest = CommandDigest(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) },
    )

    private const val MAX_KEY_LENGTH = 256
}

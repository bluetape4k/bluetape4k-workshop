package io.bluetape4k.workshop.commerce.order.idempotency

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object IdempotencyFingerprint {
    fun key(rawKey: String): String = sha256(rawKey)

    fun request(lines: Map<String, Int>): String =
        sha256(lines.toSortedMap().entries.joinToString("\n") { (sku, quantity) -> "$sku=$quantity" })

    fun request(canonicalPayload: String): String = sha256(canonicalPayload)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}

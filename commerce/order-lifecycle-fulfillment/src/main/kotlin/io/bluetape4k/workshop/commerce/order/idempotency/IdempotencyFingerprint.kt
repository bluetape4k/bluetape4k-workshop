package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import java.security.MessageDigest

internal object IdempotencyFingerprint: KLogging() {

    fun key(rawKey: String): String = sha256(rawKey)

    fun request(lines: Map<String, Int>): String =
        sha256(lines.toSortedMap().entries.joinToString("\n") { (sku, quantity) -> "$sku=$quantity" })

    fun request(canonicalPayload: String): String = sha256(canonicalPayload)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toUtf8Bytes())
            .joinToString("") { byte -> "%02x".format(byte) }
}

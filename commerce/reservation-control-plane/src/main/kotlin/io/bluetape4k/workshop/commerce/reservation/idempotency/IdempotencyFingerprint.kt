package io.bluetape4k.workshop.commerce.reservation.idempotency

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Builds domain-separated SHA-256 fingerprints without retaining caller idempotency keys or payloads. */
internal object IdempotencyFingerprint {
    fun key(
        tenantId: String,
        operation: String,
        rawKey: String,
    ): String = digest(KEY_DOMAIN, tenantId, operation, rawKey)

    fun request(
        operation: String,
        canonicalPayload: String,
    ): String = digest(REQUEST_DOMAIN, operation, canonicalPayload)

    private fun digest(
        domain: String,
        vararg fields: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                (sequenceOf(domain) + fields.asSequence()).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)
            ).joinToString("") { byte -> "%02x".format(byte) }

    private const val KEY_DOMAIN = "reservation-http-idempotency-key-v1"
    private const val REQUEST_DOMAIN = "reservation-http-idempotency-request-v1"
}

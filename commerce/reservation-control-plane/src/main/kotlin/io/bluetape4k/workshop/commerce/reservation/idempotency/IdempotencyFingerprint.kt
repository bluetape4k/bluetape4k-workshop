package io.bluetape4k.workshop.commerce.reservation.idempotency

import io.bluetape4k.tink.digest.TinkDigesters

/** caller idempotency key나 payload를 보존하지 않고 domain-separated SHA-256 fingerprint를 생성합니다. */
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
    ): String = TinkDigesters.SHA256.digestHex(
        (sequenceOf(domain) + fields.asSequence()).joinToString("\u0000")
    )

    private const val KEY_DOMAIN = "reservation-http-idempotency-key-v1"
    private const val REQUEST_DOMAIN = "reservation-http-idempotency-request-v1"
}

package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * caller credential을 domain-separated HMAC digest로 변환합니다.
 *
 * raw owner, operator, idempotency credential은 boundary에서 비교하며
 * 저장하거나 operational log에 기록하지 않습니다.
 */
internal class ReservationCredentialService(
    secret: String,
) {
    private val key =
        SecretKeySpec(
            secret.toByteArray(StandardCharsets.UTF_8).also {
                require(it.size >= 32) { "reservation HMAC secret must contain at least 32 bytes" }
            },
            HMAC_SHA_256
        )

    fun ownerDigest(rawOwner: String): String = digest("reservation-owner", rawOwner)

    fun operatorDigest(rawKey: String): String = digest("reservation-operator", rawKey)

    fun idempotencyDigest(
        tenant: String,
        operation: String,
        rawKey: String,
    ): String = digest("http-idempotency", "$tenant\u0000$operation\u0000$rawKey")

    fun matchesOwner(
        rawOwner: String,
        expectedDigest: String,
    ): Boolean {
        val actual = ownerDigest(rawOwner).toByteArray(StandardCharsets.US_ASCII)
        val expected = expectedDigest.toByteArray(StandardCharsets.US_ASCII)
        val matched = MessageDigest.isEqual(actual, expected)
        log.debug { "reservation_owner_verified matched=$matched" }
        return matched
    }

    fun matchesOperator(
        rawKey: String,
        expectedDigest: String,
    ): Boolean {
        val actual = operatorDigest(rawKey).toByteArray(StandardCharsets.US_ASCII)
        val expected = expectedDigest.toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.isEqual(actual, expected).also { matched ->
            log.debug { "reservation_operator_verified matched=$matched" }
        }
    }

    private fun digest(
        domain: String,
        value: String,
    ): String {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(key)
        return mac.doFinal("$domain\u0000$value".toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    companion object : KLogging() {
        private const val HMAC_SHA_256 = "HmacSHA256"
    }
}

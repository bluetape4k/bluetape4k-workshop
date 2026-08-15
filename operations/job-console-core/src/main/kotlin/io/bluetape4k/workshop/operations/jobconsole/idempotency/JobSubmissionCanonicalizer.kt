package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Locale

/**
 * Produces the only hashes accepted by the submission coordinator.
 *
 * The key hash is an isolation identifier, not an HMAC or an authorization
 * proof. Scope must already have been resolved by the HTTP adapter before this
 * class is called.
 */
internal class JobSubmissionCanonicalizer(
    private val maxKeyBytes: Int = DEFAULT_MAX_KEY_BYTES,
) {

    init {
        require(maxKeyBytes in 1..DEFAULT_MAX_KEY_BYTES) {
            "maxKeyBytes must be between 1 and $DEFAULT_MAX_KEY_BYTES"
        }
    }

    fun fingerprint(request: SubmitJobRequest): String {
        require(request.workUnits in MIN_WORK_UNITS..MAX_WORK_UNITS) {
            "workUnits must be between $MIN_WORK_UNITS and $MAX_WORK_UNITS"
        }
        return digest(
            domain = "job-console-submit-v1",
            components = listOf(
                request.jobType.wireValue,
                request.workUnits.toString(),
                request.failureMode.wireValue,
            ),
        )
    }

    fun keyHash(scope: DemoCallerScope, rawKey: String): String {
        val keyBytes = validateRawKey(rawKey)
        return digestBytes(
            domain = "job-console-key-v1",
            components = listOf(
                scope.tenantId.toByteArray(UTF_8),
                scope.submitterHash.toByteArray(UTF_8),
                keyBytes,
            ),
        )
    }

    fun validateRawKey(rawKey: String): ByteArray {
        val bytes = rawKey.toByteArray(UTF_8)
        require(bytes.size in 1..maxKeyBytes) {
            "Idempotency-Key must be between 1 and $maxKeyBytes bytes"
        }
        require(bytes.all { it.toInt() in ASCII_PRINTABLE_RANGE }) {
            "Idempotency-Key must contain printable ASCII characters only"
        }
        require(',' !in rawKey) {
            "Idempotency-Key must contain exactly one value"
        }
        return bytes
    }

    private fun digest(domain: String, components: List<String>): String =
        digestBytes(domain, components.map { it.toByteArray(UTF_8) })

    private fun digestBytes(domain: String, components: List<ByteArray>): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        update(messageDigest, domain.toByteArray(UTF_8))
        components.forEach { update(messageDigest, it) }
        return messageDigest.digest().joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }

    private fun update(messageDigest: MessageDigest, bytes: ByteArray) {
        messageDigest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        messageDigest.update(bytes)
    }

    private companion object {
        const val DEFAULT_MAX_KEY_BYTES = 255
        const val MIN_WORK_UNITS = 1
        const val MAX_WORK_UNITS = 10_000
        val ASCII_PRINTABLE_RANGE = 0x21..0x7e
    }
}

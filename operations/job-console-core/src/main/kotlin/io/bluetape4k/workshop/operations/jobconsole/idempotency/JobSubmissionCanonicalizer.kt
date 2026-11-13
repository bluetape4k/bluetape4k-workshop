package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*

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

    private companion object: KLogging() {
        const val DEFAULT_MAX_KEY_BYTES = 255
        const val MIN_WORK_UNITS = 1
        const val MAX_WORK_UNITS = 10_000
        val ASCII_PRINTABLE_RANGE = 0x21..0x7e
    }

    init {
        maxKeyBytes.requireInRange(1, DEFAULT_MAX_KEY_BYTES, "maxKeyBytes")
    }

    fun fingerprint(request: SubmitJobRequest): String {
        request.workUnits.requireInRange(MIN_WORK_UNITS, MAX_WORK_UNITS, "request.workUnits")

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
                scope.tenantId.toUtf8Bytes(),
                scope.submitterHash.toUtf8Bytes(),
                keyBytes,
            ),
        )
    }

    fun validateRawKey(rawKey: String): ByteArray {
        val bytes = rawKey.toUtf8Bytes()
        bytes.size.requireInRange(1, maxKeyBytes, "bytes.size")
        require(bytes.all { it.toInt() in ASCII_PRINTABLE_RANGE }) {
            "Idempotency-Key must contain printable ASCII characters only"
        }
        require(',' !in rawKey) {
            "Idempotency-Key must contain exactly one value"
        }
        return bytes
    }

    private fun digest(domain: String, components: List<String>): String =
        digestBytes(domain, components.map { it.toUtf8Bytes() })

    private fun digestBytes(domain: String, components: List<ByteArray>): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        update(messageDigest, domain.toUtf8Bytes())
        components.forEach { update(messageDigest, it) }
        return messageDigest.digest().joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }

    private fun update(messageDigest: MessageDigest, bytes: ByteArray) {
        messageDigest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        messageDigest.update(bytes)
    }

}

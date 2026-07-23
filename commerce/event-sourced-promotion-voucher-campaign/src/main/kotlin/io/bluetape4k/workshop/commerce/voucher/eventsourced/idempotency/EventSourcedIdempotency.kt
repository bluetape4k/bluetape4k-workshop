package io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import java.io.Serializable
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID

private const val SHA_256_DIGEST_LENGTH = 64
private const val OWNER_TOKEN_BYTES = 32
private const val MIN_HTTP_STATUS = 100
private const val MAX_HTTP_STATUS = 599

/** Canonical SHA-256 hex digest; raw identity, key, and owner-token material never reaches persistence. */
@JvmInline
internal value class ReceiptDigest private constructor(
    val value: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        /** Reconstitutes a canonical digest that was previously validated before persistence. */
        fun of(value: String): ReceiptDigest = ReceiptDigest(value)

        fun sha256(value: String): ReceiptDigest =
            value
                .requireNotBlank("digestMaterial")
                .toByteArray(UTF_8)
                .let { MessageDigest.getInstance("SHA-256").digest(it) }
                .let(HexFormat.of()::formatHex)
                .let(::ReceiptDigest)
    }

    init {
        require(value.length == SHA_256_DIGEST_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "digest must be a lowercase SHA-256 hex value"
        }
    }
}

/** Secret acquisition capability. Only [digest] is persisted. */
@JvmInline
internal value class ReceiptOwnerToken private constructor(
    private val value: String,
) : Serializable {
    fun digest(): ReceiptDigest = ReceiptDigest.sha256("voucher-receipt-owner-v1\u0000$value")

    companion object {
        private const val serialVersionUID: Long = 1L

        fun random(random: SecureRandom = SECURE_RANDOM): ReceiptOwnerToken =
            ByteArray(OWNER_TOKEN_BYTES)
                .also(random::nextBytes)
                .let(HexFormat.of()::formatHex)
                .let(::ReceiptOwnerToken)

        private val SECURE_RANDOM = SecureRandom()
    }
}

/** Immutable identity of one idempotent command receipt; never persist raw credentials. */
@ConsistentCopyVisibility
internal data class ReceiptScope private constructor(
    val tenantId: TenantId,
    val principalDigest: ReceiptDigest,
    val operation: String,
    val resourceId: String,
    val keyDigest: ReceiptDigest,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            tenantId: TenantId,
            principalDigest: ReceiptDigest,
            operation: String,
            resourceId: String,
            keyDigest: ReceiptDigest,
        ): ReceiptScope =
            ReceiptScope(
                tenantId = tenantId,
                principalDigest = principalDigest,
                operation = operation.requireNotBlank("operation"),
                resourceId = resourceId.requireNotBlank("resourceId"),
                keyDigest = keyDigest,
            )
    }
}

/** Closed response descriptor; it deliberately stores allocation identity and key versions, never a voucher code. */
@ConsistentCopyVisibility
internal data class TerminalDescriptor private constructor(
    val outcome: ReceiptOutcome,
    val status: Int,
    val allocationId: UUID?,
    val generationKeyVersion: Int?,
    val verificationKeyVersion: Int?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            outcome: ReceiptOutcome,
            status: Int,
            allocationId: UUID? = null,
            generationKeyVersion: Int? = null,
            verificationKeyVersion: Int? = null,
        ): TerminalDescriptor {
            require(status in MIN_HTTP_STATUS..MAX_HTTP_STATUS) { "status must be a valid HTTP status" }
            require((generationKeyVersion == null) == (verificationKeyVersion == null)) {
                "generation and verification key versions must be stored together"
            }
            require(generationKeyVersion == null || allocationId != null) {
                "key versions require an allocation identity"
            }
            require(generationKeyVersion == null || generationKeyVersion > 0) {
                "generationKeyVersion must be positive"
            }
            require(verificationKeyVersion == null || verificationKeyVersion > 0) {
                "verificationKeyVersion must be positive"
            }
            return TerminalDescriptor(outcome, status, allocationId, generationKeyVersion, verificationKeyVersion)
        }
    }

    fun replayWith(keyVersionAvailable: (Int) -> Boolean): TerminalReplay =
        if (generationKeyVersion == null || keyVersionAvailable(generationKeyVersion)) {
            TerminalReplay.Replay(this)
        } else {
            TerminalReplay.KeyUnavailable
        }
}

internal enum class ReceiptOutcome {
    CAMPAIGN_CREATED,
    VOUCHER_ALLOCATED,
    CONCURRENT_MODIFICATION,
    DOMAIN_REJECTED,
}

internal enum class ReceiptStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
}

internal sealed interface TerminalReplay {
    data class Replay(val descriptor: TerminalDescriptor) : TerminalReplay

    data object KeyUnavailable : TerminalReplay
}

internal sealed interface ReceiptAcquireResult {
    data class Owner(
        val token: ReceiptOwnerToken,
        val leaseDeadline: java.time.Instant,
    ) : ReceiptAcquireResult

    data class Replay(val descriptor: TerminalDescriptor) : ReceiptAcquireResult

    data class InProgress(val retryAfter: java.time.Duration) : ReceiptAcquireResult

    data object FingerprintConflict : ReceiptAcquireResult
}

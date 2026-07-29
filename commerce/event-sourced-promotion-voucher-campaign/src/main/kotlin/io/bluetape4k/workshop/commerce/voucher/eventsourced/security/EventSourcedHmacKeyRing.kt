package io.bluetape4k.workshop.commerce.voucher.eventsourced.security

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import java.io.Serializable
import java.nio.charset.StandardCharsets.UTF_8
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC_SHA_256 = "HmacSHA256"
private const val MINIMUM_HMAC_KEY_BYTES = 32
private const val SHA_256_HEX_LENGTH = 64
private const val HMAC_CONTEXT_VERSION = "voucher-hmac-v1"

internal enum class HmacPurpose(
    val value: String,
) {
    SUBJECT_IDENTITY("subject-identity"),
    PRINCIPAL_SCOPE("principal-scope"),
    IDEMPOTENCY_KEY("idempotency-key"),
    OPERATOR_ACTOR("operator-actor"),
    OPERATOR_REQUEST("operator-request"),
    VOUCHER_CODE("voucher-code"),
}

/**
 * versioned HMAC key 하나입니다. key material은 ingress에서 복사되며 절대 노출하지 않습니다.
 *
 * bluetape4k의 Tink helper는 내부 생성 key를 소유하고 externally versioned key material로 계산할 수 없으므로,
 * 여기서 JCA를 의도적으로 감쌉니다.
 */
internal class EventSourcedHmacKey(
    version: Int,
    material: ByteArray,
) {
    val version: Int = version.requirePositiveNumber("hmacKey.version")
    private val secret = material.copyOf().also { it.size.requireGe(MINIMUM_HMAC_KEY_BYTES, "hmacKey.bytes") }

    fun compute(value: ByteArray): ByteArray =
        Mac
            .getInstance(HMAC_SHA_256)
            .apply { init(SecretKeySpec(secret, HMAC_SHA_256)) }
            .doFinal(value)
}

@ConsistentCopyVisibility
internal data class KeyedDigest private constructor(
    val value: String,
    val keyVersion: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            value: String,
            keyVersion: Int,
        ): KeyedDigest =
            KeyedDigest(
                value = value.requireNotBlank("digest"),
                keyVersion = keyVersion.requirePositiveNumber("digest.keyVersion"),
            )
    }

    init {
        value.length.requireEquals(SHA_256_HEX_LENGTH, "digest.length")
        value.all { it in '0'..'9' || it in 'a'..'f' }.requireEquals(true, "digest.lowercaseHex")
    }
}

internal class EventSourcedKeyUnavailableException(
    val keyVersion: Int,
) : IllegalStateException("HMAC key version $keyVersion is unavailable")

/** 안정적인 domain-separated correlation에 사용하는 active key와 retention-bound retired key입니다. */
internal class EventSourcedHmacKeyRing(
    private val active: EventSourcedHmacKey,
    retired: List<EventSourcedHmacKey> = emptyList(),
) {
    private val orderedKeys = listOf(active) + retired
    private val keysByVersion = orderedKeys.associateBy(EventSourcedHmacKey::version)

    init {
        keysByVersion.size.requireEquals(orderedKeys.size, "uniqueHmacKeyVersions.size")
    }

    fun digest(
        purpose: HmacPurpose,
        tenantId: TenantId,
        domain: String,
        value: String,
    ): KeyedDigest =
        digest(
            key = active,
            purpose = purpose,
            tenantId = tenantId,
            domain = domain,
            value = value,
        )

    fun digestWithVersion(
        keyVersion: Int,
        purpose: HmacPurpose,
        tenantId: TenantId,
        domain: String,
        value: String,
    ): KeyedDigest =
        digest(
            key =
                keysByVersion[keyVersion.requirePositiveNumber("keyVersion")]
                    ?: throw EventSourcedKeyUnavailableException(keyVersion),
            purpose = purpose,
            tenantId = tenantId,
            domain = domain,
            value = value,
        )

    fun digestsForLookup(
        purpose: HmacPurpose,
        tenantId: TenantId,
        domain: String,
        value: String,
    ): List<KeyedDigest> =
        orderedKeys.map { key ->
            digest(
                key = key,
                purpose = purpose,
                tenantId = tenantId,
                domain = domain,
                value = value,
            )
        }

    fun isAvailable(keyVersion: Int): Boolean = keyVersion in keysByVersion

    private fun digest(
        key: EventSourcedHmacKey,
        purpose: HmacPurpose,
        tenantId: TenantId,
        domain: String,
        value: String,
    ): KeyedDigest {
        val validDomain = domain.requireNotBlank("hmac.domain")
        val validValue = value.requireNotBlank("hmac.value")
        val message =
            listOf(
                HMAC_CONTEXT_VERSION,
                purpose.value,
                key.version,
                tenantId.value,
                validDomain,
                validValue,
            ).joinToString("\u0000")

        return KeyedDigest(
            value = HexFormat.of().formatHex(key.compute(message.toByteArray(UTF_8))),
            keyVersion = key.version,
        )
    }
}

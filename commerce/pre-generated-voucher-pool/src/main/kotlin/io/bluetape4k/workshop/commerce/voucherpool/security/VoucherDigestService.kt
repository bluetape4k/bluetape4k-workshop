package io.bluetape4k.workshop.commerce.voucherpool.security

import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class DigestPurpose {
    STABLE_DEDUP,
    VERIFICATION,
    USER_IDENTITY,
    COMMAND_TOMBSTONE,
    REDIS_SIGNAL,
    AUDIT,
}

internal class DigestKey private constructor(
    val version: Int,
    private val material: ByteArray,
) {
    internal fun copyMaterial(): ByteArray = material.copyOf()

    override fun toString(): String = "DigestKey(version=$version, material=[REDACTED])"

    companion object {
        private const val MINIMUM_KEY_BYTES = 32

        fun of(version: Int, material: ByteArray): DigestKey {
            require(version > 0) { "digest key version must be positive" }
            require(material.size >= MINIMUM_KEY_BYTES) { "digest key must contain at least 256 bits" }
            return DigestKey(version, material.copyOf())
        }
    }
}

internal class DigestKeyRing private constructor(
    val current: DigestKey,
    retained: List<DigestKey>,
) {
    private val readable = (listOf(current) + retained).associateBy(DigestKey::version)

    init {
        require(readable.size == retained.size + 1) { "digest key versions must be unique" }
    }

    fun require(version: Int): DigestKey = readable[version] ?: throw VoucherKeyMaterialUnavailableException()

    companion object {
        fun of(current: DigestKey, retained: List<DigestKey> = emptyList()): DigestKeyRing =
            DigestKeyRing(current, retained.toList())
    }
}

internal class VoucherDigest private constructor(
    val purpose: DigestPurpose,
    val keyVersion: Int,
    private val value: ByteArray,
) {
    fun copyBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is VoucherDigest &&
            purpose == other.purpose &&
            keyVersion == other.keyVersion &&
            value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * (31 * purpose.hashCode() + keyVersion) + value.contentHashCode()

    override fun toString(): String = "VoucherDigest(purpose=$purpose, keyVersion=$keyVersion, value=[REDACTED])"

    companion object {
        fun of(purpose: DigestPurpose, keyVersion: Int, value: ByteArray): VoucherDigest {
            require(keyVersion > 0) { "digest key version must be positive" }
            require(value.isNotEmpty()) { "digest value must not be empty" }
            return VoucherDigest(purpose, keyVersion, value.copyOf())
        }
    }
}

internal class VoucherKeyMaterialUnavailableException :
    IllegalStateException("required voucher key material is unavailable")

/** Computes purpose-separated, versioned HMAC-SHA256 digests over canonical length-prefixed fields. */
internal class VoucherDigestService(
    private val stableDedupKey: DigestKey,
    private val commandTombstoneKey: DigestKey,
    rotatingKeys: Map<DigestPurpose, DigestKeyRing>,
) {
    private val rotatingKeys = rotatingKeys.toMap()

    /** Fixed tenant-lifetime command digest authority version; key material remains encapsulated. */
    val commandTombstoneKeyVersion: Int get() = commandTombstoneKey.version

    init {
        require(rotatingKeys.keys == ROTATING_PURPOSES) {
            "every rotating digest purpose requires an explicit key ring"
        }
    }

    fun stableDedup(tenantId: String, code: CanonicalVoucherCode): VoucherDigest =
        digestWithKey(DigestPurpose.STABLE_DEDUP, stableDedupKey, tenantId.bytes(), code.rawBytes())

    fun verification(
        tenantId: String,
        campaignId: UUID,
        allocationId: UUID,
        code: CanonicalVoucherCode,
    ): VoucherDigest = rotatingDigest(
        DigestPurpose.VERIFICATION,
        tenantId.bytes(),
        campaignId.bytes(),
        allocationId.bytes(),
        code.rawBytes(),
    )

    fun matchesVerification(
        tenantId: String,
        campaignId: UUID,
        allocationId: UUID,
        code: CanonicalVoucherCode,
        expected: VoucherDigest,
    ): Boolean = matches(
        DigestPurpose.VERIFICATION,
        expected,
        tenantId.bytes(),
        campaignId.bytes(),
        allocationId.bytes(),
        code.rawBytes(),
    )

    fun userIdentity(tenantId: String, campaignId: UUID, canonicalUser: String): VoucherDigest =
        rotatingDigest(DigestPurpose.USER_IDENTITY, tenantId.bytes(), campaignId.bytes(), canonicalUser.bytes())

    fun commandTombstone(tenantId: String, operation: String, rawIdempotencyKey: String): VoucherDigest =
        digestWithKey(
            DigestPurpose.COMMAND_TOMBSTONE,
            commandTombstoneKey,
            tenantId.bytes(),
            operation.bytes(),
            rawIdempotencyKey.bytes(),
        )

    fun redisSignal(tenantId: String, campaignId: UUID, operation: String, signal: String): VoucherDigest =
        rotatingDigest(
            DigestPurpose.REDIS_SIGNAL,
            tenantId.bytes(),
            campaignId.bytes(),
            operation.bytes(),
            signal.bytes(),
        )

    fun audit(tenantId: String, operation: String, requestMaterial: String): VoucherDigest =
        rotatingDigest(DigestPurpose.AUDIT, tenantId.bytes(), operation.bytes(), requestMaterial.bytes())

    fun matchesStableDedup(tenantId: String, code: CanonicalVoucherCode, expected: VoucherDigest): Boolean {
        if (expected.purpose != DigestPurpose.STABLE_DEDUP) return false
        if (expected.keyVersion != stableDedupKey.version) throw VoucherKeyMaterialUnavailableException()
        val actual = CanonicalFields.hmac(stableDedupKey, DigestPurpose.STABLE_DEDUP, tenantId.bytes(), code.rawBytes())
        return MessageDigest.isEqual(actual, expected.copyBytes())
    }

    private fun rotatingDigest(purpose: DigestPurpose, vararg fields: ByteArray): VoucherDigest {
        val key = checkNotNull(rotatingKeys[purpose]).current
        return digestWithKey(purpose, key, *fields)
    }

    private fun matches(purpose: DigestPurpose, expected: VoucherDigest, vararg fields: ByteArray): Boolean {
        if (expected.purpose != purpose) return false
        val key = checkNotNull(rotatingKeys[purpose]).require(expected.keyVersion)
        val actual = CanonicalFields.hmac(key, purpose, *fields)
        return MessageDigest.isEqual(actual, expected.copyBytes())
    }

    companion object {
        private val ROTATING_PURPOSES = setOf(
            DigestPurpose.VERIFICATION,
            DigestPurpose.USER_IDENTITY,
            DigestPurpose.REDIS_SIGNAL,
            DigestPurpose.AUDIT,
        )
    }
}

private fun digestWithKey(purpose: DigestPurpose, key: DigestKey, vararg fields: ByteArray): VoucherDigest =
    VoucherDigest.of(purpose, key.version, CanonicalFields.hmac(key, purpose, *fields))

internal object CanonicalFields {
    private val DIGEST_DOMAIN = "voucher-pool-digest-v1".bytes()

    fun hmac(key: DigestKey, purpose: DigestPurpose, vararg fields: ByteArray): ByteArray {
        val material = key.copyMaterial()
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(material, "HmacSHA256"))
            update(mac, DIGEST_DOMAIN)
            update(mac, purpose.name.bytes())
            fields.forEach { update(mac, it) }
            mac.doFinal()
        } finally {
            material.fill(0)
            fields.forEach { it.fill(0) }
        }
    }

    fun encode(domain: String, vararg fields: ByteArray): ByteArray {
        val domainBytes = domain.bytes()
        val size = Int.SIZE_BYTES + domainBytes.size + fields.sumOf { Int.SIZE_BYTES + it.size }
        return ByteBuffer.allocate(size).apply {
            putField(domainBytes)
            fields.forEach { field -> putField(field) }
        }.array()
    }

    private fun update(mac: Mac, field: ByteArray) {
        mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(field.size).array())
        mac.update(field)
    }

    private fun ByteBuffer.putField(field: ByteArray) {
        putInt(field.size)
        put(field)
    }
}

private fun String.bytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)

private fun UUID.bytes(): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES * 2)
    .putLong(mostSignificantBits)
    .putLong(leastSignificantBits)
    .array()

private fun CanonicalVoucherCode.rawBytes(): ByteArray = withRawValue { it.bytes() }

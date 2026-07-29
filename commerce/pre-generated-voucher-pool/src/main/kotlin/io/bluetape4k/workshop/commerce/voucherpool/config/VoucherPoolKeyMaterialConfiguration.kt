@file:Suppress(
    "MagicNumber",
    "SwallowedException", // Original exceptions can contain secret-file paths or JSON fragments.
    "ThrowsCount", // Each fail-closed file boundary maps to one stable redacted code.
    "TooGenericExceptionCaught",
    "TooManyFunctions", // Parsing stays encapsulated in the mounted-secret provider boundary.
)

package io.bluetape4k.workshop.commerce.voucherpool.config

import com.sun.security.auth.module.UnixSystem
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKey
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKeyRing
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKek
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

internal fun interface VoucherPoolKeyMaterialProvider {
    fun load(): VoucherPoolRuntimeKeys
}

internal fun interface VoucherPoolKeyFileLocator {
    fun locate(): String?
}

internal enum class VoucherPoolKeyProvenance {
    MOUNTED_SECRET,
    TEST_FIXTURE,
}

/** 완전히 구성된 runtime key입니다. raw key byte는 이 boundary를 넘지 않습니다. */
internal class VoucherPoolRuntimeKeys(
    val digests: VoucherDigestService,
    val kekRing: VoucherKekRing,
    val provenance: VoucherPoolKeyProvenance,
) {
    override fun toString(): String = "VoucherPoolRuntimeKeys(provenance=$provenance, material=[REDACTED])"
}

internal enum class VoucherPoolKeyMaterialFailureCode {
    MISSING_LOCATION,
    INVALID_PATH,
    FILE_UNAVAILABLE,
    FILE_NOT_REGULAR,
    FILE_NOT_CANONICAL,
    INSECURE_PERMISSIONS,
    FILE_TOO_LARGE,
    INVALID_JSON,
    INVALID_SCHEMA,
    INVALID_KEY_MATERIAL,
    FILE_CHANGED,
}

internal class VoucherPoolKeyMaterialException(
    val code: VoucherPoolKeyMaterialFailureCode,
) : IllegalStateException("VOUCHER_POOL_KEY_MATERIAL_${code.name}")

/** environment indirection으로만 선택한 permission-restricted mounted secret 하나를 load합니다. */
internal class MountedSecretVoucherPoolKeyMaterialProvider(
    private val locator: VoucherPoolKeyFileLocator,
    private val mapper: ObjectMapper,
    private val expectedOwnerId: () -> Long = ::currentProcessOwnerId,
    private val beforeRead: (Path) -> Unit = {},
) : VoucherPoolKeyMaterialProvider {
    override fun load(): VoucherPoolRuntimeKeys = try {
        val trustedFile = locateCanonicalFile()
        beforeRead(trustedFile.path)
        val bytes = readBounded(trustedFile.path)
        try {
            if (trustedFile.snapshot != trustedSnapshot(trustedFile.path)) {
                fail(VoucherPoolKeyMaterialFailureCode.FILE_CHANGED)
            }
            parse(mapper.readTree(bytes))
        } catch (failure: VoucherPoolKeyMaterialException) {
            throw failure
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INVALID_JSON)
        } finally {
            bytes.fill(0)
        }
    } catch (failure: VoucherPoolKeyMaterialException) {
        throw failure
    } catch (failure: Exception) {
        throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.FILE_UNAVAILABLE)
    }

    private fun locateCanonicalFile(): TrustedFile {
        val raw = locator.locate()?.takeIf(String::isNotBlank)
            ?: throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.MISSING_LOCATION)
        val path = try {
            Path.of(raw)
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INVALID_PATH)
        }
        if (!path.isAbsolute) fail(VoucherPoolKeyMaterialFailureCode.INVALID_PATH)
        if (Files.isSymbolicLink(path)) fail(VoucherPoolKeyMaterialFailureCode.FILE_NOT_REGULAR)
        val normalized = path.normalize()
        if (normalized != path) fail(VoucherPoolKeyMaterialFailureCode.FILE_NOT_CANONICAL)
        val real = try {
            path.toRealPath()
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.FILE_UNAVAILABLE)
        }
        if (real != path) fail(VoucherPoolKeyMaterialFailureCode.FILE_NOT_CANONICAL)
        return TrustedFile(path, trustedSnapshot(path))
    }

    private fun trustedSnapshot(path: Path): TrustedFileSnapshot {
        val attributes = try {
            Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INSECURE_PERMISSIONS)
        }
        if (!attributes.isRegularFile) fail(VoucherPoolKeyMaterialFailureCode.FILE_NOT_REGULAR)
        if (attributes.size() > MAX_FILE_BYTES) fail(VoucherPoolKeyMaterialFailureCode.FILE_TOO_LARGE)
        val fileKey = attributes.fileKey() ?: fail(VoucherPoolKeyMaterialFailureCode.FILE_UNAVAILABLE)
        val permissions = attributes.permissions()
        val forbidden =
            setOf(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE,
            )
        val ownerId = try {
            (Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong()
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INSECURE_PERMISSIONS)
        }
        val expectedOwner = try {
            expectedOwnerId()
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INSECURE_PERMISSIONS)
        }
        if (
            PosixFilePermission.OWNER_READ !in permissions ||
            permissions.any(forbidden::contains) ||
            ownerId != expectedOwner
        ) {
            fail(VoucherPoolKeyMaterialFailureCode.INSECURE_PERMISSIONS)
        }
        return TrustedFileSnapshot(fileKey, attributes.size(), ownerId, attributes.owner().name, permissions.toSet())
    }

    private fun readBounded(path: Path): ByteArray {
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { input -> input.readNBytes(MAX_FILE_BYTES + 1) }
        if (bytes.size <= MAX_FILE_BYTES) return bytes
        bytes.fill(0)
        fail(VoucherPoolKeyMaterialFailureCode.FILE_TOO_LARGE)
    }

    private fun parse(root: JsonNode): VoucherPoolRuntimeKeys {
        root.requireObject(ROOT_FIELDS)
        val stable = root.required("stableDedup").digestKey()
        val tombstone = root.required("commandTombstone").digestKey()
        val rotatingNode = root.required("rotating").requireObject(ROTATING_PURPOSES.map(DigestPurpose::name).toSet())
        val rotating = ROTATING_PURPOSES.associateWith { purpose -> rotatingNode.required(purpose.name).digestRing() }
        val kekRing = root.required("kek").kekRing()
        return VoucherPoolRuntimeKeys(
            VoucherDigestService(stable, tombstone, rotating),
            kekRing,
            VoucherPoolKeyProvenance.MOUNTED_SECRET,
        )
    }

    private fun JsonNode.digestKey(): DigestKey {
        requireObject(KEY_FIELDS)
        val versionNode = required("version")
        if (!versionNode.isInt || versionNode.intValue() <= 0) fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        val material = decodedMaterial(required("material"), MIN_DIGEST_BYTES)
        return try {
            DigestKey.of(versionNode.intValue(), material)
        } finally {
            material.fill(0)
        }
    }

    private fun JsonNode.digestRing(): DigestKeyRing {
        requireObject(RING_FIELDS)
        val current = required("current").digestKey()
        val retained = required("retained").digestKeys()
        if ((listOf(current) + retained).map(DigestKey::version).distinct().size != retained.size + 1) {
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL)
        }
        return DigestKeyRing.of(current, retained)
    }

    private fun JsonNode.digestKeys(): List<DigestKey> {
        if (!isArray || size() > MAX_RETAINED_KEYS) fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        return (0 until size()).map { index -> get(index).digestKey() }
    }

    private fun JsonNode.kekRing(): VoucherKekRing {
        requireObject(RING_FIELDS)
        val current = required("current").kek()
        val retainedNode = required("retained")
        if (!retainedNode.isArray || retainedNode.size() > MAX_RETAINED_KEYS) {
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        }
        val retained = (0 until retainedNode.size()).map { index -> retainedNode.get(index).kek() }
        if ((listOf(current) + retained).map(VoucherKek::version).distinct().size != retained.size + 1) {
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL)
        }
        return VoucherKekRing.of(current, retained)
    }

    private fun JsonNode.kek(): VoucherKek {
        requireObject(KEY_FIELDS)
        val versionNode = required("version")
        if (!versionNode.isString) fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        val version = versionNode.asString()
        if (!VERSION_LABEL.matches(version) || FORBIDDEN_LABEL_PARTS.any { version.contains(it, ignoreCase = true) }) {
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL)
        }
        val material = decodedMaterial(required("material"), KEK_BYTES, exact = true)
        return try {
            VoucherKek.of(version, material)
        } finally {
            material.fill(0)
        }
    }

    private fun decodedMaterial(node: JsonNode, minimumBytes: Int, exact: Boolean = false): ByteArray {
        if (!node.isString) fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        val material = try {
            Base64.getDecoder().decode(node.asString())
        } catch (failure: IllegalArgumentException) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL)
        }
        val invalidSize = if (exact) material.size != minimumBytes else material.size < minimumBytes
        val repeating = material.isEmpty() || material.all { it == material[0] }
        val fixtureMaterial = material.isKnownTestFixture()
        if (invalidSize || repeating || fixtureMaterial) {
            material.fill(0)
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL)
        }
        return material
    }

    private fun ByteArray.isKnownTestFixture(): Boolean {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(this)
        return try {
            HEX.formatHex(fingerprint) in KNOWN_TEST_FIXTURE_FINGERPRINTS
        } finally {
            fingerprint.fill(0)
        }
    }

    private fun JsonNode.required(name: String): JsonNode =
        get(name) ?: throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)

    private fun JsonNode.requireObject(expectedFields: Set<String>): JsonNode {
        if (!isObject || propertyNames().toSet() != expectedFields) {
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        }
        return this
    }

    private fun fail(code: VoucherPoolKeyMaterialFailureCode): Nothing = throw VoucherPoolKeyMaterialException(code)

    private companion object {
        const val MAX_FILE_BYTES = 64 * 1024
        const val MIN_DIGEST_BYTES = 32
        const val KEK_BYTES = 32
        const val MAX_RETAINED_KEYS = 16
        val ROOT_FIELDS = setOf("stableDedup", "commandTombstone", "rotating", "kek")
        val KEY_FIELDS = setOf("version", "material")
        val RING_FIELDS = setOf("current", "retained")
        val ROTATING_PURPOSES =
            setOf(
                DigestPurpose.VERIFICATION,
                DigestPurpose.USER_IDENTITY,
                DigestPurpose.REDIS_SIGNAL,
                DigestPurpose.AUDIT,
            )
        val VERSION_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val FORBIDDEN_LABEL_PARTS = setOf("test", "fixture", "sample", "demo", "default", "local", "example")
        val HEX: HexFormat = HexFormat.of()
        val KNOWN_TEST_FIXTURE_FINGERPRINTS =
            setOf(
                "ae216c2ef5247a3782c135efa279a3e4cdc61094270f5d2be58c6204b7a612c9",
                "6f103f3d9ba4c7e4d49642fb221098b83bcf07ac955063cb28f093eab38c5444",
                "00c1599d38973fee5a680ea93eba60736b1b1522a1e3488cfcd48f86ab87f4a9",
                "73e5ce5d058c73856400433b9f45c63b9370100b7b22c58167eec5cd1e571ae3",
                "07f23239ee9e10a17942824b76d1919234dfaa97525ed8741e016a2a11c2981c",
                "4caaad9b331e92ac5ccb3adf8fb1d023a3f5d66d895d9362c4fc184dae4e649f",
                "c591c90fef0f1746bcdbfd3504a7fbf02c63c312ffaf8b93368137278afc81b1",
            )
    }
}

private data class TrustedFile(
    val path: Path,
    val snapshot: TrustedFileSnapshot,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

private data class TrustedFileSnapshot(
    val fileKey: Any,
    val size: Long,
    val ownerId: Long,
    val ownerName: String,
    val permissions: Set<PosixFilePermission>,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

private fun currentProcessOwnerId(): Long = UnixSystem().uid

@Configuration(proxyBeanMethods = false)
@Profile("!voucher-pool-test")
internal class VoucherPoolKeyMaterialConfiguration {
    @Bean
    @ConditionalOnMissingBean(VoucherPoolKeyFileLocator::class)
    fun voucherPoolKeyFileLocator(): VoucherPoolKeyFileLocator =
        VoucherPoolKeyFileLocator { System.getenv(VOUCHER_POOL_KEY_FILE) }

    @Bean
    fun voucherPoolKeyMaterialProvider(
        locator: VoucherPoolKeyFileLocator,
        mapper: ObjectMapper,
    ): VoucherPoolKeyMaterialProvider = MountedSecretVoucherPoolKeyMaterialProvider(locator, mapper)

    private companion object {
        const val VOUCHER_POOL_KEY_FILE = "VOUCHER_POOL_KEY_FILE"
    }
}

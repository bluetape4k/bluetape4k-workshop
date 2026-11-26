package io.bluetape4k.workshop.shared.artifact

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Locale

/** 공통 artifact fixture가 신뢰 경계를 통과하지 못했을 때 사용하는 예외입니다. */
class ArtifactPrimitiveException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 공통 artifact/journal redaction 계약 위반을 나타냅니다. */
class ArtifactRedactionException(
    message: String,
) : IllegalStateException(message)

/** artifact 경로와 directory ownership을 검증하는 domain-independent fixture입니다. */
object TrustedArtifactPaths {

    private val identifierPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    /** 파일명·run id로 사용할 bounded identifier를 검증합니다. */
    fun requireIdentifier(
        value: String,
        name: String,
    ): String {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(identifierPattern.matches(value)) {
            "$name must match ${identifierPattern.pattern}"
        }
        require(value != "." && value != "..") { "$name must not be a dot segment" }
        return value
    }

    /** report에 기록할 normalized relative evidence path를 검증합니다. */
    fun requireRelativeEvidence(value: String): String {
        require(value.isNotBlank()) { "evidenceReference must not be blank" }
        val relative = Path.of(value)
        require(
            !relative.isAbsolute &&
                relative.normalize() == relative &&
                relative.none { it.toString() == "." || it.toString() == ".." },
        ) {
            "evidenceReference must be a normalized relative path"
        }
        return value
    }

    /** 경로의 모든 생성·기존 component를 확인하고 symlink 없는 real directory를 반환합니다. */
    fun ensureTrustedDirectory(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var trusted = absolute.root
            ?: throw ArtifactPrimitiveException("artifact directory must be absolute")

        absolute.root.relativize(absolute).forEachIndexed { index, name ->
            trusted = trusted.resolve(name)
            if (Files.exists(trusted, NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(trusted) && index == 0) {
                    // macOS의 /var 같은 system root alias만 canonicalize한다.
                    trusted = trusted.toRealPath()
                } else if (Files.isSymbolicLink(trusted) || !Files.isDirectory(trusted, NOFOLLOW_LINKS)) {
                    throw ArtifactPrimitiveException("artifact path contains an unsafe component")
                }
            } else {
                try {
                    Files.createDirectory(trusted)
                } catch (error: FileAlreadyExistsException) {
                    if (Files.isSymbolicLink(trusted) || !Files.isDirectory(trusted, NOFOLLOW_LINKS)) {
                        throw ArtifactPrimitiveException(
                            "artifact directory raced with an unsafe path",
                            error,
                        )
                    }
                } catch (error: Exception) {
                    throw ArtifactPrimitiveException("artifact directory could not be created", error)
                }
            }
        }
        if (!Files.isDirectory(trusted, NOFOLLOW_LINKS) || Files.isSymbolicLink(trusted)) {
            throw ArtifactPrimitiveException("artifact root must be a directory")
        }
        return trusted.toRealPath(NOFOLLOW_LINKS)
    }

    /** trusted root 아래에 새 run directory를 no-replace로 생성합니다. */
    fun createNewTrustedDirectory(
        root: Path,
        name: String,
    ): Path {
        val trustedRoot = ensureTrustedDirectory(root)
        val target = trustedRoot.resolve(requireIdentifier(name, "directory name"))
        try {
            Files.createDirectory(target)
        } catch (error: FileAlreadyExistsException) {
            throw ArtifactPrimitiveException("artifact run directory must be created exactly once", error)
        } catch (error: Exception) {
            throw ArtifactPrimitiveException("artifact run directory could not be created", error)
        }
        if (Files.isSymbolicLink(target)) {
            throw ArtifactPrimitiveException("artifact run directory must not be a symlink")
        }
        val real = target.toRealPath(NOFOLLOW_LINKS)
        if (real.parent != trustedRoot) {
            throw ArtifactPrimitiveException("artifact run directory escaped its trusted root")
        }
        forceDirectory(trustedRoot)
        return real
    }

    /** parent-owned run directory가 이미 존재하고 trusted root에 바로 속하는지 확인합니다. */
    fun openExistingTrustedDirectory(
        root: Path,
        name: String,
    ): Path {
        val trustedRoot = ensureTrustedDirectory(root)
        val target = trustedRoot.resolve(requireIdentifier(name, "directory name"))
        if (!Files.isDirectory(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw ArtifactPrimitiveException("parent-owned run directory is not trusted")
        }
        val real = target.toRealPath(NOFOLLOW_LINKS)
        if (real.parent != trustedRoot) {
            throw ArtifactPrimitiveException("parent-owned run directory escaped its trusted root")
        }
        return real
    }

    /** 새 파일을 만들 상대 경로를 검증하고 필요한 descendant directory를 생성합니다. */
    fun resolveCreateTarget(
        artifactRoot: Path,
        relativePath: Path,
    ): Path {
        require(!relativePath.isAbsolute) { "artifact relative path must not be absolute" }
        require(relativePath.nameCount > 0) { "artifact relative path must not be empty" }
        require(relativePath.none { it.toString() == ".." || it.toString() == "." }) {
            "artifact relative path must not contain dot segments"
        }

        val root = ensureTrustedDirectory(artifactRoot)
        val target = root.resolve(relativePath).normalize()
        require(target.startsWith(root)) { "artifact path must remain beneath its root" }
        ensureDescendantDirectory(root, root.relativize(target.parent))
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            throw ArtifactPrimitiveException("artifact target already exists")
        }
        return target
    }

    /** directory fsync를 지원하는 provider에서만 수행합니다. */
    fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, READ).use { it.force(true) }
        } catch (_: UnsupportedOperationException) {
            // Directory fsync is not supported by every file provider.
        }
    }

    private fun ensureDescendantDirectory(
        root: Path,
        relativeDirectory: Path,
    ) {
        var current = root
        relativeDirectory.forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                throw ArtifactPrimitiveException("artifact path must not traverse a symlink")
            }
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                    throw ArtifactPrimitiveException("artifact path component must be a directory")
                }
            } else {
                try {
                    Files.createDirectory(current)
                } catch (error: FileAlreadyExistsException) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) {
                        throw ArtifactPrimitiveException(
                            "artifact directory raced with an unsafe path",
                            error,
                        )
                    }
                }
            }
        }
    }
}

/** file/directory force와 hard-link no-replace를 포함하는 공통 artifact writer입니다. */
object AtomicArtifactWriter {

    fun writeNoReplace(
        artifactRoot: Path,
        relativePath: Path,
        bytes: ByteArray,
        temporaryPrefix: String = ".artifact-",
        temporarySuffix: String = ".tmp",
    ): Path {
        require(temporaryPrefix.length >= 3) { "temporaryPrefix must contain at least three characters" }
        val trustedRoot = TrustedArtifactPaths.ensureTrustedDirectory(artifactRoot)
        val target = TrustedArtifactPaths.resolveCreateTarget(trustedRoot, relativePath)
        val temporary = try {
            Files.createTempFile(target.parent, temporaryPrefix, temporarySuffix)
        } catch (error: Exception) {
            throw ArtifactPrimitiveException("artifact temporary file could not be created", error)
        }

        var published = false
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                writeFully(channel, bytes)
                channel.force(true)
            }
            try {
                Files.createLink(target, temporary)
                published = true
            } catch (error: FileAlreadyExistsException) {
                throw ArtifactPrimitiveException("artifact must not replace an existing file", error)
            }

            val finalized = target.toRealPath(NOFOLLOW_LINKS)
            if (
                !Files.isRegularFile(target, NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(target) ||
                !finalized.startsWith(trustedRoot)
            ) {
                Files.deleteIfExists(target)
                throw ArtifactPrimitiveException("finalized artifact escaped its trusted run root")
            }
            TrustedArtifactPaths.forceDirectory(target.parent)
            return target
        } catch (error: ArtifactPrimitiveException) {
            if (published) {
                Files.deleteIfExists(target)
            }
            throw error
        } catch (error: Exception) {
            if (published) {
                Files.deleteIfExists(target)
            }
            throw ArtifactPrimitiveException("artifact could not be finalized atomically", error)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeFully(
        channel: FileChannel,
        bytes: ByteArray,
    ) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }
}

/** 공통 redaction sentinel guard입니다. */
object ArtifactRedactionGuard {

    val DEFAULT_SENTINELS: Set<String> = setOf(
        "postgresql://",
        "redis://",
        "password",
        "authorization",
    )

    fun verify(
        content: String,
        additionalForbiddenSentinels: Collection<String> = emptySet(),
    ) {
        val normalized = content.lowercase(Locale.ROOT)
        val match = (DEFAULT_SENTINELS + additionalForbiddenSentinels).firstOrNull { sentinel ->
            sentinel.isNotBlank() && normalized.contains(sentinel.lowercase(Locale.ROOT))
        }
        if (match != null) {
            throw ArtifactRedactionException("artifact contains forbidden redaction sentinel")
        }
    }
}

/** hash-chain record의 domain-independent digest 결과입니다. */
data class HashChainDigest(
    val sequence: Long,
    val previousRecordSha256: String,
    val payloadSha256: String,
    val recordSha256: String,
)

/** JSONL payload를 소비자 schema와 분리해 검증하는 hash-chain cursor입니다. */
class HashChainCursor(
    initialSequence: Long = 0L,
    initialPreviousRecordSha256: String = HashChainJsonl.GENESIS_SHA256,
) {

    private var nextSequence = initialSequence
    private var previousRecordSha256 = initialPreviousRecordSha256

    init {
        require(initialSequence >= 0) { "initialSequence must not be negative" }
    }

    /** record bytes를 실제로 쓰기 전에 digest를 계산합니다. 상태는 변경하지 않습니다. */
    @Synchronized
    fun prepare(
        payloadBytes: ByteArray,
        recordBytes: ByteArray,
    ): HashChainDigest = HashChainDigest(
        sequence = nextSequence,
        previousRecordSha256 = previousRecordSha256,
        payloadSha256 = HashChainJsonl.sha256(payloadBytes),
        recordSha256 = HashChainJsonl.sha256(recordBytes),
    )

    /** fsynced append가 성공한 뒤 다음 sequence를 커밋합니다. */
    @Synchronized
    fun commit(digest: HashChainDigest) {
        check(digest.sequence == nextSequence) { "hash-chain digest sequence is stale" }
        check(digest.previousRecordSha256 == previousRecordSha256) {
            "hash-chain digest previous link is stale"
        }
        nextSequence = Math.incrementExact(nextSequence)
        previousRecordSha256 = digest.recordSha256
    }

    /** persisted metadata와 bytes를 검증하고 성공한 record만 cursor에 반영합니다. */
    @Synchronized
    fun verify(
        sequence: Long,
        previousRecordSha256: String,
        payloadSha256: String,
        payloadBytes: ByteArray,
        recordBytes: ByteArray,
    ): HashChainDigest {
        val expected = prepare(payloadBytes, recordBytes)
        check(sequence == expected.sequence) { "journal sequence is not monotonic" }
        check(previousRecordSha256 == expected.previousRecordSha256) {
            "journal hash chain is invalid"
        }
        check(payloadSha256 == expected.payloadSha256) {
            "journal payload digest is invalid"
        }
        commit(expected)
        return expected
    }
}

/** JSONL line boundary, force, bounded read를 소유하는 domain-independent helper입니다. */
object HashChainJsonl {

    const val GENESIS_SHA256: String =
        "0000000000000000000000000000000000000000000000000000000000000000"

    fun append(
        channel: FileChannel,
        cursor: HashChainCursor,
        payloadBytes: ByteArray,
        recordBytes: ByteArray,
    ): HashChainDigest {
        val digest = cursor.prepare(payloadBytes, recordBytes)
        writeFully(channel, recordBytes)
        writeFully(channel, NEWLINE)
        channel.force(true)
        cursor.commit(digest)
        return digest
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** stable file handle에서 bounded bytes를 읽습니다. */
    fun readBounded(
        path: Path,
        maximumBytes: Long,
    ): ByteArray {
        require(maximumBytes > 0) { "maximumBytes must be positive" }
        val absolute = path.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(absolute)) {
            throw ArtifactPrimitiveException("artifact path must not be a symbolic link")
        }
        return try {
            FileChannel.open(absolute.toRealPath(NOFOLLOW_LINKS), READ, NOFOLLOW_LINKS).use { channel ->
                val size = channel.size()
                if (size > maximumBytes || size > Int.MAX_VALUE) {
                    throw ArtifactPrimitiveException("artifact exceeds its bounded size")
                }
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        break
                    }
                }
                buffer.flip()
                ByteArray(buffer.remaining()).also(buffer::get)
            }
        } catch (error: ArtifactPrimitiveException) {
            throw error
        } catch (error: Exception) {
            throw ArtifactPrimitiveException("artifact could not be read", error)
        }
    }

    /** 완전한 line만 반환하고, 마지막 torn JSON suffix만 허용합니다. */
    fun completeLines(
        bytes: ByteArray,
        isCompleteJsonDocument: (ByteArray) -> Boolean,
    ): List<ByteArray> {
        val completeLength = bytes.indexOfLast { it == NEWLINE.single() }
        val suffix = bytes.copyOfRange(completeLength + 1, bytes.size)
        if (suffix.isNotEmpty() && isCompleteJsonDocument(suffix)) {
            throw IllegalStateException("complete final journal record is missing its newline")
        }
        if (completeLength < 0) {
            return emptyList()
        }

        val lines = mutableListOf<ByteArray>()
        var lineStart = 0
        for (index in 0..completeLength) {
            if (bytes[index] == NEWLINE.single()) {
                val line = bytes.copyOfRange(lineStart, index)
                if (line.isEmpty()) {
                    throw IllegalStateException("journal contains a blank complete record")
                }
                lines += line
                lineStart = index + 1
            }
        }
        return lines
    }

    private fun writeFully(
        channel: FileChannel,
        bytes: ByteArray,
    ) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private val NEWLINE = byteArrayOf('\n'.code.toByte())
}

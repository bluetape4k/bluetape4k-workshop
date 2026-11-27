package io.bluetape4k.workshop.shared.artifact

import io.bluetape4k.io.artifact.ArtifactPrimitiveException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.Locale

/** 공통 artifact/journal redaction 계약 위반을 나타냅니다. */
class ArtifactRedactionException(
    message: String,
) : IllegalStateException(message)

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

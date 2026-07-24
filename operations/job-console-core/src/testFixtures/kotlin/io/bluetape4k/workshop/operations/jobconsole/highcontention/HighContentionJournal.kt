package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireNotBlank
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

enum class HighContentionJournalEvent {
    PHASE_TRANSITION,
    RESOURCE_TRANSITION,
    DOCKER_CREATE_INTENT,
    DOCKER_RESOURCE_CREATED,
    REPORT_SERIALIZATION_FALLBACK,
}

data class HighContentionJournalPayload(
    val event: HighContentionJournalEvent,
    val fields: Map<String, String>,
)

data class HighContentionJournalRecord(
    val sequence: Long,
    val previousRecordSha256: String,
    val payloadSha256: String,
    val payload: HighContentionJournalPayload,
    val recordSha256: String,
)

class HighContentionJournalException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class HighContentionJournal private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val forbiddenSentinels: Set<String>,
) : AutoCloseable {

    private val mapper = Jackson.createDefaultJsonMapper()
    private var nextSequence = 0L
    private var previousRecordSha256 = GENESIS_SHA256
    private var closed = false

    @Synchronized
    fun append(
        event: HighContentionJournalEvent,
        fields: Map<String, String>,
    ): HighContentionJournalRecord {
        check(!closed) { "journal is closed" }
        val payload = HighContentionJournalPayload(
            event = event,
            fields = fields
                .mapKeys { (key, _) -> key.requireNotBlank("journal field name") }
                .mapValues { (_, value) -> value }
                .toSortedMap(),
        )
        val payloadBytes = mapper.writeValueAsBytes(payload)
        HighContentionRedaction.verify(payloadBytes.toString(Charsets.UTF_8), forbiddenSentinels)
        val payloadSha256 = sha256(payloadBytes)
        val persisted = PersistedJournalRecord(
            sequence = nextSequence,
            previousRecordSha256 = previousRecordSha256,
            payloadSha256 = payloadSha256,
            payload = payload,
        )
        val recordBytes = mapper.writeValueAsBytes(persisted)
        writeFully(channel, ByteBuffer.wrap(recordBytes))
        writeFully(channel, ByteBuffer.wrap(NEWLINE))
        channel.force(true)

        val recordSha256 = sha256(recordBytes)
        val record = HighContentionJournalRecord(
            sequence = nextSequence,
            previousRecordSha256 = previousRecordSha256,
            payloadSha256 = payloadSha256,
            payload = payload,
            recordSha256 = recordSha256,
        )
        nextSequence = Math.incrementExact(nextSequence)
        previousRecordSha256 = recordSha256
        return record
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        channel.force(true)
        channel.close()
        HighContentionArtifactPaths.forceDirectory(path.parent)
    }

    companion object {
        const val GENESIS_SHA256: String =
            "0000000000000000000000000000000000000000000000000000000000000000"

        fun open(
            artifactRoot: Path,
            relativePath: Path,
            forbiddenSentinels: Set<String> = emptySet(),
        ): HighContentionJournal {
            val trustedRoot = HighContentionArtifactPaths.ensureTrustedDirectory(artifactRoot)
            val path = HighContentionArtifactPaths.resolveCreateTarget(trustedRoot, relativePath)
            var createdChannel: FileChannel? = null
            return try {
                val channel = FileChannel.open(path, CREATE_NEW, WRITE)
                createdChannel = channel
                val realPath = path.toRealPath(NOFOLLOW_LINKS)
                if (!realPath.startsWith(trustedRoot)) {
                    throw HighContentionArtifactException("journal escaped its trusted artifact root")
                }
                HighContentionArtifactPaths.forceDirectory(realPath.parent)
                HighContentionJournal(
                    path = realPath,
                    channel = channel,
                    forbiddenSentinels = forbiddenSentinels,
                )
            } catch (error: Exception) {
                createdChannel?.close()
                if (createdChannel != null) {
                    Files.deleteIfExists(path)
                }
                throw HighContentionArtifactException("journal must be created exactly once", error)
            }
        }

        fun read(path: Path): List<HighContentionJournalRecord> {
            val absolute = path.toAbsolutePath().normalize()
            if (Files.isSymbolicLink(absolute)) {
                throw HighContentionArtifactException("journal path must not be a symlink")
            }
            val realPath = absolute.toRealPath(NOFOLLOW_LINKS)
            val bytes = try {
                FileChannel.open(realPath, READ, NOFOLLOW_LINKS).use { channel ->
                    if (channel.size() > MAX_JOURNAL_BYTES) {
                        throw HighContentionJournalException("journal exceeds its bounded size")
                    }
                    val buffer = ByteBuffer.allocate(channel.size().toInt())
                    while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                        // Read the stable file handle completely.
                    }
                    buffer.flip()
                    ByteArray(buffer.remaining()).also(buffer::get)
                }
            } catch (error: HighContentionJournalException) {
                throw error
            } catch (error: Exception) {
                throw HighContentionJournalException("journal could not be read", error)
            }
            return decodeCompleteLines(bytes)
        }

        private fun decodeCompleteLines(bytes: ByteArray): List<HighContentionJournalRecord> {
            val completeLength = bytes.indexOfLast { it == NEWLINE.single() }
            val suffix = bytes.copyOfRange(completeLength + 1, bytes.size)
            if (suffix.isNotEmpty() && isCompleteJsonDocument(suffix)) {
                throw HighContentionJournalException("complete final journal record is missing its newline")
            }
            if (completeLength < 0) {
                return emptyList()
            }
            val complete = bytes.copyOfRange(0, completeLength + 1)
            val lines = complete
                .toString(Charsets.UTF_8)
                .split('\n')
                .dropLast(1)
            if (lines.any(String::isEmpty)) {
                throw HighContentionJournalException("journal contains a blank complete record")
            }
            val mapper = Jackson.createDefaultJsonMapper()
            val strictJsonFactory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build()
            var expectedSequence = 0L
            var expectedPrevious = GENESIS_SHA256

            return lines.mapIndexed { index, line ->
                try {
                    val recordBytes = line.toByteArray(Charsets.UTF_8)
                    val persisted = strictJsonFactory
                        .createParser(ObjectReadContext.empty(), recordBytes)
                        .use { parser ->
                            mapper.readerFor(PersistedJournalRecord::class.java)
                                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readValue<PersistedJournalRecord>(parser)
                        }
                    if (persisted.sequence != expectedSequence) {
                        throw HighContentionJournalException("journal sequence is not monotonic at line ${index + 1}")
                    }
                    if (persisted.previousRecordSha256 != expectedPrevious) {
                        throw HighContentionJournalException("journal hash chain is invalid at line ${index + 1}")
                    }
                    val actualPayloadSha256 = sha256(mapper.writeValueAsBytes(persisted.payload))
                    if (persisted.payloadSha256 != actualPayloadSha256) {
                        throw HighContentionJournalException("journal payload digest is invalid at line ${index + 1}")
                    }
                    val recordSha256 = sha256(recordBytes)
                    expectedSequence = Math.incrementExact(expectedSequence)
                    expectedPrevious = recordSha256
                    HighContentionJournalRecord(
                        sequence = persisted.sequence,
                        previousRecordSha256 = persisted.previousRecordSha256,
                        payloadSha256 = persisted.payloadSha256,
                        payload = persisted.payload,
                        recordSha256 = recordSha256,
                    )
                } catch (error: HighContentionJournalException) {
                    throw error
                } catch (error: Exception) {
                    throw HighContentionJournalException("journal record is invalid at line ${index + 1}", error)
                }
            }
        }

        private fun isCompleteJsonDocument(bytes: ByteArray): Boolean =
            try {
                Jackson.createDefaultJsonMapper()
                    .reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes)
                true
            } catch (_: Exception) {
                false
            }

        private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        private val NEWLINE = byteArrayOf('\n'.code.toByte())
        private const val MAX_JOURNAL_BYTES = 16L * 1024 * 1024
    }
}

private data class PersistedJournalRecord(
    val sequence: Long,
    val previousRecordSha256: String,
    val payloadSha256: String,
    val payload: HighContentionJournalPayload,
)

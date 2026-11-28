package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.shared.artifact.HashChainCursor
import io.bluetape4k.workshop.shared.artifact.HashChainJsonl
import io.bluetape4k.io.artifact.ArtifactPrimitiveException
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

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

/** operations 전용 event schema를 shared hash-chain JSONL primitive에 연결합니다. */
class HighContentionJournal private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val forbiddenSentinels: Set<String>,
) : AutoCloseable {

    private val mapper = Jackson.createDefaultJsonMapper()
    private val chain = HashChainCursor()
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
                .toSortedMap(),
        )
        val payloadBytes = mapper.writeValueAsBytes(payload)
        HighContentionRedaction.verify(payloadBytes.toUtf8String(), forbiddenSentinels)
        val state = chain.prepare(payloadBytes, ByteArray(0))
        val persisted = PersistedJournalRecord(
            sequence = state.sequence,
            previousRecordSha256 = state.previousRecordSha256,
            payloadSha256 = state.payloadSha256,
            payload = payload,
        )
        val recordBytes = mapper.writeValueAsBytes(persisted)
        val digest = HashChainJsonl.append(channel, chain, payloadBytes, recordBytes)
        return HighContentionJournalRecord(
            sequence = digest.sequence,
            previousRecordSha256 = digest.previousRecordSha256,
            payloadSha256 = digest.payloadSha256,
            payload = payload,
            recordSha256 = digest.recordSha256,
        )
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
        const val GENESIS_SHA256: String = HashChainJsonl.GENESIS_SHA256

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
                HighContentionJournal(realPath, channel, forbiddenSentinels)
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
            val bytes = try {
                HashChainJsonl.readBounded(absolute, MAX_JOURNAL_BYTES)
            } catch (error: ArtifactPrimitiveException) {
                throw HighContentionJournalException("journal could not be read", error)
            }
            return decodeCompleteLines(bytes)
        }

        private fun decodeCompleteLines(bytes: ByteArray): List<HighContentionJournalRecord> {
            val lines = try {
                HashChainJsonl.completeLines(bytes, ::isCompleteJsonDocument)
            } catch (error: IllegalStateException) {
                throw HighContentionJournalException(error.message ?: "journal line is invalid", error)
            }
            val mapper = Jackson.createDefaultJsonMapper()
            val strictJsonFactory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build()
            val chain = HashChainCursor()

            return lines.mapIndexed { index, line ->
                try {
                    val persisted = strictJsonFactory
                        .createParser(ObjectReadContext.empty(), line)
                        .use { parser ->
                            mapper.readerFor(PersistedJournalRecord::class.java)
                                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readValue<PersistedJournalRecord>(parser)
                        }
                    val payloadBytes = mapper.writeValueAsBytes(persisted.payload)
                    val digest = chain.verify(
                        sequence = persisted.sequence,
                        previousRecordSha256 = persisted.previousRecordSha256,
                        payloadSha256 = persisted.payloadSha256,
                        payloadBytes = payloadBytes,
                        recordBytes = line,
                    )
                    HighContentionJournalRecord(
                        sequence = persisted.sequence,
                        previousRecordSha256 = persisted.previousRecordSha256,
                        payloadSha256 = persisted.payloadSha256,
                        payload = persisted.payload,
                        recordSha256 = digest.recordSha256,
                    )
                } catch (error: HighContentionJournalException) {
                    throw error
                } catch (error: Exception) {
                    throw HighContentionJournalException(
                        "journal record is invalid at line ${index + 1}",
                        error,
                    )
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

        private const val MAX_JOURNAL_BYTES = 16L * 1024 * 1024
    }
}

private data class PersistedJournalRecord(
    val sequence: Long,
    val previousRecordSha256: String,
    val payloadSha256: String,
    val payload: HighContentionJournalPayload,
)

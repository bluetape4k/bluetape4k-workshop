package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.shared.artifact.ArtifactRedactionException
import io.bluetape4k.workshop.shared.artifact.ArtifactRedactionGuard
import io.bluetape4k.workshop.shared.artifact.HashChainCursor
import io.bluetape4k.workshop.shared.artifact.HashChainJsonl
import io.bluetape4k.io.artifact.ArtifactPrimitiveException
import io.bluetape4k.io.artifact.AtomicArtifactWriter
import io.bluetape4k.io.artifact.TrustedArtifactPaths
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.DSYNC
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class TicketHighContentionArtifactException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class TicketHighContentionArtifactStore private constructor(
    private val runRoot: Path,
) {

    private val mapper = Jackson.createDefaultJsonMapper()

    internal val journalPath: Path
        get() = runRoot.resolve(JOURNAL_FILE_NAME)

    fun createJournal(): TicketHighContentionJournal =
        TicketHighContentionJournal.open(
            artifactRoot = runRoot,
            relativePath = Path.of(JOURNAL_FILE_NAME),
        )

    fun writeTerminalReport(
        implementation: String,
        profileId: String,
        report: Map<String, Any?>,
        requiredFields: Set<String>,
        forbiddenPatterns: List<String>,
    ): Path {
        val safeImplementation = identifier(implementation, "implementation")
        val safeProfile = identifier(profileId, "profileId")
        requiredFields.requireNotEmpty("requiredFields")
        require(requiredFields.all(report.keys::contains)) {
            "terminal report is missing required fields"
        }

        val bytes = mapper.writeValueAsBytes(report)
        verifyRedaction(bytes.toUtf8String(), forbiddenPatterns)
        return try {
            AtomicArtifactWriter.writeNoReplace(
                artifactRoot = runRoot,
                relativePath = Path.of("$safeImplementation-$safeProfile-report.json"),
                bytes = bytes,
                temporaryPrefix = ".ticket-high-contention-",
            )
        } catch (error: ArtifactPrimitiveException) {
            throw TicketHighContentionArtifactException(
                error.message ?: "failed to finalize Ticket terminal report",
                error.cause,
            )
        }
    }

    internal companion object {
        private const val JOURNAL_FILE_NAME = "ticket-topology.jsonl"

        fun create(
            outputRoot: Path,
            runId: String,
            parentOwnedRun: Boolean = false,
        ): TicketHighContentionArtifactStore {
            val validRunId = identifier(runId, "runId")
            return try {
                val root = TrustedArtifactPaths.ensureTrustedDirectory(outputRoot)
                val runRoot = if (parentOwnedRun) {
                    TrustedArtifactPaths.openExistingTrustedDirectory(root, validRunId)
                } else {
                    TrustedArtifactPaths.createNewTrustedDirectory(root, validRunId)
                }
                TicketHighContentionArtifactStore(runRoot)
            } catch (error: ArtifactPrimitiveException) {
                throw TicketHighContentionArtifactException(
                    error.message ?: "artifact root is not trusted",
                    error.cause,
                )
            }
        }

        private fun identifier(value: String, name: String): String =
            TrustedArtifactPaths.requireIdentifier(value.requireNotBlank(name), name)

        private fun verifyRedaction(
            evidence: String,
            forbiddenPatterns: Collection<String> = emptyList(),
        ) {
            try {
                ArtifactRedactionGuard.verify(evidence, COMMERCE_REDACTION_SENTINELS + forbiddenPatterns)
            } catch (error: ArtifactRedactionException) {
                throw IllegalArgumentException("artifact contains raw sensitive evidence", error)
            }
        }

        private val COMMERCE_REDACTION_SENTINELS = setOf(
            "jdbc:postgresql:",
            "credential",
        )
    }
}

internal data class TicketHighContentionJournalPayload(
    val event: String,
    val fields: Map<String, String>,
)

internal data class TicketHighContentionJournalRecord(
    val sequence: Long,
    val previousRecordSha256: String,
    val payloadSha256: String,
    val payload: TicketHighContentionJournalPayload,
    val recordSha256: String,
)

internal class TicketHighContentionJournalException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class TicketHighContentionJournal private constructor(
    private val path: Path,
    private val channel: FileChannel,
) : AutoCloseable {

    private val mapper = Jackson.createDefaultJsonMapper()
    private val lock = ReentrantLock()
    private val chain = HashChainCursor()
    private var closed = false

    fun append(
        event: String,
        fields: Map<String, String>,
    ): TicketHighContentionJournalRecord = lock.withLock {
        check(!closed) { "Ticket high-contention journal is closed" }
        val payload = TicketHighContentionJournalPayload(
            event = event.requireNotBlank("event"),
            fields = fields
                .mapKeys { (key, _) -> key.requireNotBlank("journal field name") }
                .toSortedMap(),
        )
        val payloadBytes = mapper.writeValueAsBytes(payload)
        verifyRedaction(payloadBytes.toUtf8String())
        val state = chain.prepare(payloadBytes, ByteArray(0))
        val persisted = PersistedTicketHighContentionJournalRecord(
            sequence = state.sequence,
            previousRecordSha256 = state.previousRecordSha256,
            payloadSha256 = state.payloadSha256,
            payload = payload,
        )
        val recordBytes = mapper.writeValueAsBytes(persisted)
        val digest = HashChainJsonl.append(channel, chain, payloadBytes, recordBytes)
        TicketHighContentionJournalRecord(
            sequence = digest.sequence,
            previousRecordSha256 = digest.previousRecordSha256,
            payloadSha256 = digest.payloadSha256,
            payload = payload,
            recordSha256 = digest.recordSha256,
        )
    }

    override fun close() = lock.withLock {
        if (closed) {
            return@withLock
        }
        closed = true
        channel.force(true)
        channel.close()
        TrustedArtifactPaths.forceDirectory(path.parent)
    }

    internal companion object {
        const val GENESIS_SHA256: String = HashChainJsonl.GENESIS_SHA256
        private const val MAX_JOURNAL_BYTES = 16L * 1024 * 1024

        fun open(
            artifactRoot: Path,
            relativePath: Path,
        ): TicketHighContentionJournal {
            val trustedRoot = try {
                TrustedArtifactPaths.ensureTrustedDirectory(artifactRoot)
            } catch (error: ArtifactPrimitiveException) {
                throw TicketHighContentionArtifactException(
                    error.message ?: "journal root is not trusted",
                    error.cause,
                )
            }
            val path = try {
                TrustedArtifactPaths.resolveCreateTarget(trustedRoot, relativePath)
            } catch (error: Exception) {
                throw TicketHighContentionArtifactException(
                    "journal escaped its trusted artifact root",
                    error,
                )
            }
            if (path.parent != trustedRoot) {
                throw TicketHighContentionArtifactException("journal escaped its trusted artifact root")
            }

            var createdChannel: FileChannel? = null
            return try {
                val channel = FileChannel.open(path, CREATE_NEW, WRITE, APPEND, DSYNC)
                createdChannel = channel
                val realPath = path.toRealPath(NOFOLLOW_LINKS)
                if (!realPath.startsWith(trustedRoot)) {
                    throw TicketHighContentionArtifactException("journal escaped its trusted artifact root")
                }
                TicketHighContentionJournal(realPath, channel)
            } catch (error: TicketHighContentionArtifactException) {
                createdChannel?.close()
                throw error
            } catch (error: Exception) {
                createdChannel?.close()
                if (createdChannel != null) {
                    Files.deleteIfExists(path)
                }
                throw TicketHighContentionArtifactException("journal must be created exactly once", error)
            }
        }

        fun read(path: Path): List<TicketHighContentionJournalRecord> {
            val absolute = path.toAbsolutePath().normalize()
            if (Files.isSymbolicLink(absolute)) {
                throw TicketHighContentionArtifactException("journal path must not be a symbolic link")
            }
            val bytes = try {
                HashChainJsonl.readBounded(absolute, MAX_JOURNAL_BYTES)
            } catch (error: ArtifactPrimitiveException) {
                throw TicketHighContentionJournalException("journal could not be read", error)
            }
            return decodeCompleteLines(bytes)
        }

        private fun decodeCompleteLines(bytes: ByteArray): List<TicketHighContentionJournalRecord> {
            val lines = try {
                HashChainJsonl.completeLines(bytes, ::isCompleteJsonDocument)
            } catch (error: IllegalStateException) {
                throw TicketHighContentionJournalException(error.message ?: "journal line is invalid", error)
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
                            mapper.readerFor(PersistedTicketHighContentionJournalRecord::class.java)
                                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readValue<PersistedTicketHighContentionJournalRecord>(parser)
                        }
                    val payloadBytes = mapper.writeValueAsBytes(persisted.payload)
                    val digest = chain.verify(
                        sequence = persisted.sequence,
                        previousRecordSha256 = persisted.previousRecordSha256,
                        payloadSha256 = persisted.payloadSha256,
                        payloadBytes = payloadBytes,
                        recordBytes = line,
                    )
                    TicketHighContentionJournalRecord(
                        sequence = persisted.sequence,
                        previousRecordSha256 = persisted.previousRecordSha256,
                        payloadSha256 = persisted.payloadSha256,
                        payload = persisted.payload,
                        recordSha256 = digest.recordSha256,
                    )
                } catch (error: TicketHighContentionJournalException) {
                    throw error
                } catch (error: Exception) {
                    throw TicketHighContentionJournalException(
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

        private fun verifyRedaction(evidence: String) {
            try {
                ArtifactRedactionGuard.verify(evidence, COMMERCE_REDACTION_SENTINELS)
            } catch (error: ArtifactRedactionException) {
                throw IllegalArgumentException("journal contains raw sensitive evidence", error)
            }
        }

        private val COMMERCE_REDACTION_SENTINELS = setOf(
            "jdbc:postgresql:",
            "credential",
        )
    }
}

private data class PersistedTicketHighContentionJournalRecord(
    val sequence: Long,
    val previousRecordSha256: String,
    val payloadSha256: String,
    val payload: TicketHighContentionJournalPayload,
)

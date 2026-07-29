package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.DSYNC
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
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
        verifyRedaction(bytes.toString(Charsets.UTF_8), forbiddenPatterns)

        val target = runRoot.resolve("$safeImplementation-$safeProfile-report.json")
        val temporary = runRoot.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
        var published = false
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE, DSYNC).use { channel ->
                channel.writeFully(ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            try {
                Files.createLink(target, temporary)
                published = true
            } catch (error: FileAlreadyExistsException) {
                throw TicketHighContentionArtifactException(
                    "terminal report must not replace an existing file",
                    error,
                )
            }
            verifyContainedRegularFile(target)
            return target
        } catch (error: Throwable) {
            if (published) {
                Files.deleteIfExists(target)
            }
            runCatching { Files.deleteIfExists(temporary) }
            if (error is TicketHighContentionArtifactException) {
                throw error
            }
            throw TicketHighContentionArtifactException("failed to finalize Ticket terminal report", error)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun verifyContainedRegularFile(path: Path) {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw TicketHighContentionArtifactException("artifact is not a regular file")
        }
        if (!path.toRealPath(NOFOLLOW_LINKS).startsWith(runRoot.toRealPath(NOFOLLOW_LINKS))) {
            throw TicketHighContentionArtifactException("artifact escaped the run root")
        }
    }

    internal companion object {
        private val IDENTIFIER = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val REDACTION_SENTINELS = setOf(
            "postgresql://",
            "redis://",
            "jdbc:postgresql:",
            "password",
            "credential",
            "authorization",
        )
        private const val JOURNAL_FILE_NAME = "ticket-topology.jsonl"

        fun create(
            outputRoot: Path,
            runId: String,
            parentOwnedRun: Boolean = false,
        ): TicketHighContentionArtifactStore {
            val root = outputRoot.toAbsolutePath().normalize()
            createDirectoriesWithoutLinks(root)
            val runRoot = root.resolve(identifier(runId, "runId"))
            if (parentOwnedRun) {
                if (!Files.isDirectory(runRoot, NOFOLLOW_LINKS) || Files.isSymbolicLink(runRoot)) {
                    throw TicketHighContentionArtifactException("parent-owned run root is not trusted")
                }
                rejectLinks(root, runRoot)
                if (runRoot.toRealPath(NOFOLLOW_LINKS).parent != root.toRealPath(NOFOLLOW_LINKS)) {
                    throw TicketHighContentionArtifactException("parent-owned run root escaped output root")
                }
            } else {
                try {
                    Files.createDirectory(runRoot)
                    rejectLinks(root, runRoot)
                    if (!runRoot.toRealPath(NOFOLLOW_LINKS).startsWith(root.toRealPath(NOFOLLOW_LINKS))) {
                        throw TicketHighContentionArtifactException("run root escaped output root")
                    }
                } catch (error: TicketHighContentionArtifactException) {
                    throw error
                } catch (error: Exception) {
                    throw TicketHighContentionArtifactException("run root must be created exactly once", error)
                }
            }
            return TicketHighContentionArtifactStore(runRoot)
        }

        private fun createDirectoriesWithoutLinks(path: Path) {
            var current = path.root
            path.forEach { component ->
                current = current.resolve(component)
                if (Files.exists(current, NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) {
                        throw TicketHighContentionArtifactException("$current is not a trusted directory")
                    }
                } else {
                    Files.createDirectory(current)
                }
            }
        }

        private fun rejectLinks(root: Path, descendant: Path) {
            var current = root
            root.relativize(descendant).forEach { component ->
                current = current.resolve(component)
                if (Files.isSymbolicLink(current)) {
                    throw TicketHighContentionArtifactException("$descendant crosses a symbolic link")
                }
            }
        }

        private fun identifier(value: String, name: String): String =
            value.requireNotBlank(name).also {
                require(IDENTIFIER.matches(it)) { "$name must be a bounded identifier" }
            }

        private fun verifyRedaction(
            evidence: String,
            forbiddenPatterns: Collection<String> = emptyList(),
        ) {
            val normalized = evidence.lowercase()
            (REDACTION_SENTINELS + forbiddenPatterns.map(String::lowercase)).forEach { sentinel ->
                require(!normalized.contains(sentinel)) {
                    "artifact contains raw sensitive evidence"
                }
            }
        }

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
    private var nextSequence = 0L
    private var previousRecordSha256 = GENESIS_SHA256
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
        verifyRedaction(payloadBytes.toString(Charsets.UTF_8))
        val payloadSha256 = sha256(payloadBytes)
        val persisted = PersistedTicketHighContentionJournalRecord(
            sequence = nextSequence,
            previousRecordSha256 = previousRecordSha256,
            payloadSha256 = payloadSha256,
            payload = payload,
        )
        val recordBytes = mapper.writeValueAsBytes(persisted)
        channel.writeFully(ByteBuffer.wrap(recordBytes))
        channel.writeFully(ByteBuffer.wrap(NEWLINE))
        channel.force(true)

        val recordSha256 = sha256(recordBytes)
        val record = TicketHighContentionJournalRecord(
            sequence = nextSequence,
            previousRecordSha256 = previousRecordSha256,
            payloadSha256 = payloadSha256,
            payload = payload,
            recordSha256 = recordSha256,
        )
        nextSequence = Math.incrementExact(nextSequence)
        previousRecordSha256 = recordSha256
        record
    }

    override fun close() {
        lock.withLock {
            if (closed) {
                return
            }
            closed = true
            channel.force(true)
            channel.close()
        }
    }

    internal companion object {
        const val GENESIS_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"

        fun open(
            artifactRoot: Path,
            relativePath: Path,
        ): TicketHighContentionJournal {
            val trustedRoot = artifactRoot.toRealPath(NOFOLLOW_LINKS)
            val path = trustedRoot.resolve(relativePath).normalize()
            if (path.parent != trustedRoot || Files.isSymbolicLink(path)) {
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
                FileChannel.open(absolute.toRealPath(NOFOLLOW_LINKS), READ, NOFOLLOW_LINKS).use { channel ->
                    if (channel.size() > MAX_JOURNAL_BYTES) {
                        throw TicketHighContentionJournalException("journal exceeds its bounded size")
                    }
                    val buffer = ByteBuffer.allocate(channel.size().toInt())
                    while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                        // 안정적인 file handle을 끝까지 읽습니다.
                    }
                    buffer.flip()
                    ByteArray(buffer.remaining()).also(buffer::get)
                }
            } catch (error: TicketHighContentionJournalException) {
                throw error
            } catch (error: Exception) {
                throw TicketHighContentionJournalException("journal could not be read", error)
            }
            return decodeCompleteLines(bytes)
        }

        private fun decodeCompleteLines(bytes: ByteArray): List<TicketHighContentionJournalRecord> {
            val completeLength = bytes.indexOfLast { it == NEWLINE.single() }
            val suffix = bytes.copyOfRange(completeLength + 1, bytes.size)
            if (suffix.isNotEmpty() && isCompleteJsonDocument(suffix)) {
                throw TicketHighContentionJournalException("complete final journal record is missing its newline")
            }
            if (completeLength < 0) {
                return emptyList()
            }
            val lines = bytes
                .copyOfRange(0, completeLength + 1)
                .toString(Charsets.UTF_8)
                .split('\n')
                .dropLast(1)
            if (lines.any(String::isEmpty)) {
                throw TicketHighContentionJournalException("journal contains a blank complete record")
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
                            mapper.readerFor(PersistedTicketHighContentionJournalRecord::class.java)
                                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readValue<PersistedTicketHighContentionJournalRecord>(parser)
                        }
                    if (persisted.sequence != expectedSequence) {
                        throw TicketHighContentionJournalException(
                            "journal sequence is not monotonic at line ${index + 1}",
                        )
                    }
                    if (persisted.previousRecordSha256 != expectedPrevious) {
                        throw TicketHighContentionJournalException(
                            "journal hash chain is invalid at line ${index + 1}",
                        )
                    }
                    val actualPayloadSha256 = sha256(mapper.writeValueAsBytes(persisted.payload))
                    if (persisted.payloadSha256 != actualPayloadSha256) {
                        throw TicketHighContentionJournalException(
                            "journal payload digest is invalid at line ${index + 1}",
                        )
                    }
                    val recordSha256 = sha256(recordBytes)
                    expectedSequence = Math.incrementExact(expectedSequence)
                    expectedPrevious = recordSha256
                    TicketHighContentionJournalRecord(
                        sequence = persisted.sequence,
                        previousRecordSha256 = persisted.previousRecordSha256,
                        payloadSha256 = persisted.payloadSha256,
                        payload = persisted.payload,
                        recordSha256 = recordSha256,
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
            val normalized = evidence.lowercase()
            REDACTION_SENTINELS.forEach { sentinel ->
                require(!normalized.contains(sentinel)) {
                    "journal contains raw sensitive evidence"
                }
            }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        private val NEWLINE = byteArrayOf('\n'.code.toByte())
        private val REDACTION_SENTINELS = setOf(
            "postgresql://",
            "redis://",
            "jdbc:postgresql:",
            "password",
            "credential",
            "authorization",
        )
        private const val MAX_JOURNAL_BYTES = 16L * 1024 * 1024
    }
}

private data class PersistedTicketHighContentionJournalRecord(
    val sequence: Long,
    val previousRecordSha256: String,
    val payloadSha256: String,
    val payload: TicketHighContentionJournalPayload,
)

private fun FileChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        write(buffer)
    }
}

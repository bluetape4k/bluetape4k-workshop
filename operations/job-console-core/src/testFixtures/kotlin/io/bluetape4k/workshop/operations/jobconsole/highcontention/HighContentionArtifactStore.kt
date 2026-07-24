package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.Locale

class HighContentionArtifactException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class HighContentionRedactionException(
    message: String,
) : IllegalStateException(message)

private class HighContentionReportSerializationException(
    cause: Throwable,
) : IllegalStateException("terminal report serialization failed", cause)

fun interface HighContentionReportSerializer {
    fun serialize(report: HighContentionTerminalReport): ByteArray
}

enum class HighContentionReportWriteResult {
    REPORT_WRITTEN,
    FALLBACK_JOURNALED,
}

class HighContentionArtifactStore private constructor(
    private val runRoot: Path,
    private val runId: String,
    private val forbiddenSentinels: Set<String>,
    private val reportSerializer: HighContentionReportSerializer,
) {

    fun writeTerminalReport(
        implementation: String,
        profileId: String,
        report: HighContentionTerminalReport,
    ): Path {
        val validImplementation = HighContentionArtifactPaths.requireIdentifier(
            implementation,
            "implementation",
        )
        val validProfileId = HighContentionArtifactPaths.requireIdentifier(profileId, "profileId")
        val validReport = report.validate()
        validReport.runId.requireEquals(runId, "report runId")
        validReport.implementation.requireEquals(validImplementation, "report implementation")
        validReport.profileId.requireEquals(validProfileId, "report profileId")

        val bytes = try {
            reportSerializer.serialize(validReport)
        } catch (error: Exception) {
            throw HighContentionReportSerializationException(error)
        }
        verifyRedaction(bytes.toString(Charsets.UTF_8))
        return writeNoReplace(
            Path.of("reports", validImplementation, "$validProfileId.json"),
            bytes,
        )
    }

    fun writeTerminalReportOrFallback(
        implementation: String,
        profileId: String,
        report: HighContentionTerminalReport,
        journal: HighContentionJournal,
    ): HighContentionReportWriteResult =
        try {
            writeTerminalReport(implementation, profileId, report)
            HighContentionReportWriteResult.REPORT_WRITTEN
        } catch (error: HighContentionReportSerializationException) {
            journal.append(
                HighContentionJournalEvent.REPORT_SERIALIZATION_FALLBACK,
                mapOf(
                    "errorCode" to HighContentionErrorCode.REPORT_SERIALIZATION.name,
                    "redactedErrorClass" to (error.cause ?: error).javaClass.name,
                    "terminalStatus" to HighContentionTerminalStatus.ERROR.name,
                ),
            )
            HighContentionReportWriteResult.FALLBACK_JOURNALED
        }

    fun verifyRedaction(content: String) {
        HighContentionRedaction.verify(content, forbiddenSentinels)
    }

    private fun writeNoReplace(
        relativePath: Path,
        bytes: ByteArray,
    ): Path {
        val target = HighContentionArtifactPaths.resolveCreateTarget(runRoot, relativePath)
        val temporary = try {
            Files.createTempFile(target.parent, ".high-contention-", ".tmp")
        } catch (error: Exception) {
            throw HighContentionArtifactException("artifact temporary file could not be created", error)
        }
        var published = false
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }
            try {
                Files.createLink(target, temporary)
                published = true
            } catch (error: FileAlreadyExistsException) {
                throw HighContentionArtifactException("artifact must not replace an existing file", error)
            }
            val finalized = target.toRealPath(NOFOLLOW_LINKS)
            if (!finalized.startsWith(runRoot)) {
                Files.deleteIfExists(target)
                throw HighContentionArtifactException("finalized artifact escaped its trusted run root")
            }
            HighContentionArtifactPaths.forceDirectory(target.parent)
            return target
        } catch (error: HighContentionArtifactException) {
            if (published) {
                Files.deleteIfExists(target)
            }
            throw error
        } catch (error: Exception) {
            if (published) {
                Files.deleteIfExists(target)
            }
            throw HighContentionArtifactException("artifact could not be finalized atomically", error)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        fun create(
            outputRoot: Path,
            runId: String,
            forbiddenSentinels: Set<String> = emptySet(),
            reportSerializer: HighContentionReportSerializer = HighContentionReportSerializer {
                Jackson.createDefaultJsonMapper().writeValueAsBytes(it)
            },
        ): HighContentionArtifactStore {
            val validRunId = HighContentionArtifactPaths.requireIdentifier(runId, "runId")
            val root = HighContentionArtifactPaths.ensureTrustedDirectory(outputRoot)
            val runRoot = HighContentionArtifactPaths.createNewTrustedDirectory(root, validRunId)
            return HighContentionArtifactStore(
                runRoot = runRoot,
                runId = validRunId,
                forbiddenSentinels = forbiddenSentinels,
                reportSerializer = reportSerializer,
            )
        }
    }
}

internal object HighContentionRedaction {

    private val defaultForbiddenSentinels = setOf(
        "postgresql://",
        "redis://",
        "toxiproxyControlEndpoint",
        "password",
        "authorization",
        "sentinel-token",
    )

    fun verify(
        content: String,
        additionalForbiddenSentinels: Set<String> = emptySet(),
    ) {
        val normalized = content.lowercase(Locale.ROOT)
        val match = (defaultForbiddenSentinels + additionalForbiddenSentinels).firstOrNull { sentinel ->
            sentinel.isNotBlank() && normalized.contains(sentinel.lowercase(Locale.ROOT))
        }
        if (match != null) {
            throw HighContentionRedactionException("artifact contains forbidden redaction sentinel")
        }
    }
}

internal object HighContentionArtifactPaths {

    private val identifierRegex = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    fun requireIdentifier(
        value: String,
        name: String,
    ): String {
        val valid = value.requireNotBlank(name)
        if (!identifierRegex.matches(valid)) {
            throw IllegalArgumentException("$name must match ${identifierRegex.pattern}")
        }
        if (valid == "." || valid == "..") {
            throw IllegalArgumentException("$name must not be a dot segment")
        }
        return valid
    }

    fun requireRelativeEvidence(value: String): String {
        val valid = value.requireNotBlank("evidenceReference")
        val relative = Path.of(valid)
        if (
            relative.isAbsolute ||
            relative.normalize() != relative ||
            relative.any { it.toString() == "." || it.toString() == ".." }
        ) {
            throw IllegalArgumentException("evidenceReference must be a normalized relative path")
        }
        return valid
    }

    fun ensureTrustedDirectory(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(absolute)) {
            throw HighContentionArtifactException("artifact directory must not be a symlink")
        }
        val missingNames = ArrayDeque<Path>()
        var current: Path? = absolute
        while (current != null && !Files.exists(current, NOFOLLOW_LINKS)) {
            missingNames.addFirst(current.fileName)
            current = current.parent
        }
        if (current == null) {
            throw HighContentionArtifactException("artifact directory has no trusted existing ancestor")
        }
        var trusted = current.toRealPath()
        if (!Files.isDirectory(trusted, NOFOLLOW_LINKS)) {
            throw HighContentionArtifactException("artifact ancestor must be a directory")
        }
        missingNames.forEach { name ->
            trusted = trusted.resolve(name)
            try {
                Files.createDirectory(trusted)
            } catch (error: FileAlreadyExistsException) {
                if (Files.isSymbolicLink(trusted) || !Files.isDirectory(trusted, NOFOLLOW_LINKS)) {
                    throw HighContentionArtifactException("artifact directory raced with an unsafe path", error)
                }
            }
        }
        if (!Files.isDirectory(trusted, NOFOLLOW_LINKS)) {
            throw HighContentionArtifactException("artifact root must be a directory")
        }
        return trusted.toRealPath(NOFOLLOW_LINKS)
    }

    fun createNewTrustedDirectory(
        root: Path,
        name: String,
    ): Path {
        val trustedRoot = ensureTrustedDirectory(root)
        val target = trustedRoot.resolve(requireIdentifier(name, "directory name"))
        try {
            Files.createDirectory(target)
        } catch (error: FileAlreadyExistsException) {
            throw HighContentionArtifactException("artifact run directory must be created exactly once", error)
        }
        if (Files.isSymbolicLink(target)) {
            throw HighContentionArtifactException("artifact run directory must not be a symlink")
        }
        val real = target.toRealPath(NOFOLLOW_LINKS)
        if (real.parent != trustedRoot) {
            throw HighContentionArtifactException("artifact run directory escaped its trusted root")
        }
        forceDirectory(trustedRoot)
        return real
    }

    fun resolveCreateTarget(
        artifactRoot: Path,
        relativePath: Path,
    ): Path {
        if (relativePath.isAbsolute) {
            throw IllegalArgumentException("artifact relative path must not be absolute")
        }
        if (relativePath.any { it.toString() == ".." || it.toString() == "." }) {
            throw IllegalArgumentException("artifact relative path must not contain dot segments")
        }
        val root = ensureTrustedDirectory(artifactRoot)
        val target = root.resolve(relativePath).normalize()
        if (!target.startsWith(root)) {
            throw IllegalArgumentException("artifact path must remain beneath its root")
        }
        ensureDescendantDirectory(root, root.relativize(target.parent))
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            throw HighContentionArtifactException("artifact target already exists")
        }
        return target
    }

    private fun ensureDescendantDirectory(
        root: Path,
        relativeDirectory: Path,
    ) {
        var current = root
        relativeDirectory.forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                throw HighContentionArtifactException("artifact path must not traverse a symlink")
            }
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                    throw HighContentionArtifactException("artifact path component must be a directory")
                }
            } else {
                try {
                    Files.createDirectory(current)
                } catch (error: FileAlreadyExistsException) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) {
                        throw HighContentionArtifactException("artifact directory raced with an unsafe path", error)
                    }
                }
            }
        }
    }

    fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, READ).use { it.force(true) }
        } catch (error: UnsupportedOperationException) {
            // Directory fsync is not supported by every provider.
        }
    }
}

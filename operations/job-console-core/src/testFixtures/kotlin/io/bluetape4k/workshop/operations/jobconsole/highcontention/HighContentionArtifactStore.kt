package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.shared.artifact.ArtifactPrimitiveException
import io.bluetape4k.workshop.shared.artifact.ArtifactRedactionException
import io.bluetape4k.workshop.shared.artifact.ArtifactRedactionGuard
import io.bluetape4k.workshop.shared.artifact.AtomicArtifactWriter
import io.bluetape4k.workshop.shared.artifact.TrustedArtifactPaths
import java.nio.file.Path

/** 운영 high-contention artifact의 domain-level 실패입니다. */
class HighContentionArtifactException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 운영 high-contention artifact에 raw sentinel이 남은 경우의 실패입니다. */
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

/** report schema와 shared no-replace writer를 연결하는 operations 전용 adapter입니다. */
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
        val validImplementation = HighContentionArtifactPaths.requireIdentifier(implementation, "implementation")
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
        verifyRedaction(bytes.toUtf8String())
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
    ): Path =
        try {
            AtomicArtifactWriter.writeNoReplace(
                artifactRoot = runRoot,
                relativePath = relativePath,
                bytes = bytes,
                temporaryPrefix = ".high-contention-",
            )
        } catch (error: ArtifactPrimitiveException) {
            throw HighContentionArtifactException(
                error.message ?: "artifact could not be finalized atomically",
                error.cause,
            )
        }

    companion object {
        fun create(
            outputRoot: Path,
            runId: String,
            parentOwnedRun: Boolean = false,
            forbiddenSentinels: Set<String> = emptySet(),
            reportSerializer: HighContentionReportSerializer = HighContentionReportSerializer {
                Jackson.createDefaultJsonMapper().writeValueAsBytes(it)
            },
        ): HighContentionArtifactStore {
            val validRunId = HighContentionArtifactPaths.requireIdentifier(runId, "runId")
            val root = HighContentionArtifactPaths.ensureTrustedDirectory(outputRoot)
            val runRoot = if (parentOwnedRun) {
                HighContentionArtifactPaths.openExistingTrustedDirectory(root, validRunId)
            } else {
                HighContentionArtifactPaths.createNewTrustedDirectory(root, validRunId)
            }
            return HighContentionArtifactStore(
                runRoot = runRoot,
                runId = validRunId,
                forbiddenSentinels = forbiddenSentinels,
                reportSerializer = reportSerializer,
            )
        }
    }
}

/** operations 소비자가 shared redaction guard에 domain sentinel을 추가하는 adapter입니다. */
internal object HighContentionRedaction {

    private val defaultAdditionalSentinels = setOf(
        "toxiproxyControlEndpoint",
        "sentinel-token",
    )

    fun verify(
        content: String,
        additionalForbiddenSentinels: Set<String> = emptySet(),
    ) {
        try {
            ArtifactRedactionGuard.verify(
                content,
                defaultAdditionalSentinels + additionalForbiddenSentinels,
            )
        } catch (error: ArtifactRedactionException) {
            throw HighContentionRedactionException(
                error.message ?: "artifact contains forbidden redaction sentinel",
            )
        }
    }
}

/** 기존 operations 호출부가 shared path primitive를 계속 사용하는 thin facade입니다. */
internal object HighContentionArtifactPaths {

    fun requireIdentifier(value: String, name: String): String =
        TrustedArtifactPaths.requireIdentifier(value, name)

    fun requireRelativeEvidence(value: String): String =
        TrustedArtifactPaths.requireRelativeEvidence(value)

    fun ensureTrustedDirectory(path: Path): Path = translate {
        TrustedArtifactPaths.ensureTrustedDirectory(path)
    }

    fun createNewTrustedDirectory(
        root: Path,
        name: String,
    ): Path = translate {
        TrustedArtifactPaths.createNewTrustedDirectory(root, name)
    }

    fun openExistingTrustedDirectory(
        root: Path,
        name: String,
    ): Path = translate {
        TrustedArtifactPaths.openExistingTrustedDirectory(root, name)
    }

    fun resolveCreateTarget(
        artifactRoot: Path,
        relativePath: Path,
    ): Path = translate {
        TrustedArtifactPaths.resolveCreateTarget(artifactRoot, relativePath)
    }

    fun forceDirectory(directory: Path) {
        TrustedArtifactPaths.forceDirectory(directory)
    }

    private inline fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (error: ArtifactPrimitiveException) {
            throw HighContentionArtifactException(
                error.message ?: "untrusted artifact path",
                error.cause,
            )
        }
}

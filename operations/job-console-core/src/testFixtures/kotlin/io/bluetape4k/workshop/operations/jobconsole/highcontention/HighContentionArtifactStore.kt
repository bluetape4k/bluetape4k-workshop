package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8String
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.Locale

/**
 * HighContentionArtifactException 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionArtifactException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * HighContentionRedactionException 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionRedactionException(
    message: String,
) : IllegalStateException(message)

/**
 * HighContentionReportSerializationException 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class HighContentionReportSerializationException(
    cause: Throwable,
) : IllegalStateException("terminal report serialization failed", cause)

/**
 * HighContentionReportSerializer 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
fun interface HighContentionReportSerializer {
/**
 * serialize은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun serialize(report: HighContentionTerminalReport): ByteArray
}

/**
 * `HighContentionReportWriteResult`은 명령 처리 결과입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
enum class HighContentionReportWriteResult {
    /**
     * `HighContentionReportWriteResult.REPORT_WRITTEN`은 `report written` 업무 상황을 나타냅니다.
     * 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    REPORT_WRITTEN,
    /**
     * `HighContentionReportWriteResult.FALLBACK_JOURNALED`은 `fallback journaled` 업무 상황을 나타냅니다.
     * 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    FALLBACK_JOURNALED,
}

/**
 * HighContentionArtifactStore 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionArtifactStore private constructor(
/**
 * runRoot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val runRoot: Path,
/**
 * runId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val runId: String,
/**
 * forbiddenSentinels은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val forbiddenSentinels: Set<String>,
/**
 * reportSerializer은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reportSerializer: HighContentionReportSerializer,
) {

/**
 * writeTerminalReport은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun writeTerminalReport(
        implementation: String,
        profileId: String,
        report: HighContentionTerminalReport,
    ): Path {
/**
 * validImplementation은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val validImplementation = HighContentionArtifactPaths.requireIdentifier(
            implementation,
            "implementation",
        )
/**
 * validProfileId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val validProfileId = HighContentionArtifactPaths.requireIdentifier(profileId, "profileId")
/**
 * validReport은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val validReport = report.validate()
        validReport.runId.requireEquals(runId, "report runId")
        validReport.implementation.requireEquals(validImplementation, "report implementation")
        validReport.profileId.requireEquals(validProfileId, "report profileId")

/**
 * bytes은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * writeTerminalReportOrFallback은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * verifyRedaction은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun verifyRedaction(content: String) {
        HighContentionRedaction.verify(content, forbiddenSentinels)
    }

/**
 * writeNoReplace은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun writeNoReplace(
        relativePath: Path,
        bytes: ByteArray,
    ): Path {
/**
 * target은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val target = HighContentionArtifactPaths.resolveCreateTarget(runRoot, relativePath)
/**
 * temporary은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val temporary = try {
            Files.createTempFile(target.parent, ".high-contention-", ".tmp")
        } catch (error: Exception) {
            throw HighContentionArtifactException("artifact temporary file could not be created", error)
        }
/**
 * published은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var published = false
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
/**
 * buffer은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * finalized은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * create은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun create(
            outputRoot: Path,
            runId: String,
            parentOwnedRun: Boolean = false,
            forbiddenSentinels: Set<String> = emptySet(),
            reportSerializer: HighContentionReportSerializer = HighContentionReportSerializer {
                Jackson.createDefaultJsonMapper().writeValueAsBytes(it)
            },
        ): HighContentionArtifactStore {
/**
 * validRunId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val validRunId = HighContentionArtifactPaths.requireIdentifier(runId, "runId")
/**
 * root은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val root = HighContentionArtifactPaths.ensureTrustedDirectory(outputRoot)
/**
 * runRoot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * HighContentionRedaction 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal object HighContentionRedaction {

/**
 * defaultForbiddenSentinels은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val defaultForbiddenSentinels = setOf(
        "postgresql://",
        "redis://",
        "toxiproxyControlEndpoint",
        "password",
        "authorization",
        "sentinel-token",
    )

/**
 * verify은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun verify(
        content: String,
        additionalForbiddenSentinels: Set<String> = emptySet(),
    ) {
/**
 * normalized은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val normalized = content.lowercase(Locale.ROOT)
/**
 * match은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val match = (defaultForbiddenSentinels + additionalForbiddenSentinels).firstOrNull { sentinel ->
            sentinel.isNotBlank() && normalized.contains(sentinel.lowercase(Locale.ROOT))
        }
        if (match != null) {
            throw HighContentionRedactionException("artifact contains forbidden redaction sentinel")
        }
    }
}

/**
 * HighContentionArtifactPaths 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal object HighContentionArtifactPaths {

/**
 * identifierRegex은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val identifierRegex = Regex("[a-z0-9][a-z0-9._-]{0,63}")

/**
 * requireIdentifier은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun requireIdentifier(
        value: String,
        name: String,
    ): String {
/**
 * valid은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val valid = value.requireNotBlank(name)
        if (!identifierRegex.matches(valid)) {
            throw IllegalArgumentException("$name must match ${identifierRegex.pattern}")
        }
        if (valid == "." || valid == "..") {
            throw IllegalArgumentException("$name must not be a dot segment")
        }
        return valid
    }

/**
 * requireRelativeEvidence은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * ensureTrustedDirectory은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun ensureTrustedDirectory(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var trusted = absolute.root
            ?: throw HighContentionArtifactException("artifact directory must be absolute")
        absolute.root.relativize(absolute).forEachIndexed { index, name ->
            trusted = trusted.resolve(name)
            if (Files.exists(trusted, NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(trusted) && index == 0) {
                    trusted = trusted.toRealPath()
                } else if (Files.isSymbolicLink(trusted) || !Files.isDirectory(trusted, NOFOLLOW_LINKS)) {
                    throw HighContentionArtifactException("artifact path contains an unsafe component")
                }
            } else {
                try {
                    Files.createDirectory(trusted)
                } catch (error: FileAlreadyExistsException) {
                    if (Files.isSymbolicLink(trusted) || !Files.isDirectory(trusted, NOFOLLOW_LINKS)) {
                        throw HighContentionArtifactException(
                            "artifact directory raced with an unsafe path",
                            error,
                        )
                    }
                }
            }
        }
        if (!Files.isDirectory(trusted, NOFOLLOW_LINKS)) {
            throw HighContentionArtifactException("artifact root must be a directory")
        }
        return trusted.toRealPath(NOFOLLOW_LINKS)
    }

/**
 * createNewTrustedDirectory은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun createNewTrustedDirectory(
        root: Path,
        name: String,
    ): Path {
/**
 * trustedRoot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val trustedRoot = ensureTrustedDirectory(root)
/**
 * target은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val target = trustedRoot.resolve(requireIdentifier(name, "directory name"))
        try {
            Files.createDirectory(target)
        } catch (error: FileAlreadyExistsException) {
            throw HighContentionArtifactException("artifact run directory must be created exactly once", error)
        }
        if (Files.isSymbolicLink(target)) {
            throw HighContentionArtifactException("artifact run directory must not be a symlink")
        }
/**
 * real은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val real = target.toRealPath(NOFOLLOW_LINKS)
        if (real.parent != trustedRoot) {
            throw HighContentionArtifactException("artifact run directory escaped its trusted root")
        }
        forceDirectory(trustedRoot)
        return real
    }

/**
 * openExistingTrustedDirectory은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun openExistingTrustedDirectory(
        root: Path,
        name: String,
    ): Path {
/**
 * trustedRoot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val trustedRoot = ensureTrustedDirectory(root)
/**
 * target은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val target = trustedRoot.resolve(requireIdentifier(name, "directory name"))
        if (!Files.isDirectory(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw HighContentionArtifactException("parent-owned run directory is not trusted")
        }
/**
 * real은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val real = target.toRealPath(NOFOLLOW_LINKS)
        if (real.parent != trustedRoot) {
            throw HighContentionArtifactException("parent-owned run directory escaped its trusted root")
        }
        return real
    }

/**
 * resolveCreateTarget은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
/**
 * root은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val root = ensureTrustedDirectory(artifactRoot)
/**
 * target은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * ensureDescendantDirectory은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun ensureDescendantDirectory(
        root: Path,
        relativeDirectory: Path,
    ) {
/**
 * current은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * forceDirectory은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, READ).use { it.force(true) }
        } catch (error: UnsupportedOperationException) {
            // Directory fsync is not supported by every provider.
        }
    }
}

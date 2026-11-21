package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.codec.Base58
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotContains
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * TicketHighContentionArtifactException 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketHighContentionArtifactException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * TicketHighContentionArtifactStore 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketHighContentionArtifactStore private constructor(
/**
 * runRoot은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val runRoot: Path,
) {
/**
 * mapper은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val mapper = Jackson.createDefaultJsonMapper()

/**
 * journalPath은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    internal val journalPath: Path
        get() = runRoot.resolve(JOURNAL_FILE_NAME)

/**
 * createJournal은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun createJournal(): TicketHighContentionJournal =
        TicketHighContentionJournal.open(
            artifactRoot = runRoot,
            relativePath = Path.of(JOURNAL_FILE_NAME),
        )

/**
 * writeTerminalReport은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun writeTerminalReport(
        implementation: String,
        profileId: String,
        report: Map<String, Any?>,
        requiredFields: Set<String>,
        forbiddenPatterns: List<String>,
    ): Path {
/**
 * safeImplementation은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val safeImplementation = identifier(implementation, "implementation")
/**
 * safeProfile은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val safeProfile = identifier(profileId, "profileId")
        requiredFields.requireNotEmpty("requiredFields")
        require(requiredFields.all(report.keys::contains)) {
            "terminal report is missing required fields"
        }
/**
 * bytes은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val bytes = mapper.writeValueAsBytes(report)
        verifyRedaction(bytes.toUtf8String(), forbiddenPatterns)

/**
 * target은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val target = runRoot.resolve("$safeImplementation-$safeProfile-report.json")
/**
 * temporary은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val temporary = runRoot.resolve(".${target.fileName}.${Base58.randomString(16)}.tmp")
/**
 * published은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * verifyContainedRegularFile은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun verifyContainedRegularFile(path: Path) {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw TicketHighContentionArtifactException("artifact is not a regular file")
        }
        if (!path.toRealPath(NOFOLLOW_LINKS).startsWith(runRoot.toRealPath(NOFOLLOW_LINKS))) {
            throw TicketHighContentionArtifactException("artifact escaped the run root")
        }
    }

    internal companion object {
/**
 * IDENTIFIER은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val IDENTIFIER = Regex("[a-z0-9][a-z0-9._-]{0,63}")
/**
 * REDACTION_SENTINELS은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val REDACTION_SENTINELS = setOf(
            "postgresql://",
            "redis://",
            "jdbc:postgresql:",
            "password",
            "credential",
            "authorization",
        )
/**
 * JOURNAL_FILE_NAME은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val JOURNAL_FILE_NAME = "ticket-topology.jsonl"

/**
 * create은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun create(
            outputRoot: Path,
            runId: String,
            parentOwnedRun: Boolean = false,
        ): TicketHighContentionArtifactStore {
/**
 * root은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val root = outputRoot.toAbsolutePath().normalize()
            createDirectoriesWithoutLinks(root)
/**
 * runRoot은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * createDirectoriesWithoutLinks은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * rejectLinks은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun rejectLinks(root: Path, descendant: Path) {
            var current = root
            root.relativize(descendant).forEach { component ->
                current = current.resolve(component)
                if (Files.isSymbolicLink(current)) {
                    throw TicketHighContentionArtifactException("$descendant crosses a symbolic link")
                }
            }
        }

/**
 * identifier은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun identifier(value: String, name: String): String =
            value.requireNotBlank(name).also {
                require(IDENTIFIER.matches(it)) { "$name must be a bounded identifier" }
            }

/**
 * verifyRedaction은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun verifyRedaction(
            evidence: String,
            forbiddenPatterns: Collection<String> = emptyList(),
        ) {
/**
 * normalized은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val normalized = evidence.lowercase()
            (REDACTION_SENTINELS + forbiddenPatterns.map(String::lowercase)).forEach { sentinel ->
                require(!normalized.contains(sentinel)) {
                    "artifact contains raw sensitive evidence"
                }
            }
        }

    }
}

/**
 * TicketHighContentionJournalPayload 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class TicketHighContentionJournalPayload(
/**
 * event은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val event: String,
/**
 * fields은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val fields: Map<String, String>,
)

/**
 * TicketHighContentionJournalRecord 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class TicketHighContentionJournalRecord(
/**
 * sequence은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sequence: Long,
/**
 * previousRecordSha256은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val previousRecordSha256: String,
/**
 * payloadSha256은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val payloadSha256: String,
/**
 * payload은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val payload: TicketHighContentionJournalPayload,
/**
 * recordSha256은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val recordSha256: String,
)

/**
 * TicketHighContentionJournalException 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketHighContentionJournalException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * TicketHighContentionJournal 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketHighContentionJournal private constructor(
/**
 * path은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val path: Path,
/**
 * channel은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val channel: FileChannel,
) : AutoCloseable {
/**
 * mapper은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val mapper = Jackson.createDefaultJsonMapper()
/**
 * lock은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val lock = ReentrantLock()
/**
 * nextSequence은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var nextSequence = 0L
/**
 * previousRecordSha256은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var previousRecordSha256 = GENESIS_SHA256
/**
 * closed은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var closed = false

/**
 * append은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun append(
        event: String,
        fields: Map<String, String>,
    ): TicketHighContentionJournalRecord = lock.withLock {
        check(!closed) { "Ticket high-contention journal is closed" }
/**
 * payload은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payload = TicketHighContentionJournalPayload(
            event = event.requireNotBlank("event"),
            fields = fields
                .mapKeys { (key, _) -> key.requireNotBlank("journal field name") }
                .toSortedMap(),
        )
/**
 * payloadBytes은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payloadBytes = mapper.writeValueAsBytes(payload)
        verifyRedaction(payloadBytes.toUtf8String())
/**
 * payloadSha256은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payloadSha256 = sha256(payloadBytes)
/**
 * persisted은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val persisted = PersistedTicketHighContentionJournalRecord(
            sequence = nextSequence,
            previousRecordSha256 = previousRecordSha256,
            payloadSha256 = payloadSha256,
            payload = payload,
        )
/**
 * recordBytes은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val recordBytes = mapper.writeValueAsBytes(persisted)
        channel.writeFully(ByteBuffer.wrap(recordBytes))
        channel.writeFully(ByteBuffer.wrap(NEWLINE))
        channel.force(true)

/**
 * recordSha256은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val recordSha256 = sha256(recordBytes)
/**
 * record은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * close은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
/**
 * GENESIS_SHA256은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val GENESIS_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"

/**
 * open은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun open(
            artifactRoot: Path,
            relativePath: Path,
        ): TicketHighContentionJournal {
/**
 * trustedRoot은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val trustedRoot = artifactRoot.toRealPath(NOFOLLOW_LINKS)
/**
 * path은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val path = trustedRoot.resolve(relativePath).normalize()
            if (path.parent != trustedRoot || Files.isSymbolicLink(path)) {
                throw TicketHighContentionArtifactException("journal escaped its trusted artifact root")
            }
/**
 * createdChannel은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var createdChannel: FileChannel? = null
            return try {
/**
 * channel은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val channel = FileChannel.open(path, CREATE_NEW, WRITE, APPEND, DSYNC)
                createdChannel = channel
/**
 * realPath은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * read은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * decodeCompleteLines은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
                .toUtf8String()
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
                    val recordBytes = line.toUtf8Bytes()
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

/**
 * isCompleteJsonDocument은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun isCompleteJsonDocument(bytes: ByteArray): Boolean =
            try {
                Jackson.defaultJsonMapper
                    .reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes)
                true
            } catch (_: Exception) {
                false
            }

/**
 * verifyRedaction은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun verifyRedaction(evidence: String) {
            val normalized = evidence.lowercase()
            REDACTION_SENTINELS.forEach { sentinel ->
                normalized.requireNotContains(sentinel) {
                    "journal contains raw sensitive evidence"
                }
            }
        }

/**
 * sha256은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

/**
 * NEWLINE은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NEWLINE = byteArrayOf('\n'.code.toByte())
/**
 * REDACTION_SENTINELS은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val REDACTION_SENTINELS = setOf(
            "postgresql://",
            "redis://",
            "jdbc:postgresql:",
            "password",
            "credential",
            "authorization",
        )
/**
 * MAX_JOURNAL_BYTES은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val MAX_JOURNAL_BYTES = 16L * 1024 * 1024
    }
}

/**
 * PersistedTicketHighContentionJournalRecord 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private data class PersistedTicketHighContentionJournalRecord(
/**
 * sequence은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sequence: Long,
/**
 * previousRecordSha256은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val previousRecordSha256: String,
/**
 * payloadSha256은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val payloadSha256: String,
/**
 * payload은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val payload: TicketHighContentionJournalPayload,
)

/**
 * writeFully은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun FileChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        write(buffer)
    }
}

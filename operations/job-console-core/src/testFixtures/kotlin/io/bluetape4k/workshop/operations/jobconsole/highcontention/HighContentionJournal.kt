package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8String
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

/**
 * `HighContentionJournalEvent`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
enum class HighContentionJournalEvent {
    /**
     * `HighContentionJournalEvent.PHASE_TRANSITION`은 `phase transition` 업무 상황을 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    PHASE_TRANSITION,
    /**
     * `HighContentionJournalEvent.RESOURCE_TRANSITION`은 `resource transition` 업무 상황을 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    RESOURCE_TRANSITION,
    /**
     * `HighContentionJournalEvent.DOCKER_CREATE_INTENT`은 `docker create intent` 업무 상황을 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    DOCKER_CREATE_INTENT,
    /**
     * `HighContentionJournalEvent.DOCKER_RESOURCE_CREATED`은 `docker resource created` 업무 상황을
     * 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    DOCKER_RESOURCE_CREATED,
    /**
     * `HighContentionJournalEvent.REPORT_SERIALIZATION_FALLBACK`은 `report serialization fallback`
     * 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    REPORT_SERIALIZATION_FALLBACK,
}

/**
 * HighContentionJournalPayload 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionJournalPayload(
/**
 * event은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val event: HighContentionJournalEvent,
/**
 * fields은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val fields: Map<String, String>,
)

/**
 * HighContentionJournalRecord 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionJournalRecord(
/**
 * sequence은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sequence: Long,
/**
 * previousRecordSha256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val previousRecordSha256: String,
/**
 * payloadSha256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val payloadSha256: String,
/**
 * payload은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val payload: HighContentionJournalPayload,
/**
 * recordSha256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val recordSha256: String,
)

/**
 * HighContentionJournalException 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionJournalException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * HighContentionJournal 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionJournal private constructor(
/**
 * path은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val path: Path,
/**
 * channel은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val channel: FileChannel,
/**
 * forbiddenSentinels은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val forbiddenSentinels: Set<String>,
) : AutoCloseable {

/**
 * mapper은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val mapper = Jackson.createDefaultJsonMapper()
/**
 * nextSequence은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var nextSequence = 0L
/**
 * previousRecordSha256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var previousRecordSha256 = GENESIS_SHA256
/**
 * closed은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var closed = false

    @Synchronized
/**
 * append은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun append(
        event: HighContentionJournalEvent,
        fields: Map<String, String>,
    ): HighContentionJournalRecord {
        check(!closed) { "journal is closed" }
/**
 * payload은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payload = HighContentionJournalPayload(
            event = event,
            fields = fields
                .mapKeys { (key, _) -> key.requireNotBlank("journal field name") }
                .mapValues { (_, value) -> value }
                .toSortedMap(),
        )
/**
 * payloadBytes은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payloadBytes = mapper.writeValueAsBytes(payload)
        HighContentionRedaction.verify(payloadBytes.toUtf8String(), forbiddenSentinels)
/**
 * payloadSha256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payloadSha256 = sha256(payloadBytes)
/**
 * persisted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val persisted = PersistedJournalRecord(
            sequence = nextSequence,
            previousRecordSha256 = previousRecordSha256,
            payloadSha256 = payloadSha256,
            payload = payload,
        )
/**
 * recordBytes은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val recordBytes = mapper.writeValueAsBytes(persisted)
        writeFully(channel, ByteBuffer.wrap(recordBytes))
        writeFully(channel, ByteBuffer.wrap(NEWLINE))
        channel.force(true)

/**
 * recordSha256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val recordSha256 = sha256(recordBytes)
/**
 * record은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * close은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
/**
 * GENESIS_SHA256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val GENESIS_SHA256: String =
            "0000000000000000000000000000000000000000000000000000000000000000"

/**
 * open은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun open(
            artifactRoot: Path,
            relativePath: Path,
            forbiddenSentinels: Set<String> = emptySet(),
        ): HighContentionJournal {
/**
 * trustedRoot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val trustedRoot = HighContentionArtifactPaths.ensureTrustedDirectory(artifactRoot)
/**
 * path은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val path = HighContentionArtifactPaths.resolveCreateTarget(trustedRoot, relativePath)
/**
 * createdChannel은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var createdChannel: FileChannel? = null
            return try {
/**
 * channel은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val channel = FileChannel.open(path, CREATE_NEW, WRITE)
                createdChannel = channel
/**
 * realPath은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * read은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * decodeCompleteLines은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
                .toUtf8String()
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

/**
 * isCompleteJsonDocument은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * writeFully은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }

/**
 * sha256은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

/**
 * NEWLINE은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NEWLINE = byteArrayOf('\n'.code.toByte())
/**
 * MAX_JOURNAL_BYTES은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val MAX_JOURNAL_BYTES = 16L * 1024 * 1024
    }
}

/**
 * PersistedJournalRecord 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private data class PersistedJournalRecord(
/**
 * sequence은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sequence: Long,
/**
 * previousRecordSha256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val previousRecordSha256: String,
/**
 * payloadSha256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val payloadSha256: String,
/**
 * payload은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val payload: HighContentionJournalPayload,
)

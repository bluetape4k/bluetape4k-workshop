package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND

/**
 * HighContentionJournalTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionJournalTest {

    companion object: KLogging()

    @TempDir
/**
 * tempDir은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    lateinit var tempDir: Path

/**
 * open은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun open(relativePath: String): HighContentionJournal =
        HighContentionJournal.open(tempDir, Path.of(relativePath))

    @Test
/**
 * 이 시나리오는 "journal persists a monotonic fsynced hash chain" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `journal persists a monotonic fsynced hash chain`() {
        val path = tempDir.resolve("run-journal.jsonl")
        log.debug { "path=$path" }

        open("run-journal.jsonl").use { journal ->
            journal.append(
                HighContentionJournalEvent.RESOURCE_TRANSITION,
                mapOf("resourceKey" to "database", "state" to "ALLOCATED"),
            )
            journal.append(
                HighContentionJournalEvent.RESOURCE_TRANSITION,
                mapOf("resourceKey" to "database", "state" to "STARTED"),
            )
        }

        val records = HighContentionJournal.read(path)
        records.forEach { log.debug { "record=$it" } }
        records.map(HighContentionJournalRecord::sequence) shouldBeEqualTo listOf(0L, 1L)
        records[0].previousRecordSha256 shouldBeEqualTo HighContentionJournal.GENESIS_SHA256
        records[1].previousRecordSha256 shouldBeEqualTo records[0].recordSha256
        records.map { it.payload.fields.getValue("state") } shouldBeEqualTo listOf("ALLOCATED", "STARTED")
    }

    @Test
/**
 * 이 시나리오는 "reader ignores only a torn final line and rejects earlier corruption" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `reader ignores only a torn final line and rejects earlier corruption`() {
        val path = tempDir.resolve("run-journal.jsonl")
        open("run-journal.jsonl").use { journal ->
            journal.append(HighContentionJournalEvent.PHASE_TRANSITION, mapOf("phase" to "setup"))
            journal.append(HighContentionJournalEvent.PHASE_TRANSITION, mapOf("phase" to "workload"))
        }
        val completeContent = Files.readString(path)
        Files.writeString(path, completeContent.removeSuffix("\n"))
        assertFailsWith<HighContentionJournalException> {
            HighContentionJournal.read(path)
        }

        Files.writeString(path, completeContent)
        Files.writeString(path, """{"sequence":2""", APPEND)

        HighContentionJournal.read(path).size shouldBeEqualTo 2

        val corrupted = Files.readString(path).replaceFirst("\"setup\"", "\"tampered\"")

        Files.writeString(path, corrupted)
        assertFailsWith<HighContentionJournalException> {
            HighContentionJournal.read(path)
        }
    }

    @Test
/**
 * 이 시나리오는 "reader rejects duplicate keys and blank complete records" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `reader rejects duplicate keys and blank complete records`() {
        val duplicatePath = tempDir.resolve("duplicate.jsonl")
        open("duplicate.jsonl").use { journal ->
            journal.append(HighContentionJournalEvent.PHASE_TRANSITION, mapOf("phase" to "setup"))
        }
        Files.writeString(
            duplicatePath,
            Files.readString(duplicatePath)
                .replaceFirst(
                    "\"sequence\":0",
                    "\"sequence\":0,\"sequence\":0",
                ),
        )
        assertFailsWith<HighContentionJournalException> {
            HighContentionJournal.read(duplicatePath)
        }

        val blankPath = tempDir.resolve("blank.jsonl")
        open("blank.jsonl").use { journal ->
            journal.append(HighContentionJournalEvent.PHASE_TRANSITION, mapOf("phase" to "setup"))
        }
        Files.writeString(blankPath, "\n", APPEND)
        assertFailsWith<HighContentionJournalException> {
            HighContentionJournal.read(blankPath)
        }
    }

    @Test
/**
 * 이 시나리오는 "journal path is create-new and cannot traverse a symlink" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `journal path is create-new and cannot traverse a symlink`() {
        val existing = tempDir.resolve("existing.jsonl")
        Files.writeString(existing, "occupied")
        assertFailsWith<HighContentionArtifactException> {
            open("existing.jsonl")
        }

        val outside = Files.createTempDirectory("high-contention-journal-outside")
        val link = tempDir.resolve("link")
        Files.createSymbolicLink(link, outside)
        assertFailsWith<HighContentionArtifactException> {
            HighContentionJournal.open(tempDir, Path.of("link", "journal.jsonl"))
        }
    }

    @Test
/**
 * 이 시나리오는 "journal rejects redaction sentinels before fsync" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `journal rejects redaction sentinels before fsync`() {
        val path = tempDir.resolve("redaction.jsonl")
        open("redaction.jsonl").use { journal ->
            assertFailsWith<HighContentionRedactionException> {
                journal.append(
                    HighContentionJournalEvent.PHASE_TRANSITION,
                    mapOf("detail" to "redis://user:secret@private-host"),
                )
            }
        }

        HighContentionJournal.read(path).shouldBeEmpty()
    }
}

package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND

class HighContentionJournalTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `journal persists a monotonic fsynced hash chain`() {
        val path = tempDir.resolve("run-journal.jsonl")

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
        records.map(HighContentionJournalRecord::sequence) shouldBeEqualTo listOf(0L, 1L)
        records[0].previousRecordSha256 shouldBeEqualTo HighContentionJournal.GENESIS_SHA256
        records[1].previousRecordSha256 shouldBeEqualTo records[0].recordSha256
        records.map { it.payload.fields.getValue("state") } shouldBeEqualTo listOf("ALLOCATED", "STARTED")
    }

    @Test
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
    fun `reader rejects duplicate keys and blank complete records`() {
        val duplicatePath = tempDir.resolve("duplicate.jsonl")
        open("duplicate.jsonl").use { journal ->
            journal.append(HighContentionJournalEvent.PHASE_TRANSITION, mapOf("phase" to "setup"))
        }
        Files.writeString(
            duplicatePath,
            Files.readString(duplicatePath).replaceFirst(
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

        HighContentionJournal.read(path) shouldBeEqualTo emptyList()
    }

    private fun open(relativePath: String): HighContentionJournal =
        HighContentionJournal.open(tempDir, Path.of(relativePath))
}

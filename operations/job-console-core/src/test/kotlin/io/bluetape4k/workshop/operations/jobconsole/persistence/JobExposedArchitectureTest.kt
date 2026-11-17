package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** 저장소의 일반 DB 경로가 Exposed transaction/DSL을 계속 사용하도록 고정합니다. */
class JobExposedArchitectureTest {

    @Test
    fun `generic repository paths use Exposed and raw SQL is explicitly bounded`() {
        val repository = source("JobRepository.kt")
        val outbox = source("JobOutboxRepository.kt")
        val idempotency = source("JobSubmissionIdempotencyRepository.kt")

        repository.contains("fun load(jobId: UUID): StoredJob? = jdbc.exposedTransaction").shouldBeTrue()
        repository.contains("fun loadTenantJobs(tenantId: String): List<StoredJob> = jdbc.exposedTransaction").shouldBeTrue()
        repository.contains("private fun queryQueueRows").shouldBeTrue()
        repository.contains(".selectAll()").shouldBeTrue()
        repository.contains("PostgreSQL 제어 경로").shouldBeTrue()
        repository.contains("dataSource.connection").shouldBeFalse()

        outbox.contains("fun markPublished(token: UUID, eventId: UUID): Boolean = jdbc.exposedTransaction").shouldBeTrue()
        outbox.contains("JobOutboxTable.update").shouldBeTrue()
        outbox.contains("PostgreSQL 전용 claim 제어").shouldBeTrue()

        idempotency.contains(".selectAll()").shouldBeTrue()
        idempotency.contains(".insert").shouldBeTrue()
        idempotency.contains(".update").shouldBeTrue()
        idempotency.contains("metadata가 없는 test double").shouldBeTrue()
        idempotency.contains("ctid").shouldBeTrue()
    }

    private fun source(fileName: String): String {
        var directory: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            val candidate = directory?.resolve(
                "operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/$fileName",
            )
            if (candidate != null && Files.isRegularFile(candidate)) return Files.readString(candidate)
            directory = directory?.parent
        }
        error("소스 파일을 찾을 수 없습니다: $fileName")
    }
}

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
        val fixture = testFixtureSource("JobConsoleDatabaseFixture.kt")
        val queuePlan = workspaceSource(
            "operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/queue/QueueQueryPlanTest.kt",
        )
        val idempotencyPlan = workspaceSource(
            "operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyQueryPlanTest.kt",
        )
        val authorityQueries = testFixtureSource("JobConsoleAuthorityQueries.kt")
        val adapterProfiles = listOf(
            workspaceSource(
                "operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/KtorJobConsoleProfileServer.kt",
            ),
            workspaceSource(
                "operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringJobConsoleProfileApplication.kt",
            ),
        )

        repository.contains("fun load(jobId: UUID): StoredJob? = jdbc.exposedTransaction").shouldBeTrue()
        repository.contains("fun loadTenantJobs(tenantId: String): List<StoredJob> = jdbc.exposedTransaction").shouldBeTrue()
        val tenantListing = repository.substringAfter("fun loadTenantJobs(tenantId: String): List<StoredJob> = jdbc.exposedTransaction")
            .substringBefore("/** PostgreSQL")
        tenantListing.contains("leftJoin(JobCheckpointTable").shouldBeTrue()
        tenantListing.contains("JobCheckpointTable.select").shouldBeFalse()
        repository.contains("private fun queryQueueRows").shouldBeTrue()
        repository.contains(".selectAll()").shouldBeTrue()
        repository.contains("jdbc.exposedTransaction { select(CurrentTimestamp).singleOrNull() != null }").shouldBeTrue()
        repository.contains("JobAttemptTable.insert").shouldBeTrue()
        repository.contains("connection.prepareStatement(\"SELECT 1\")").shouldBeFalse()
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

        fixture.contains("fun count(table: String): Long = transaction(database)").shouldBeTrue()
        fixture.contains("countWhere").shouldBeFalse()
        fixture.contains("countQuery").shouldBeFalse()
        fixture.contains("SELECT count(*) FROM").shouldBeFalse()
        fixture.contains("fun explainPlan(").shouldBeTrue()
        listOf(queuePlan, idempotencyPlan).forEach { planSource ->
            planSource.contains("fixture.explainPlan").shouldBeTrue()
            planSource.contains("EXPLAIN ").shouldBeFalse()
        }
        authorityQueries.contains("JobTable.selectAll().count()").shouldBeTrue()
        authorityQueries.contains("connection.createStatement").shouldBeFalse()
        authorityQueries.contains("SELECT count(*)").shouldBeFalse()
        authorityQueries.contains("JobJdbcExecutor(dataSource).use").shouldBeTrue()
        authorityQueries.contains("Database.connect").shouldBeFalse()
        authorityQueries.contains("TransactionManager.closeAndUnregister(database)").shouldBeFalse()
        adapterProfiles.forEach { profile ->
            profile.contains("JobConsoleAuthorityQueries").shouldBeTrue()
            profile.contains("scalarCount(").shouldBeFalse()
            profile.contains("countRows(").shouldBeFalse()
            profile.contains("SELECT count(*)").shouldBeFalse()
        }
    }

    private fun testFixtureSource(fileName: String): String {
        var directory: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            val candidate = listOfNotNull(
                directory?.resolve(
                    "operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/$fileName",
                ),
                directory?.resolve(
                    "operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/$fileName",
                ),
            ).firstOrNull(Files::isRegularFile)
            if (candidate != null) return Files.readString(candidate)
            directory = directory?.parent
        }
        error("테스트 fixture 소스 파일을 찾을 수 없습니다: $fileName")
    }

    private fun source(fileName: String): String {
        return workspaceSource(
            "operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/$fileName",
        )
    }

    private fun workspaceSource(relativePath: String): String {
        var directory: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            val candidate = directory?.resolve(relativePath)
            if (candidate != null && Files.isRegularFile(candidate)) return Files.readString(candidate)
            directory = directory?.parent
        }
        error("소스 파일을 찾을 수 없습니다: $relativePath")
    }
}

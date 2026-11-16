package io.bluetape4k.workshop.operations.jobconsole.queue

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

@Tag("integration")
class QueueQueryPlanTest {

    companion object: KLogging() {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }

    @Test
    fun `queue reads are tenant scoped and samples are bounded`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            val submitted = (0 until 120).map { index ->
                repository.submit(scope, "key-$index", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1), NOW)
            }

            repository.queueRows("tenant-a", afterSequence = null, limit = 1_000).size shouldBeEqualTo 100
            repository.queueRows("tenant-b", afterSequence = null, limit = 10).size shouldBeEqualTo 0

            val service = JobConsoleService(repository)
            service.snapshot(scope, submitted.last().jobId).queue?.position shouldBeEqualTo 120

            val firstPage = service.tenantQueue(scope.tenantId, null, 100)
            firstPage.items.size shouldBeEqualTo 100

            val secondPage = service.tenantQueue(scope.tenantId, firstPage.nextCursor, 100)
            secondPage.items.size shouldBeEqualTo 20
            secondPage.nextCursor.shouldBeNull()

            repeat(120) { index ->
                repository.recordDuration(JobType.DOCUMENT_EXPORT, (index + 1).seconds(), NOW)
            }
            repository.recordDuration(JobType.REPORT_GENERATION, 999.seconds(), NOW)
            repository.durationSamples(
                JobType.DOCUMENT_EXPORT,
                NOW - 1.seconds(),
                limit = 1_000
            ).size shouldBeEqualTo 100

            fixture.execute("ANALYZE jobs")
            val plan = fixture.queryLines(
                "EXPLAIN SELECT job_id FROM jobs WHERE tenant_id = 'tenant-a' AND state = 'queued' ORDER BY enqueue_sequence LIMIT 25",
            ).joinToString("\n")

            log.debug { "plan=$plan" }
            (plan.contains("ix_job_tenant_queue") || plan.contains("Index Scan")).shouldBeTrue()
        }
    }
}

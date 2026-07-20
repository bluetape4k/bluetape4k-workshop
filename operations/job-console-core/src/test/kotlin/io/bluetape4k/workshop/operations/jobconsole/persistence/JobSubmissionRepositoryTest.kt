package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

@Tag("integration")
class JobSubmissionRepositoryTest {

    @Test
    fun `same key and request replays the existing job without new rows`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            val request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)

            val first = repository.submit(scope, "request-key", request, NOW)
            val replay = repository.submit(scope, "request-key", request, NOW.plusSeconds(1))

            replay.jobId shouldBeEqualTo first.jobId
            replay.replayed shouldBeEqualTo true
            replay.enqueueSequence shouldBeEqualTo first.enqueueSequence
            fixture.count("jobs") shouldBeEqualTo 1L
            fixture.count("job_requests") shouldBeEqualTo 1L
            fixture.count("job_outbox") shouldBeEqualTo 1L
            fixture.count("job_history") shouldBeEqualTo 1L
        }
    }

    @Test
    fun `same key and different request returns stable conflict`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            repository.submit(scope, "request-key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10), NOW)

            val failure =
                assertFailsWith<JobRepositoryException> {
                    repository.submit(scope, "request-key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 11), NOW)
                }

            failure.code shouldBeEqualTo JobProblemCode.IDEMPOTENCY_KEY_REUSED
            fixture.count("jobs") shouldBeEqualTo 1L
        }
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-07-21T00:00:00Z")
    }
}

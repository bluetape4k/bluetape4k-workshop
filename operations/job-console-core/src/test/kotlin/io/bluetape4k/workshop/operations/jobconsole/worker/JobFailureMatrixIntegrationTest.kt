package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@Tag("integration")
class JobFailureMatrixIntegrationTest {
    @Test
    fun `retry once resumes the same queue identity and succeeds`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val submitted = repository.submit(SCOPE, "retry-once", request(FailureMode.RETRY_ONCE), NOW)
            val sequence = submitted.enqueueSequence

            runOnce(repository)
            repository.load(submitted.jobId)?.state shouldBeEqualTo JobState.QUEUED
            runOnce(repository)

            val stored = requireNotNull(repository.load(submitted.jobId))
            stored.state shouldBeEqualTo JobState.SUCCEEDED
            stored.enqueueSequence shouldBeEqualTo sequence
        }
    }

    @Test
    fun `non retryable failure terminates immediately`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val submitted = repository.submit(SCOPE, "fail", request(FailureMode.NON_RETRYABLE), NOW)

            runOnce(repository)

            repository.load(submitted.jobId)?.state shouldBeEqualTo JobState.FAILED
        }
    }

    private fun runOnce(repository: JobRepository) {
        val claim = requireNotNull(repository.claimNext(SCOPE.tenantId, Duration.ofSeconds(30)))
        JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)
    }

    private fun request(mode: FailureMode) = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 2, mode)

    companion object {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
        private val SCOPE = DemoCallerScope("tenant-a", "submitter-a")
    }
}

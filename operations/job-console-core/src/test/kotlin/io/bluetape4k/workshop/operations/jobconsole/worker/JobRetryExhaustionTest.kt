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
class JobRetryExhaustionTest {
    @Test
    fun `retry exhaustion emits exactly one dead letter transition`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val submitted =
                repository.submit(
                    DemoCallerScope("tenant-a", "submitter-a"),
                    "always-fail",
                    SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1, FailureMode.ALWAYS_RETRYABLE),
                    NOW,
                )

            repeat(3) {
                val claim = requireNotNull(repository.claimNext("tenant-a", Duration.ofSeconds(30)))
                JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)
            }

            repository.load(submitted.jobId)?.state shouldBeEqualTo JobState.DEAD_LETTERED
            fixture.countWhere("job_history", "to_state = 'dead_lettered'") shouldBeEqualTo 1L
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }
}

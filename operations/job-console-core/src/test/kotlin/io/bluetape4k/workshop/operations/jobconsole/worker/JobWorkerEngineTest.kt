package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.assertions.shouldBeEqualTo
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
class JobWorkerEngineTest {
    @Test
    fun `run once discovers queued tenant and completes its oldest job`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-discovery", "submitter-a")
            val submitted =
                repository.submit(
                    scope,
                    "discovered-job",
                    SubmitJobRequest(JobType.DOCUMENT_EXPORT, 2),
                    Instant.parse("2026-07-21T00:00:00Z"),
                )

            val processed = JobWorkerEngine(repository, DeterministicJobWorkload()).runOnce()

            processed shouldBeEqualTo true
            requireNotNull(repository.load(submitted.jobId)).state shouldBeEqualTo JobState.SUCCEEDED
        }
    }


    @Test
    fun `worker checkpoints every unit and completes exactly once`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val submitted =
                repository.submit(
                    DemoCallerScope("tenant-a", "submitter-a"),
                    "job-key",
                    SubmitJobRequest(JobType.DOCUMENT_EXPORT, 3),
                    Instant.parse("2026-07-21T00:00:00Z"),
                )
            val claim = requireNotNull(repository.claimNext("tenant-a", Duration.ofSeconds(30)))

            JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)

            val stored = requireNotNull(repository.load(submitted.jobId))
            stored.state shouldBeEqualTo JobState.SUCCEEDED
            stored.progress shouldBeEqualTo 100
            stored.completedChunk shouldBeEqualTo 3L
            repository.durationSamples(JobType.DOCUMENT_EXPORT, Instant.EPOCH, 10).size shouldBeEqualTo 1
        }
    }
}

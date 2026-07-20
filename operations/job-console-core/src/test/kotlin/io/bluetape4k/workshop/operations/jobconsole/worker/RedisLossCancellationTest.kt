package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.signal.CancelSignal
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Tag("integration")
class RedisLossCancellationTest {

    @Test
    fun `missing advisory signal is reported degraded while durable cancellation succeeds`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-no-redis", "submitter-a")
            val submitted = repository.submit(scope, "job-key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 3), NOW)
            repository.claimNext(scope.tenantId, Duration.ofSeconds(30))

            val outcome = JobConsoleService(repository).cancel(scope, submitted.jobId)

            outcome.state shouldBeEqualTo JobState.CANCEL_REQUESTED
            outcome.signalDegraded shouldBeEqualTo true
        }
    }

    @Test
    fun `signal loss does not roll back durable running cancellation`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            val submitted = repository.submit(scope, "job-key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 3), NOW)
            val claim = requireNotNull(repository.claimNext("tenant-a", Duration.ofSeconds(30)))
            val service = JobConsoleService(repository, FailingCancelSignal)

            val outcome = service.cancel(scope, submitted.jobId)

            outcome.state shouldBeEqualTo JobState.CANCEL_REQUESTED
            outcome.signalDegraded shouldBeEqualTo true
            repository.checkpoint(claim.lease, completedChunk = 0, progress = 0).state shouldBeEqualTo JobState.CANCELLED
        }
    }

    private object FailingCancelSignal : CancelSignal {
        override fun publish(jobId: UUID) = error("redis unavailable")
    }

    companion object {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }
}

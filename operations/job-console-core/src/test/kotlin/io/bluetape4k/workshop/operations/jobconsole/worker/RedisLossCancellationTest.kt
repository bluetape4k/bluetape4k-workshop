package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
import java.time.Instant
import java.util.*

@Tag("integration")
class RedisLossCancellationTest {

    companion object: KLogging() {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }

    @Test
    fun `missing advisory signal is reported degraded while durable cancellation succeeds`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-no-redis", "submitter-a")
            val submitted = repository.submit(
                scope,
                "job-key",
                SubmitJobRequest(JobType.DOCUMENT_EXPORT, 3),
                NOW
            )
            repository.claimNext(scope.tenantId, 30.seconds())

            val outcome = JobConsoleService(repository).cancel(scope, submitted.jobId)

            log.debug { "outcome=$outcome" }
            outcome.state shouldBeEqualTo JobState.CANCEL_REQUESTED
            outcome.signalDegraded.shouldBeTrue()
        }
    }

    @Test
    fun `signal loss does not roll back durable running cancellation`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            val submitted = repository.submit(
                scope,
                "job-key",
                SubmitJobRequest(JobType.DOCUMENT_EXPORT, 3),
                NOW
            )
            val claim = repository.claimNext("tenant-a", 30.seconds()).shouldNotBeNull()
            val service = JobConsoleService(repository, FailingCancelSignal)

            val outcome = service.cancel(scope, submitted.jobId)

            log.debug { "outcome=$outcome" }
            outcome.state shouldBeEqualTo JobState.CANCEL_REQUESTED
            outcome.signalDegraded.shouldBeTrue()

            repository.checkpoint(
                claim.lease,
                completedChunk = 0,
                progress = 0
            ).state shouldBeEqualTo JobState.CANCELLED
        }
    }

    private object FailingCancelSignal: CancelSignal {
        override fun publish(jobId: UUID) = error("redis unavailable")
    }
}

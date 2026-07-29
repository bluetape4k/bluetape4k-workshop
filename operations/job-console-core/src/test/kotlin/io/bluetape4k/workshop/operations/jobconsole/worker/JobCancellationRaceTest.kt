package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Tag("integration")
class JobCancellationRaceTest {

    @Test
    fun `queued cancel and claim race converges to one cancelled result`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            val submitted = repository.submit(scope, "job-key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 3), NOW)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)

            val claim: ClaimedJob? =
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    val claimFuture =
                        executor.submit<ClaimedJob?> {
                            ready.countDown()
                            start.await()
                            repository.claimNext("tenant-a", Duration.ofSeconds(30))
                        }
                    val cancelFuture =
                        executor.submit {
                            ready.countDown()
                            start.await()
                            repository.cancel(scope, submitted.jobId)
                        }
                    ready.await()
                    start.countDown()
                    val claimed = claimFuture.get()
                    cancelFuture.get()
                    claimed
                }

            val afterRace = requireNotNull(repository.load(submitted.jobId))
            if (afterRace.state == JobState.CANCEL_REQUESTED) {
                val active = requireNotNull(claim)
                repository.checkpoint(active.lease, completedChunk = 0, progress = 0)
            }

            requireNotNull(repository.load(submitted.jobId)).state shouldBeEqualTo JobState.CANCELLED
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }
}

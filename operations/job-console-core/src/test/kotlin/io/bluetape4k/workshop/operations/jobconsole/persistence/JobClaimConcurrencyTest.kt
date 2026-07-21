package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Tag("integration")
class JobClaimConcurrencyTest {

    @Test
    fun `same tenant concurrent claims start only the oldest job`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            repository.submit(scope, "key-1", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 2), NOW)
            repository.submit(scope, "key-2", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 2), NOW.plusSeconds(1))
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)

            val claims =
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    List(2) {
                        executor.submit<ClaimedJob?> {
                            ready.countDown()
                            start.await()
                            repository.claimNext("tenant-a", Duration.ofSeconds(30))
                        }
                    }.also {
                        ready.await()
                        start.countDown()
                    }.map { it.get() }
                }

            claims.filterNotNull().size shouldBeEqualTo 1
            claims.filterNotNull().single().enqueueSequence shouldBeEqualTo 1L
            repository.loadTenantJobs("tenant-a").count { it.state.active } shouldBeEqualTo 1
        }
    }

    @Test
    fun `different tenants claim independently`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            repository.submit(DemoCallerScope("tenant-a", "a"), "key-a", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1), NOW)
            repository.submit(DemoCallerScope("tenant-b", "b"), "key-b", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1), NOW)

            repository.claimNext("tenant-a", Duration.ofSeconds(30))?.enqueueSequence shouldBeEqualTo 1L
            repository.claimNext("tenant-b", Duration.ofSeconds(30))?.enqueueSequence shouldBeEqualTo 1L
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }
}

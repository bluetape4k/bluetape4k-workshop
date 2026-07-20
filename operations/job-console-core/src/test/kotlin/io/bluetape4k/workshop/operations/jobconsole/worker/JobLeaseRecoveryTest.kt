package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@Tag("integration")
class JobLeaseRecoveryTest {

    @Test
    fun `expired lease is reclaimed and stale worker cannot checkpoint`() {
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
            val stale = requireNotNull(repository.claimNext("tenant-a", Duration.ofSeconds(30)))
            repository.checkpoint(stale.lease, completedChunk = 1, progress = 33)
            fixture.execute("UPDATE jobs SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE job_id = '${submitted.jobId}'")

            val recovered = requireNotNull(repository.reclaimExpired("tenant-a", Duration.ofSeconds(30)))

            (recovered.lease.token == stale.lease.token) shouldBeEqualTo false
            recovered.completedChunk shouldBeEqualTo 1L
            assertFailsWith<JobRepositoryException> {
                repository.checkpoint(stale.lease, completedChunk = 2, progress = 66)
            }.code shouldBeEqualTo JobProblemCode.LEASE_LOST
            repository.checkpoint(recovered.lease, completedChunk = 2, progress = 66).completedChunk shouldBeEqualTo 2L
        }
    }
}

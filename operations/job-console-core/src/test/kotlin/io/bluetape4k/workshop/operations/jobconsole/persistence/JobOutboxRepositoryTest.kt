package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.JobEventType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@Tag("integration")
class JobOutboxRepositoryTest {
    @Test
    fun `each durable change notifies both job and queue snapshot consumers`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            JobRepository(fixture.dataSource).submit(SCOPE, "key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1), NOW)

            val events = JobOutboxRepository(fixture.dataSource).claim(10, Duration.ofSeconds(30)).events

            events.map { it.eventType }.toSet() shouldBeEqualTo
                setOf(JobEventType.JOB_UPDATED, JobEventType.QUEUE_UPDATED)
        }
    }

    @Test
    fun `claim is bounded and failed publication can be retried with stable event id`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val jobs = JobRepository(fixture.dataSource)
            val outbox = JobOutboxRepository(fixture.dataSource)
            repeat(5) { index ->
                jobs.submit(SCOPE, "key-$index", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1), NOW)
            }

            val firstClaim = outbox.claim(batchSize = 2, claimDuration = Duration.ofSeconds(30))
            firstClaim.events.size shouldBeEqualTo 2
            outbox.release(firstClaim.token, firstClaim.events.first().eventId)

            val retry = outbox.claim(batchSize = 1, claimDuration = Duration.ofSeconds(30)).events.single()
            retry.eventId shouldBeEqualTo firstClaim.events.first().eventId
            outbox.markPublished(firstClaim.token, firstClaim.events.last().eventId) shouldBeEqualTo true
            outbox.markPublished(firstClaim.token, firstClaim.events.last().eventId) shouldBeEqualTo true
        }
    }

    @Test
    fun `oldest unpublished age is observable without exposing payload`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            JobRepository(fixture.dataSource).submit(SCOPE, "key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1), NOW)

            JobOutboxRepository(fixture.dataSource).oldestUnpublishedAge(NOW.plusSeconds(10)) shouldBeEqualTo Duration.ofSeconds(10)
        }
    }

    @Test
    fun `durable job and outbox identities use UUID version seven`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val jobs = JobRepository(fixture.dataSource)
            val submitted =
                jobs.submit(
                    SCOPE,
                    "v7-key",
                    SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1),
                    NOW,
                )
            val lease = requireNotNull(jobs.claimNext(SCOPE.tenantId, Duration.ofSeconds(30)))
            val claimed = JobOutboxRepository(fixture.dataSource).claim(10, Duration.ofSeconds(30))

            submitted.jobId.version() shouldBeEqualTo 7
            lease.lease.token.version() shouldBeEqualTo 7
            claimed.token.version() shouldBeEqualTo 7
            claimed.events.map { it.eventId.version() }.toSet() shouldBeEqualTo setOf(7)
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
        private val SCOPE = DemoCallerScope("tenant-a", "submitter-a")
    }
}

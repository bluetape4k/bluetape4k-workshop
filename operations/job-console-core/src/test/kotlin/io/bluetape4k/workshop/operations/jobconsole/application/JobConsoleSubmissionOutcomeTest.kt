package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionOutcome
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class JobConsoleSubmissionOutcomeTest {
    private val snapshot =
        JobSnapshot(
            jobId = UUID.fromString("0198af23-7b9c-7000-8000-000000000001"),
            jobType = io.bluetape4k.workshop.operations.jobconsole.api.JobType.DOCUMENT_EXPORT,
            state = JobState.QUEUED,
            progress = 0,
            checkpoint = null,
            queue = null,
            version = 1,
            updatedAt = Instant.parse("2026-08-16T00:00:00Z"),
        )

    @Test
    fun `owner and replay preserve body and differ only in replay header`() {
        val owner = JobSubmissionHttpMapper.map(JobSubmissionOutcome.OwnerCompleted(snapshot), requestId = "owner-id")
        val replay = JobSubmissionHttpMapper.map(JobSubmissionOutcome.Replayed(snapshot), requestId = "replay-id")

        owner.status shouldBeEqualTo 202
        replay.status shouldBeEqualTo 202
        owner.body.contentEquals(replay.body) shouldBeEqualTo true
        owner.contentType shouldBeEqualTo "application/json"
        replay.contentType shouldBeEqualTo "application/json"
        owner.headers["Idempotency-Replayed"] shouldBeEqualTo listOf("false")
        replay.headers["Idempotency-Replayed"] shouldBeEqualTo listOf("true")
        owner.replayed shouldBeEqualTo false
        replay.replayed shouldBeEqualTo true
    }

    @Test
    fun `problem matrix uses stable codes and retry headers`() {
        val cases =
            listOf(
                JobSubmissionOutcome.Conflict to Triple(409, JobProblemCode.IDEMPOTENCY_KEY_REUSED, null),
                JobSubmissionOutcome.InFlightTimeout to Triple(409, JobProblemCode.IDEMPOTENCY_IN_FLIGHT, 1L),
                JobSubmissionOutcome.WaiterOverflow to Triple(429, JobProblemCode.IDEMPOTENCY_WAITERS_EXCEEDED, 2L),
                JobSubmissionOutcome.Abandoned to Triple(503, JobProblemCode.DEPENDENCY_UNAVAILABLE, null),
            )
        val mapper = Jackson.defaultJsonMapper
        cases.forEach { (outcome, expected) ->
            val response = JobSubmissionHttpMapper.map(outcome, requestId = "request-id")
            val tree = mapper.readTree(response.body)
            response.status shouldBeEqualTo expected.first
            response.contentType shouldBeEqualTo "application/problem+json"
            response.replayed shouldBeEqualTo false
            tree["status"].asInt() shouldBeEqualTo expected.first
            tree["code"].asString() shouldBeEqualTo expected.second.wireValue
            if (expected.third == null) {
                tree["retryAfterSeconds"].isNull shouldBeEqualTo true
                response.headers shouldBeEqualTo emptyMap()
            } else {
                tree["retryAfterSeconds"].asLong() shouldBeEqualTo expected.third
                response.headers["Retry-After"] shouldBeEqualTo listOf(expected.third.toString())
            }
            response.headers.containsKey("Idempotency-Replayed") shouldBeEqualTo false
        }
    }

    @Test
    @Tag("integration")
    fun `service returns a stable replay outcome without duplicating durable rows`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val service = JobConsoleService(JobRepository(fixture.dataSource))
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            val request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, workUnits = 3)

            val first = service.submit(scope, "outcome-key", request)
            val replay = service.submit(scope, "outcome-key", request)
            val conflict = service.submit(scope, "outcome-key", request.copy(workUnits = 4))

            check(first is JobSubmissionOutcome.OwnerCompleted)
            check(replay is JobSubmissionOutcome.Replayed)
            first.snapshot shouldBeEqualTo replay.snapshot
            conflict shouldBeEqualTo JobSubmissionOutcome.Conflict
            fixture.count("jobs") shouldBeEqualTo 1L
            fixture.count("job_outbox") shouldBeEqualTo 2L
            fixture.count("job_history") shouldBeEqualTo 1L
        }
    }

    @Test
    @Tag("integration")
    fun `readiness exposes policy mismatch and closed admission rejects new submissions`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val service =
                JobConsoleService(
                    repository = JobRepository(fixture.dataSource),
                    boundedWaitEnabled = false,
                    expectedPolicyFingerprint = "mismatched-policy",
                )
            val readiness = service.readiness()
            readiness.ready shouldBeEqualTo false
            readiness.postgres shouldBeEqualTo io.bluetape4k.workshop.operations.jobconsole.observability.DependencyState.UP
            readiness.boundedWaitEnabled shouldBeEqualTo false
            readiness.reason shouldBeEqualTo "policy"
            readiness.policyFingerprint.isNotBlank() shouldBeEqualTo true

            service.closeAdmission()
            service.isAcceptingSubmissions() shouldBeEqualTo false
            val failure =
                assertFailsWith<JobRepositoryException> {
                    service.submit(
                        DemoCallerScope("tenant-a", "submitter-a"),
                        "closed-admission-key",
                        SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1),
                    )
                }
            failure.code shouldBeEqualTo JobProblemCode.DEPENDENCY_UNAVAILABLE
            service.activeSubmissionCount() shouldBeEqualTo 0
        }
    }
}

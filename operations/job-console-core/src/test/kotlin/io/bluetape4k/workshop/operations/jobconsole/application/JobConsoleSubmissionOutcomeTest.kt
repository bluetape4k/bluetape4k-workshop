package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionOutcome
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.observability.DependencyState
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * service outcome을 HTTP response로 매핑하는 경계를 검증한다.
 * 최초 owner, replay, conflict, bounded wait timeout/overflow, readiness와
 * shutdown admission이 호출자에게 안정적인 status/code/header로 보이는지 확인한다.
 */
class JobConsoleSubmissionOutcomeTest {

    companion object: KLogging()

/**
 * snapshot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val snapshot = JobSnapshot(
        jobId = UUID.fromString("0198af23-7b9c-7000-8000-000000000001"),
        jobType = JobType.DOCUMENT_EXPORT,
        state = JobState.QUEUED,
        progress = 0,
        checkpoint = null,
        queue = null,
        version = 1,
        updatedAt = Instant.parse("2026-08-16T00:00:00Z"),
    )

    @Test
    /** owner와 replay는 body를 공유하고 replay 여부만 명시적인 header로 구분한다. */
    /** owner와 replay는 body를 공유하고 replay 여부만 명시적인 header로 구분한다. */
    fun `owner and replay preserve body and differ only in replay header`() {
        val owner = JobSubmissionHttpMapper.map(JobSubmissionOutcome.OwnerCompleted(snapshot), requestId = "owner-id")
        val replay = JobSubmissionHttpMapper.map(JobSubmissionOutcome.Replayed(snapshot), requestId = "replay-id")

        owner.status shouldBeEqualTo 202
        replay.status shouldBeEqualTo 202
        owner.body.contentEquals(replay.body).shouldBeTrue()
        owner.contentType shouldBeEqualTo "application/json"
        replay.contentType shouldBeEqualTo "application/json"
        owner.headers["Idempotency-Replayed"] shouldBeEqualTo listOf("false")
        replay.headers["Idempotency-Replayed"] shouldBeEqualTo listOf("true")
        owner.replayed.shouldBeFalse()
        replay.replayed.shouldBeTrue()
    }

    @Test
    /** 각 실패 outcome의 stable problem code와 호출자 backoff용 retry header를 검증한다. */
    /** 각 실패 outcome의 stable problem code와 호출자 backoff용 retry header를 검증한다. */
    fun `problem matrix uses stable codes and retry headers`() {
        val cases = listOf(
            JobSubmissionOutcome.Conflict to Triple(409, JobProblemCode.IDEMPOTENCY_KEY_REUSED, null),
            JobSubmissionOutcome.InFlightTimeout to Triple(409, JobProblemCode.IDEMPOTENCY_IN_FLIGHT, 1L),
            JobSubmissionOutcome.WaiterOverflow to Triple(429, JobProblemCode.IDEMPOTENCY_WAITERS_EXCEEDED, 2L),
            JobSubmissionOutcome.Abandoned to Triple(503, JobProblemCode.DEPENDENCY_UNAVAILABLE, null),
        )

        val mapper = Jackson.defaultJsonMapper
        cases.forEach { (outcome, expected) ->
            val response = JobSubmissionHttpMapper.map(outcome, requestId = "request-id")
            val tree = mapper.readTree(response.body)

            log.debug { "response=$response" }
            log.debug { "tree=$tree" }

            response.status shouldBeEqualTo expected.first
            response.contentType shouldBeEqualTo "application/problem+json"
            response.replayed.shouldBeFalse()

            tree["status"].asInt() shouldBeEqualTo expected.first
            tree["code"].asString() shouldBeEqualTo expected.second.wireValue
            if (expected.third == null) {
                tree["retryAfterSeconds"].isNull.shouldBeTrue()
                response.headers shouldBeEqualTo emptyMap()
            } else {
                tree["retryAfterSeconds"].asLong() shouldBeEqualTo expected.third
                response.headers["Retry-After"] shouldBeEqualTo listOf(expected.third.toString())
            }
            response.headers.containsKey("Idempotency-Replayed").shouldBeFalse()
        }
    }

    @Test
    @Tag("integration")
    /** 같은 canonical request를 재전송해도 job/outbox/history durable row가 중복되지 않는다. */
    /** 같은 canonical request를 재전송해도 job/outbox/history durable row가 중복되지 않는다. */
    fun `service returns a stable replay outcome without duplicating durable rows`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()

            val service = JobConsoleService(JobRepository(fixture.dataSource))
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            val request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, workUnits = 3)

            val first = service.submit(scope, "outcome-key", request)
            val replay = service.submit(scope, "outcome-key", request)
            val conflict = service.submit(scope, "outcome-key", request.copy(workUnits = 4))

            first.shouldBeInstanceOf<JobSubmissionOutcome.OwnerCompleted>()
            replay.shouldBeInstanceOf<JobSubmissionOutcome.Replayed>()

            first.snapshot shouldBeEqualTo replay.snapshot
            conflict shouldBeEqualTo JobSubmissionOutcome.Conflict

            fixture.count("jobs") shouldBeEqualTo 1L
            fixture.count("job_outbox") shouldBeEqualTo 2L
            fixture.count("job_history") shouldBeEqualTo 1L
        }
    }

    @Test
    @Tag("integration")
    /** policy fingerprint가 맞지 않거나 admission을 닫으면 신규 submission을 fail closed한다. */
    /** policy fingerprint가 맞지 않거나 admission을 닫으면 신규 submission을 fail closed한다. */
    fun `readiness exposes policy mismatch and closed admission rejects new submissions`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val service = JobConsoleService(
                repository = JobRepository(fixture.dataSource),
                boundedWaitEnabled = false,
                expectedPolicyFingerprint = "mismatched-policy",
            )

            val readiness = service.readiness()
            log.debug { "readiness=$readiness" }
            readiness.ready.shouldBeFalse()
            readiness.postgres shouldBeEqualTo DependencyState.UP
            readiness.boundedWaitEnabled.shouldBeFalse()
            readiness.reason shouldBeEqualTo "policy"
            readiness.policyFingerprint.shouldNotBeBlank()

            service.closeAdmission()
            service.isAcceptingSubmissions().shouldBeFalse()

            val failure = assertFailsWith<JobRepositoryException> {
                service.submit(
                    DemoCallerScope("tenant-a", "submitter-a"),
                    "closed-admission-key",
                    SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1),
                )
            }
            log.debug { "failure=$failure" }
            failure.code shouldBeEqualTo JobProblemCode.DEPENDENCY_UNAVAILABLE
            service.activeSubmissionCount() shouldBeEqualTo 0
        }
    }
}

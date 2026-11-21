package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/** legacy V001 제출 경로가 동일 key replay와 다른 request conflict를 안정적으로 보존하는지 검증한다. */
/** legacy V001 제출 경로가 동일 key replay와 다른 request conflict를 안정적으로 보존하는지 검증한다. */
@Tag("integration")
class JobSubmissionRepositoryTest {

    companion object: KLogging() {
/**
 * NOW은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW: Instant = Instant.parse("2026-07-21T00:00:00Z")
    }

    @Test
/**
 * 이 시나리오는 "same key and request replays the existing job without new rows" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same key and request replays the existing job without new rows`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            val request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)

            val first = repository.submit(scope, "request-key", request, NOW)
            val replay = repository.submit(scope, "request-key", request, NOW.plusSeconds(1))

            log.debug { "first: $first" }
            log.debug { "replay: $replay" }

            replay.jobId shouldBeEqualTo first.jobId
            replay.replayed.shouldBeTrue()
            replay.enqueueSequence shouldBeEqualTo first.enqueueSequence
            fixture.count("jobs") shouldBeEqualTo 1L
            fixture.count("job_requests") shouldBeEqualTo 1L
            fixture.count("job_outbox") shouldBeEqualTo 2L
            fixture.count("job_history") shouldBeEqualTo 1L
        }
    }

    @Test
/**
 * 이 시나리오는 "same key and different request returns stable conflict" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same key and different request returns stable conflict`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            repository.submit(scope, "request-key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10), NOW)

            val failure = assertFailsWith<JobRepositoryException> {
                repository.submit(scope, "request-key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 11), NOW)
            }

            failure.code shouldBeEqualTo JobProblemCode.IDEMPOTENCY_KEY_REUSED
            fixture.count("jobs") shouldBeEqualTo 1L
        }
    }
}

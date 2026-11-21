package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

@Tag("integration")
/**
 * 실제 PostgreSQL queue와 worker를 사용해 failureMode별 terminal 규칙을 검증한다.
 * 각 테스트는 외부 provider 대신 deterministic workload를 사용하므로 retry와
 * terminal 결과를 wall-clock 지연 없이 재현한다.
 */
class JobFailureMatrixIntegrationTest {

    companion object: KLogging() {
/**
 * NOW은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
/**
 * SCOPE은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val SCOPE = DemoCallerScope("tenant-a", "submitter-a")
    }

    @Test
    /** 첫 attempt의 retryable failure 뒤 동일 queue identity로 재개되어 성공한다. */
    /** 첫 attempt의 retryable failure 뒤 동일 queue identity로 재개되어 성공한다. */
    fun `retry once resumes the same queue identity and succeeds`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val submitted = repository.submit(SCOPE, "retry-once", request(FailureMode.RETRY_ONCE), NOW)
            val sequence = submitted.enqueueSequence

            runOnce(repository)
            repository.load(submitted.jobId)?.state shouldBeEqualTo JobState.QUEUED
            runOnce(repository)

            val stored = repository.load(submitted.jobId).shouldNotBeNull()
            log.debug { "stored=$stored" }
            stored.state shouldBeEqualTo JobState.SUCCEEDED
            stored.enqueueSequence shouldBeEqualTo sequence
        }
    }

    @Test
    /** retry 불가능한 provider 결과는 retry 없이 즉시 failed terminal로 수렴한다. */
    /** retry 불가능한 provider 결과는 retry 없이 즉시 failed terminal로 수렴한다. */
    fun `non retryable failure terminates immediately`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val submitted = repository.submit(SCOPE, "fail", request(FailureMode.NON_RETRYABLE), NOW)

            runOnce(repository)

            repository.load(submitted.jobId)?.state shouldBeEqualTo JobState.FAILED
        }
    }

/**
 * runOnce은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun runOnce(repository: JobRepository) {
        val claim = repository.claimNext(SCOPE.tenantId, 30.seconds()).shouldNotBeNull()
        JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)
    }

/**
 * request은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(mode: FailureMode): SubmitJobRequest =
        SubmitJobRequest(JobType.DOCUMENT_EXPORT, 2, mode)
}

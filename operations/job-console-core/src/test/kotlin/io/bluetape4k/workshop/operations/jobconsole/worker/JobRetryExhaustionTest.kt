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

/** retry budget 소진이 무한 재시도 대신 단 하나의 dead-letter terminal transition을 만드는지 검증한다. */
/** retry budget 소진이 무한 재시도 대신 단 하나의 dead-letter terminal transition을 만드는지 검증한다. */
@Tag("integration")
class JobRetryExhaustionTest {

    companion object: KLogging() {
/**
 * NOW은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }

    @Test
/**
 * 이 시나리오는 "retry exhaustion emits exactly one dead letter transition" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retry exhaustion emits exactly one dead letter transition`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val submitted = repository.submit(
                DemoCallerScope("tenant-a", "submitter-a"),
                "always-fail",
                SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1, FailureMode.ALWAYS_RETRYABLE),
                NOW,
            )

            repeat(3) {
                val claim = repository.claimNext("tenant-a", 30.seconds()).shouldNotBeNull()
                log.debug { "claim=$claim" }
                JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)
            }

            repository.load(submitted.jobId)?.state shouldBeEqualTo JobState.DEAD_LETTERED
            fixture.countHistoryToState("dead_lettered") shouldBeEqualTo 1L
        }
    }
}

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

/** Redis notification 유실·degraded 상태에서도 durable cancel과 다음 checkpoint 관찰이 수렴하는지 검증한다. */
/** Redis notification 유실·degraded 상태에서도 durable cancel과 다음 checkpoint 관찰이 수렴하는지 검증한다. */
@Tag("integration")
class RedisLossCancellationTest {

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
 * 이 시나리오는 "missing advisory signal is reported degraded while durable cancellation succeeds" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "signal loss does not roll back durable running cancellation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * FailingCancelSignal 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private object FailingCancelSignal: CancelSignal {
/**
 * publish은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun publish(jobId: UUID) = error("redis unavailable")
    }
}

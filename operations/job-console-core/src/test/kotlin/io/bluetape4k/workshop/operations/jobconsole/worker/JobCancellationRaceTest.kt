package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** queued claim과 cancel이 경쟁해도 PostgreSQL이 한 가지 durable 결과로 수렴시키는지 검증한다. */
/** queued claim과 cancel이 경쟁해도 PostgreSQL이 한 가지 durable 결과로 수렴시키는지 검증한다. */
@Tag("integration")
class JobCancellationRaceTest {

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
 * 이 시나리오는 "queued cancel and claim race converges to one cancelled result" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `queued cancel and claim race converges to one cancelled result`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")

            val submitted = repository.submit(
                scope = scope,
                idempotencyKey = "job-key",
                request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 3),
                now = NOW
            )
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)

            val claim: ClaimedJob? = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val claimFuture = executor.submit<ClaimedJob?> {
                    ready.countDown()
                    start.await()
                    repository.claimNext("tenant-a", 30.seconds())
                }
                val cancelFuture = executor.submit {
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
            log.debug { "claim=$claim" }

            val afterRace = repository.load(submitted.jobId).shouldNotBeNull()
            log.debug { "after-race=$afterRace" }

            if (afterRace.state == JobState.CANCEL_REQUESTED) {
                claim.shouldNotBeNull()
                repository.checkpoint(claim.lease, completedChunk = 0, progress = 0)
            }

            repository.load(submitted.jobId).shouldNotBeNull().state shouldBeEqualTo JobState.CANCELLED
        }
    }
}

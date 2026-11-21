package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/** worker의 tenant 탐색, lease claim, unit별 checkpoint, terminal commit 순서를 검증한다. */
/** worker의 tenant 탐색, lease claim, unit별 checkpoint, terminal commit 순서를 검증한다. */
@Tag("integration")
class JobWorkerEngineTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "run once discovers queued tenant and completes its oldest job" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `run once discovers queued tenant and completes its oldest job`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-discovery", "submitter-a")

            val submitted = repository.submit(
                scope,
                "discovered-job",
                SubmitJobRequest(JobType.DOCUMENT_EXPORT, 2),
                Instant.parse("2026-07-21T00:00:00Z"),
            )
            log.debug { "submitted=$submitted" }

            val processed = JobWorkerEngine(repository, DeterministicJobWorkload()).runOnce()
            processed.shouldBeTrue()

            repository.load(submitted.jobId).shouldNotBeNull().state shouldBeEqualTo JobState.SUCCEEDED
        }
    }


    @Test
/**
 * 이 시나리오는 "worker checkpoints every unit and completes exactly once" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `worker checkpoints every unit and completes exactly once`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val submitted = repository.submit(
                DemoCallerScope("tenant-a", "submitter-a"),
                "job-key",
                SubmitJobRequest(JobType.DOCUMENT_EXPORT, 3),
                Instant.parse("2026-07-21T00:00:00Z"),
            )
            val claim = repository.claimNext("tenant-a", 30.seconds()).shouldNotBeNull()

            log.debug { "claim=$claim" }
            JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)

            val stored = repository.load(submitted.jobId).shouldNotBeNull()
            log.debug { "stored=$stored" }
            stored.state shouldBeEqualTo JobState.SUCCEEDED
            stored.progress shouldBeEqualTo 100
            stored.completedChunk shouldBeEqualTo 3L

            repository.durationSamples(JobType.DOCUMENT_EXPORT, Instant.EPOCH, 10).size shouldBeEqualTo 1
        }
    }
}

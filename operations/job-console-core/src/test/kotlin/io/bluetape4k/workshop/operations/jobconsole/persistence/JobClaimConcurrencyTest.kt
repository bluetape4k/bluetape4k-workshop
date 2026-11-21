package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** 실제 PostgreSQL claim transaction이 tenant FIFO와 tenant 간 독립성을 보장하는지 검증한다. */
/** 실제 PostgreSQL claim transaction이 tenant FIFO와 tenant 간 독립성을 보장하는지 검증한다. */
@Tag("integration")
class JobClaimConcurrencyTest {

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
 * 이 시나리오는 "same tenant concurrent claims start only the oldest job" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same tenant concurrent claims start only the oldest job`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            repository.submit(scope, "key-1", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 2), NOW)
            repository.submit(scope, "key-2", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 2), NOW.plusSeconds(1))
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)

            val claims = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                List(2) {
                    executor.submit<ClaimedJob?> {
                        ready.countDown()
                        start.await()
                        repository.claimNext("tenant-a", 30.seconds())
                    }
                }.also {
                    ready.await()
                    start.countDown()
                }.map { it.get() }
            }.onEach { log.debug { "claim=$it" } }

            claims.filterNotNull().size shouldBeEqualTo 1
            claims.filterNotNull().single().enqueueSequence shouldBeEqualTo 1L
            repository.loadTenantJobs("tenant-a").count { it.state.active } shouldBeEqualTo 1
        }
    }

    @Test
/**
 * 이 시나리오는 "different tenants claim independently" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `different tenants claim independently`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()

            val repository = JobRepository(fixture.dataSource)

            repository.submit(
                DemoCallerScope("tenant-a", "a"),
                "key-a",
                SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1),
                NOW
            )

            repository.submit(
                DemoCallerScope("tenant-b", "b"),
                "key-b",
                SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1),
                NOW
            )

            val tenantANext = repository.claimNext("tenant-a", 30.seconds())
            log.debug { "tenantA - Next=$tenantANext" }
            tenantANext?.enqueueSequence shouldBeEqualTo 1L

            val tenantBNext = repository.claimNext("tenant-b", 30.seconds())
            log.debug { "tenantB - Next=$tenantBNext" }
            tenantBNext?.enqueueSequence shouldBeEqualTo 1L
        }
    }

    @Test
/**
 * 이 시나리오는 "tenant job listing keeps checkpoint values without duplicate jobs" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `tenant job listing keeps checkpoint values without duplicate jobs`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-checkpoint", "submitter-a")
            val submitted =
                (1..3).map { index ->
                    repository.submit(
                        scope,
                        "checkpoint-key-$index",
                        SubmitJobRequest(JobType.DOCUMENT_EXPORT, index),
                        NOW.plusSeconds(index.toLong()),
                    ).jobId
                }

            submitted.forEachIndexed { index, _ ->
                val claim = repository.claimNext(scope.tenantId, 30.seconds()).shouldNotBeNull()
                repository.checkpoint(
                    claim.lease,
                    completedChunk = (index + 1).toLong(),
                    progress = (index + 1) * 10,
                )
                repository.complete(claim.lease, JobSignal.SUCCESS)
            }

            val listed = repository.loadTenantJobs(scope.tenantId)
            listed.map { it.jobId } shouldBeEqualTo submitted
            listed.map { it.completedChunk } shouldBeEqualTo listOf(1L, 2L, 3L)
        }
    }
}

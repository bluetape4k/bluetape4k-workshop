package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCanonicalizer
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCommand
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.Reservation
import io.bluetape4k.workshop.operations.jobconsole.idempotency.WaiterRegistration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
/**
 * JobSubmissionIdempotencyMultiInstanceTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class JobSubmissionIdempotencyMultiInstanceTest {

    @Test
/**
 * 이 시나리오는 "two repository instances share the request owner row and waiter cap" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `two repository instances share the request owner row and waiter cap`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val jobRepository = JobRepository(fixture.dataSource)
            val policy = JobSubmissionIdempotencyPolicy(maxWaitersPerKey = 1)
            val first = JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, jobRepository, policy)
            val second = JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, jobRepository, policy)
            val command = command()
            val repositories: List<JobSubmissionIdempotencyRepository> = listOf(first, second)
            val executor = Executors.newFixedThreadPool(repositories.size)
            try {
                val reserveStart = CountDownLatch(1)
                val reserveReady = CountDownLatch(repositories.size)
                val reservations = repositories.map { repository ->
                    executor.submit<Reservation> {
                        reserveReady.countDown()
                        check(reserveStart.await(5, TimeUnit.SECONDS))
                        repository.reserve(command, NOW)
                    }
                }
                check(reserveReady.await(5, TimeUnit.SECONDS))
                reserveStart.countDown()
                val results = reservations.map { it.get(10, TimeUnit.SECONDS) }
                results.count { it is Reservation.Owner } shouldBeEqualTo 1
                results.count { it is Reservation.Wait } shouldBeEqualTo 1
                val owner = results.filterIsInstance<Reservation.Owner>().single()

                val waiterStart = CountDownLatch(1)
                val waiterReady = CountDownLatch(repositories.size)
                val registrations = repositories.map { repository ->
                    executor.submit<WaiterRegistration> {
                        waiterReady.countDown()
                        check(waiterStart.await(5, TimeUnit.SECONDS))
                        repository.registerWaiter(InFlightOwnership(owner.ownership), NOW)
                    }
                }
                check(waiterReady.await(5, TimeUnit.SECONDS))
                waiterStart.countDown()
                val waiterResults = registrations.map { it.get(10, TimeUnit.SECONDS) }
                waiterResults.count { it is WaiterRegistration.Registered } shouldBeEqualTo 1
                waiterResults.count { it == WaiterRegistration.Overflow } shouldBeEqualTo 1
                fixture.count("job_requests") shouldBeEqualTo 1L
                fixture.count("job_request_waiters") shouldBeEqualTo 1L
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

/**
 * command은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun command(): JobSubmissionCommand {
        val scope = DemoCallerScope("tenant-multi", "submitter-a")
        val request = SubmitJobRequest(JobType.REPORT_GENERATION, 3)
        val canonicalizer = JobSubmissionCanonicalizer()
        return JobSubmissionCommand(
            scope = scope,
            keyHash = canonicalizer.keyHash(scope, "multi-key"),
            requestFingerprint = canonicalizer.fingerprint(request),
            request = request,
            policyFingerprint = JobSubmissionIdempotencyPolicy().fingerprint,
        )
    }

    private companion object {
/**
 * NOW은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NOW: Instant = Instant.parse("2026-07-21T00:00:00Z")
    }
}

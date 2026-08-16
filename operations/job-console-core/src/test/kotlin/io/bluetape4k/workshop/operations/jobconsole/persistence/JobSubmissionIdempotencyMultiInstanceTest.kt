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
class JobSubmissionIdempotencyMultiInstanceTest {

    @Test
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
        val NOW: Instant = Instant.parse("2026-07-21T00:00:00Z")
    }
}

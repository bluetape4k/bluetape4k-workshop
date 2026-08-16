package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCanonicalizer
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCommand
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionSnapshotPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PreparedJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PollResult
import io.bluetape4k.workshop.operations.jobconsole.idempotency.Reservation
import io.bluetape4k.workshop.operations.jobconsole.idempotency.WaiterRegistration
import io.bluetape4k.workshop.operations.jobconsole.idempotency.AbandonReason
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

@Tag("integration")
class JobSubmissionIdempotencyRepositoryTest {

    @Test
    fun `reserve classifies owner waiter conflict and terminal replay`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))
            val command = command()

            val owner = repository.reserve(command, NOW)
            check(owner is Reservation.Owner)
            val waiter = repository.reserve(command, NOW.plusMillis(10))
            check(waiter is Reservation.Wait)
            repository.reserve(command.copy(requestFingerprint = "f".repeat(64)), NOW) shouldBeEqualTo Reservation.Conflict

            val prepared = PreparedJobSubmission(
                request = command.request,
                responseBody = "accepted".toByteArray(),
            )
            val snapshot = repository.finalizeOwner(owner.ownership, prepared, NOW.plusSeconds(1))
            snapshot.jobId shouldBeEqualTo owner.ownership.jobId

            val replay = repository.reserve(command, NOW.plusSeconds(2))
            check(replay is Reservation.Replay)
            replay.snapshot.responseBody.decodeToString() shouldBeEqualTo "accepted"
            replay.snapshot.responseHeaders shouldBeEqualTo emptyMap()
            val terminal = repository.poll(command.scope, command.keyHash, owner.ownership.generation, NOW.plusSeconds(2))
            check(terminal is PollResult.Terminal)
        }
    }

    @Test
    fun `unsafe or oversized replay headers fail before job insertion`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))
            val command = command()
            val owner = repository.reserve(command, NOW)
            check(owner is Reservation.Owner)
            val rejectedHeaders = listOf(
                mapOf("x-auth-token" to listOf("secret")),
                mapOf("client-secret" to listOf("secret")),
                mapOf("X-Demo-Id" to listOf("demo")),
                (1..9).associate { "x-safe-$it" to listOf("value") },
            )
            rejectedHeaders.forEach { headers ->
                assertFailsWith<IllegalArgumentException> {
                    repository.finalizeOwner(
                        owner.ownership,
                        PreparedJobSubmission(command.request, responseBody = "accepted".toByteArray(), responseHeaders = headers),
                        NOW,
                    )
                }
            }
            fixture.count("jobs") shouldBeEqualTo 0L
        }
    }

    @Test
    fun `test-only synthetic snapshot policy reaches jdbc finalize and replay`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val policy = JobSubmissionIdempotencyPolicy()
            val repository =
                JdbcJobSubmissionIdempotencyRepository(
                    fixture.dataSource,
                    JobRepository(fixture.dataSource),
                    policy,
                    JobSubmissionSnapshotPolicy.syntheticForTests(policy),
                )
            val command = command(policy)
            val owner = repository.reserve(command, NOW)
            check(owner is Reservation.Owner)

            val snapshot =
                repository.finalizeOwner(
                    owner.ownership,
                    PreparedJobSubmission(
                        request = command.request,
                        responseStatus = 422,
                        responseBody = "synthetic problem".toByteArray(),
                        responseContentType = "application/problem+json",
                    ),
                    NOW,
                )

            snapshot.responseStatus shouldBeEqualTo 422
            check(repository.reserve(command, NOW.plusSeconds(1)) is Reservation.Replay)
        }
    }

    @Test
    fun `waiter registration is bounded and abandoned state is observable`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val policy = JobSubmissionIdempotencyPolicy(maxWaitersPerKey = 1)
            val repository = JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource), policy)
            val command = command()
            val owner = repository.reserve(command, NOW)
            check(owner is Reservation.Owner)
            val ownership = JobSubmissionOwnership(
                scope = command.scope,
                keyHash = command.keyHash,
                generation = owner.ownership.generation,
                jobId = owner.ownership.jobId,
                ownerToken = owner.ownership.ownerToken,
                leaseExpiresAt = owner.ownership.leaseExpiresAt,
            )

            val first = repository.registerWaiter(io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership(ownership), NOW)
            check(first is WaiterRegistration.Registered)
            repository.registerWaiter(io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership(ownership), NOW) shouldBeEqualTo WaiterRegistration.Overflow

            repository.removeWaiter(command.scope, command.keyHash, first.generation, first.waiterToken) shouldBeEqualTo true
            val registrationNow = Instant.now()
            val registrationDeadline = registrationNow.plusSeconds(2)
            val replacement =
                repository.registerWaiter(
                    io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership(ownership),
                    registrationNow,
                    Duration.ofSeconds(2),
                    registrationDeadline,
                )
            check(replacement is WaiterRegistration.Registered)
            fixture.queryLines(
                "SELECT expires_at >= CURRENT_TIMESTAMP FROM job_request_waiters",
            ).single() shouldBeEqualTo "t"
            fixture.queryLines(
                "SELECT expires_at <= CURRENT_TIMESTAMP + INTERVAL '2 seconds' FROM job_request_waiters",
            ).single() shouldBeEqualTo "t"
            repository.abandon(ownership, AbandonReason.PREPARE_FAILED, NOW) shouldBeEqualTo true
            repository.poll(command.scope, command.keyHash, ownership.generation, NOW) shouldBeEqualTo PollResult.Abandoned(ownership.generation)
            fixture.execute("UPDATE job_request_waiters SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")
            fixture.execute("UPDATE job_requests SET abandoned_until = CURRENT_TIMESTAMP - INTERVAL '1 second'")
            val cleanup = repository.cleanupExpired(NOW.plusSeconds(120), batchSize = 100)
            cleanup.waitersDeleted shouldBeEqualTo 1
            cleanup.requestsDeleted shouldBeEqualTo 1
        }
    }

    @Test
    fun `expired owner lease is taken over without changing generation`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val policy =
                JobSubmissionIdempotencyPolicy(
                    ownerLease = Duration.ofMillis(100),
                    prepareDeadline = Duration.ofMillis(10),
                )
            val repository = JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource), policy)
            val command = command(policy)
            val owner = repository.reserve(command, NOW)
            check(owner is Reservation.Owner)
            fixture.execute("UPDATE job_requests SET owner_lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")

            val recovered = repository.reserve(command, NOW.plusSeconds(1))
            check(recovered is Reservation.Owner)
            recovered.ownership.generation shouldBeEqualTo owner.ownership.generation
            check(recovered.ownership.ownerToken != owner.ownership.ownerToken)
            check(recovered.ownership.jobId != owner.ownership.jobId)
        }
    }

    @Test
    fun `expired owner cannot finalize after lease boundary`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))
            val command = command()
            val owner = repository.reserve(command, NOW)
            check(owner is Reservation.Owner)
            fixture.execute("UPDATE job_requests SET owner_lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")

            val failure = assertFailsWith<JobRepositoryException> {
                repository.finalizeOwner(
                    owner.ownership,
                    PreparedJobSubmission(command.request, responseBody = "late".toByteArray()),
                    NOW.plusSeconds(1),
                )
            }
            failure.code shouldBeEqualTo JobProblemCode.LEASE_LOST
            fixture.count("jobs") shouldBeEqualTo 0L
        }
    }

    @Test
    fun `legacy terminal row lazily snapshots and retention creates a new generation`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val scope = DemoCallerScope("tenant-legacy", "submitter-a")
            val request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)
            val legacy = JobRepository(fixture.dataSource).submit(scope, "legacy-key", request, Instant.now())
            val command =
                JobSubmissionCommand(
                    scope = scope,
                    keyHash = sha256("${scope.tenantId}:${scope.submitterHash}:legacy-key"),
                    requestFingerprint = sha256("${request.jobType.wireValue}:${request.workUnits}:${request.failureMode.wireValue}"),
                    request = request,
                    policyFingerprint = JobSubmissionIdempotencyPolicy().fingerprint,
                )
            val repository = JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))

            val replay = repository.reserve(command, NOW)
            check(replay is Reservation.Replay)
            replay.snapshot.jobId shouldBeEqualTo legacy.jobId
            replay.snapshot.responseStatus shouldBeEqualTo 202
            replay.snapshot.responseBody.decodeToString().contains(legacy.jobId.toString()) shouldBeEqualTo true
            fixture.countWhere("job_requests", "response_status = 202") shouldBeEqualTo 1L

            fixture.execute("UPDATE job_requests SET retained_until = CURRENT_TIMESTAMP")
            val newCommand = command.copy(requestFingerprint = "f".repeat(64))
            val recovered = repository.reserve(newCommand, NOW.plusSeconds(1))
            check(recovered is Reservation.Owner)
            recovered.ownership.generation shouldBeEqualTo 2L
            check(recovered.ownership.jobId != legacy.jobId)
            check(repository.reserve(newCommand, NOW.plusSeconds(1)) is Reservation.Wait)
        }
    }

    @Test
    fun `stale owner cannot finalize a request after abandonment`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))
            val command = command()
            val owner = repository.reserve(command, NOW)
            check(owner is Reservation.Owner)
            repository.abandon(owner.ownership, AbandonReason.OWNER_DISCONNECTED, NOW)

            val failure = assertFailsWith<JobRepositoryException> {
                repository.finalizeOwner(
                    owner.ownership,
                    PreparedJobSubmission(command.request, responseBody = "late".toByteArray()),
                    NOW.plusSeconds(1),
                )
            }
            failure.code shouldBeEqualTo JobProblemCode.LEASE_LOST
            fixture.count("jobs") shouldBeEqualTo 0L
        }
    }

    private fun command(policy: JobSubmissionIdempotencyPolicy = JobSubmissionIdempotencyPolicy()): JobSubmissionCommand {
        val scope = DemoCallerScope("tenant-a", "submitter-a")
        val request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)
        val canonicalizer = JobSubmissionCanonicalizer()
        return JobSubmissionCommand(
            scope = scope,
            keyHash = canonicalizer.keyHash(scope, "request-key"),
            requestFingerprint = canonicalizer.fingerprint(request),
            request = request,
            policyFingerprint = policy.fingerprint,
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)).toHexString()

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-21T00:00:00Z")
    }
}

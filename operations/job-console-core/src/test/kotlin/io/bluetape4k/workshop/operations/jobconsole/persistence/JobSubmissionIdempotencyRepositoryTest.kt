package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.idempotency.AbandonReason
import io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCanonicalizer
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCommand
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionSnapshotPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PollResult
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PreparedJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.Reservation
import io.bluetape4k.workshop.operations.jobconsole.idempotency.WaiterRegistration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

@Tag("integration")
class JobSubmissionIdempotencyRepositoryTest {

    private companion object: KLogging() {
        val NOW: Instant = Instant.parse("2026-07-21T00:00:00Z")
    }

    @Test
    fun `reserve classifies owner waiter conflict and terminal replay`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository =
                JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))
            val command = command()

            val owner = repository.reserve(command, NOW)
            owner.shouldBeInstanceOf<Reservation.Owner>()
            log.debug { "Reserving owner: $owner" }

            val waiter = repository.reserve(command, NOW.plusMillis(10))
            log.debug { "Reserving waiter: $waiter" }
            waiter.shouldBeInstanceOf<Reservation.Wait>()

            repository.reserve(
                command.copy(requestFingerprint = "f".repeat(64)),
                NOW
            ) shouldBeEqualTo Reservation.Conflict

            val prepared = PreparedJobSubmission(
                request = command.request,
                responseBody = "accepted".toUtf8Bytes(),
            )
            val snapshot = repository.finalizeOwner(owner.ownership, prepared, NOW + 1.seconds())
            snapshot.jobId shouldBeEqualTo owner.ownership.jobId

            val replay = repository.reserve(command, NOW + 2.seconds())
            log.debug { "replay=$replay" }
            replay.shouldBeInstanceOf<Reservation.Replay>()
            replay.snapshot.responseBody.decodeToString() shouldBeEqualTo "accepted"
            replay.snapshot.responseHeaders shouldBeEqualTo emptyMap()

            val terminal =
                repository.poll(command.scope, command.keyHash, owner.ownership.generation, NOW + 2.seconds())
            log.debug { "terminal=$terminal" }
            terminal.shouldBeInstanceOf<PollResult.Terminal>()
        }
    }

    @Test
    fun `unsafe or oversized replay headers fail before job insertion`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository =
                JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))
            val command = command()
            val owner = repository.reserve(command, NOW)
            owner.shouldBeInstanceOf<Reservation.Owner>()

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
                        PreparedJobSubmission(
                            command.request,
                            responseBody = "accepted".toUtf8Bytes(),
                            responseHeaders = headers
                        ),
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
            owner.shouldBeInstanceOf<Reservation.Owner>()

            val snapshot =
                repository.finalizeOwner(
                    owner.ownership,
                    PreparedJobSubmission(
                        request = command.request,
                        responseStatus = 422,
                        responseBody = "synthetic problem".toUtf8Bytes(),
                        responseContentType = "application/problem+json",
                    ),
                    NOW,
                )

            snapshot.responseStatus shouldBeEqualTo 422
            repository.reserve(command, NOW + 1.seconds()).shouldBeInstanceOf<Reservation.Replay>()
        }
    }

    @Test
    fun `waiter registration is bounded and abandoned state is observable`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val policy = JobSubmissionIdempotencyPolicy(maxWaitersPerKey = 1)
            val repository =
                JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource), policy)
            val command = command()
            val owner = repository.reserve(command, NOW)
            owner.shouldBeInstanceOf<Reservation.Owner>()

            val ownership = JobSubmissionOwnership(
                scope = command.scope,
                keyHash = command.keyHash,
                generation = owner.ownership.generation,
                jobId = owner.ownership.jobId,
                ownerToken = owner.ownership.ownerToken,
                leaseExpiresAt = owner.ownership.leaseExpiresAt,
            )

            val first = repository.registerWaiter(
                InFlightOwnership(ownership), NOW
            )
            first.shouldBeInstanceOf<WaiterRegistration.Registered>()
            repository.registerWaiter(InFlightOwnership(ownership), NOW) shouldBeEqualTo WaiterRegistration.Overflow

            repository.removeWaiter(
                command.scope,
                command.keyHash,
                first.generation,
                first.waiterToken
            ).shouldBeTrue()
            val registrationNow = Instant.now()
            val registrationDeadline = registrationNow + 2.seconds()
            val replacement =
                repository.registerWaiter(
                    InFlightOwnership(ownership),
                    registrationNow,
                    Duration.ofSeconds(2),
                    registrationDeadline,
                )
            replacement.shouldBeInstanceOf<WaiterRegistration.Registered>()

            fixture.queryLines(
                "SELECT expires_at >= CURRENT_TIMESTAMP FROM job_request_waiters",
            ).single() shouldBeEqualTo "t"

            fixture.queryLines(
                "SELECT expires_at <= CURRENT_TIMESTAMP + INTERVAL '2 seconds' FROM job_request_waiters",
            ).single() shouldBeEqualTo "t"

            repository.abandon(ownership, AbandonReason.PREPARE_FAILED, NOW).shouldBeTrue()

            repository.poll(
                command.scope,
                command.keyHash,
                ownership.generation,
                NOW
            ) shouldBeEqualTo PollResult.Abandoned(ownership.generation)

            fixture.execute("UPDATE job_request_waiters SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")
            fixture.execute("UPDATE job_requests SET abandoned_until = CURRENT_TIMESTAMP - INTERVAL '1 second'")

            val cleanup = repository.cleanupExpired(NOW.plusSeconds(120), batchSize = 100)
            log.debug { "cleanup=$cleanup" }
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
                    ownerLease = 100.millis(),
                    prepareDeadline = 10.millis(),
                )
            val repository =
                JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource), policy)
            val command = command(policy)
            val owner = repository.reserve(command, NOW)
            log.debug { "owner=$owner" }
            owner.shouldBeInstanceOf<Reservation.Owner>()

            fixture.execute("UPDATE job_requests SET owner_lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")

            val recovered = repository.reserve(command, NOW + 1.seconds())
            log.debug { "recovered=$recovered" }
            recovered.shouldBeInstanceOf<Reservation.Owner>()
            recovered.ownership.generation shouldBeEqualTo owner.ownership.generation

            recovered.ownership.ownerToken shouldNotBeEqualTo owner.ownership.ownerToken
            recovered.ownership.jobId shouldNotBeEqualTo owner.ownership.jobId
        }
    }

    @Test
    fun `expired owner cannot finalize after lease boundary`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository =
                JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))
            val command = command()
            val owner = repository.reserve(command, NOW)
            log.debug { "owner=$owner" }
            owner.shouldBeInstanceOf<Reservation.Owner>()

            fixture.execute("UPDATE job_requests SET owner_lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")

            val failure = assertFailsWith<JobRepositoryException> {
                repository.finalizeOwner(
                    owner.ownership,
                    PreparedJobSubmission(command.request, responseBody = "late".toUtf8Bytes()),
                    NOW + 1.seconds(),
                )
            }
            log.debug { "failure=$failure" }
            failure.code shouldBeEqualTo JobProblemCode.LEASE_LOST
            fixture.count("jobs") shouldBeEqualTo 0L
        }
    }

    @Test
    fun `cleanup deletes expired abandoned requests in bounded batches`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository =
                JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))

            fixture.execute(
                """
                INSERT INTO job_requests(
                    tenant_id, submitter_hash, key_hash, request_fingerprint, job_id,
                    state, generation, abandoned_until, updated_at
                )
                SELECT 'tenant-cleanup-batch', lpad(to_hex(item), 64, '0'), lpad(to_hex(item + 1000), 64, '0'),
                       repeat('f', 64), md5(format('cleanup-%s', item))::uuid,
                       'ABANDONED', 1, CURRENT_TIMESTAMP - interval '1 second', CURRENT_TIMESTAMP
                FROM generate_series(1, 105) item
                """.trimIndent(),
            )

            val first = repository.cleanupExpired(NOW, batchSize = 100)
            log.debug { "first=$first" }
            first.waitersDeleted shouldBeEqualTo 0
            first.requestsDeleted shouldBeEqualTo 100
            fixture.countWhere("job_requests", "tenant_id = 'tenant-cleanup-batch'") shouldBeEqualTo 5L

            val second = repository.cleanupExpired(NOW, batchSize = 100)
            log.debug { "second=$second" }
            second.waitersDeleted shouldBeEqualTo 0
            second.requestsDeleted shouldBeEqualTo 5
            fixture.countWhere("job_requests", "tenant_id = 'tenant-cleanup-batch'") shouldBeEqualTo 0L
        }
    }

    @Test
    fun `legacy terminal row lazily snapshots and retention creates a new generation`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val scope = DemoCallerScope("tenant-legacy", "submitter-a")
            val request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)
            val legacy = JobRepository(fixture.dataSource).submit(scope, "legacy-key", request, Instant.now())

            val command = JobSubmissionCommand(
                scope = scope,
                keyHash = sha256("${scope.tenantId}:${scope.submitterHash}:legacy-key"),
                requestFingerprint = sha256("${request.jobType.wireValue}:${request.workUnits}:${request.failureMode.wireValue}"),
                request = request,
                policyFingerprint = JobSubmissionIdempotencyPolicy().fingerprint,
            )
            val repository =
                JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))

            val replay = repository.reserve(command, NOW)
            log.debug { "replay=$replay" }
            replay.shouldBeInstanceOf<Reservation.Replay>()
            replay.snapshot.jobId shouldBeEqualTo legacy.jobId
            replay.snapshot.responseStatus shouldBeEqualTo 202
            replay.snapshot.responseBody.decodeToString().contains(legacy.jobId.toString()).shouldBeTrue()

            fixture.countWhere("job_requests", "response_status = 202") shouldBeEqualTo 1L

            fixture.execute("UPDATE job_requests SET retained_until = CURRENT_TIMESTAMP")
            val newCommand = command.copy(requestFingerprint = "f".repeat(64))
            val recovered = repository.reserve(newCommand, NOW + 1.seconds())
            log.debug { "recovered=$recovered" }
            recovered.shouldBeInstanceOf<Reservation.Owner>()
            recovered.ownership.generation shouldBeEqualTo 2L

            recovered.ownership.jobId shouldNotBeEqualTo legacy.jobId
            repository.reserve(newCommand, NOW + 1.seconds()).shouldBeInstanceOf<Reservation.Wait>()
        }
    }

    @Test
    fun `stale owner cannot finalize a request after abandonment`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository =
                JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource))
            val command = command()
            val owner = repository.reserve(command, NOW)
            log.debug { "owner = $owner" }
            owner.shouldBeInstanceOf<Reservation.Owner>()

            repository.abandon(owner.ownership, AbandonReason.OWNER_DISCONNECTED, NOW)

            val failure = assertFailsWith<JobRepositoryException> {
                repository.finalizeOwner(
                    owner.ownership,
                    PreparedJobSubmission(command.request, responseBody = "late".toUtf8Bytes()),
                    NOW + 1.seconds(),
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
        MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()).toHexString()

}

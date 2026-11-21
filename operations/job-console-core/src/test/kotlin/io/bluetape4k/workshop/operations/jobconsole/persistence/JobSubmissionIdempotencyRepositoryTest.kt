package io.bluetape4k.workshop.operations.jobconsole.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** PostgreSQL request row의 owner/waiter/replay, lease takeover, retention, clock 권위를 검증한다. */
/** PostgreSQL request row의 owner/waiter/replay, lease takeover, retention, clock 권위를 검증한다. */
@Tag("integration")
class JobSubmissionIdempotencyRepositoryTest {

    private companion object: KLogging() {
/**
 * NOW은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NOW: Instant = Instant.parse("2026-07-21T00:00:00Z")
    }

    @Test
/**
 * 이 시나리오는 "reserve classifies owner waiter conflict and terminal replay" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "unsafe or oversized replay headers fail before job insertion" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "test-only synthetic snapshot policy reaches jdbc finalize and replay" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "waiter registration is bounded and abandoned state is observable" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "expired owner lease is taken over without changing generation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "expired owner cannot finalize after lease boundary" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "owner lease and abandonment use database clock rather than caller clock" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `owner lease and abandonment use database clock rather than caller clock`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val policy = JobSubmissionIdempotencyPolicy(ownerLease = 30.seconds())
            val repository =
                JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource), policy)
            val command = command(policy)

            // 호출자 clock이 DB보다 크게 과거/미래여도 lease 경계는 DB clock에 고정되어야 합니다.
            val owner = repository.reserve(command, Instant.parse("1900-01-01T00:00:00Z"))
            owner.shouldBeInstanceOf<Reservation.Owner>()
            fixture.queryLines(
                """
                SELECT created_at >= CURRENT_TIMESTAMP - INTERVAL '5 seconds'
                   AND owner_lease_expires_at >= CURRENT_TIMESTAMP + INTERVAL '20 seconds'
                FROM job_requests
                """.trimIndent(),
            ).single() shouldBeEqualTo "t"

            repository.abandon(owner.ownership, AbandonReason.PREPARE_FAILED, Instant.parse("2099-01-01T00:00:00Z"))
                .shouldBeTrue()
            fixture.queryLines(
                """
                SELECT updated_at >= CURRENT_TIMESTAMP - INTERVAL '5 seconds'
                   AND abandoned_until BETWEEN CURRENT_TIMESTAMP + INTERVAL '50 seconds'
                                             AND CURRENT_TIMESTAMP + INTERVAL '70 seconds'
                FROM job_requests
                """.trimIndent(),
            ).single() shouldBeEqualTo "t"
        }
    }

    @Test
/**
 * 이 시나리오는 "lease clock is sampled after lock wait even when another request exists" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `lease clock is sampled after lock wait even when another request exists`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val policy =
                JobSubmissionIdempotencyPolicy(
                    ownerLease = Duration.ofSeconds(1),
                    prepareDeadline = Duration.ofMillis(500),
                    statementTimeout = Duration.ofSeconds(2),
                    connectionAcquireTimeout = Duration.ofSeconds(1),
                )
            val repository =
                JdbcJobSubmissionIdempotencyRepository(fixture.dataSource, JobRepository(fixture.dataSource), policy)
            val command = command(policy)
            val owner = repository.reserve(command, NOW)
            owner.shouldBeInstanceOf<Reservation.Owner>()

            // 다른 request row가 존재해도 DB clock 조회가 단일 row를 가정하지 않아야 합니다.
            repository.reserve(command.copy(keyHash = "b".repeat(64)), NOW)
                .shouldBeInstanceOf<Reservation.Owner>()

            fixture.dataSource.connection.use { blocker ->
                blocker.autoCommit = false
                blocker.prepareStatement(
                    """
                    SELECT key_hash
                    FROM job_requests
                    WHERE tenant_id = ? AND key_hash = ?
                    FOR UPDATE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, command.scope.tenantId)
                    statement.setString(2, command.keyHash)
                    statement.executeQuery().use { result -> result.next().shouldBeTrue() }
                }

                val started = CountDownLatch(1)
                Executors.newSingleThreadExecutor().use { executor ->
                    val waiter = executor.submit<Reservation> {
                        started.countDown()
                        repository.reserve(command, NOW)
                    }
                    started.await(1, TimeUnit.SECONDS).shouldBeTrue()
                    // owner lease보다 길게 row lock을 유지하여, lock 해제 직후 fresh DB clock이면 takeover가 됩니다.
                    Thread.sleep(1_200)
                    blocker.commit()

                    val recovered = waiter.get(5, TimeUnit.SECONDS)
                    recovered.shouldBeInstanceOf<Reservation.Owner>()
                    recovered.ownership.ownerToken shouldNotBeEqualTo owner.ownership.ownerToken
                }
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "waiter reserve with a single connection pool reuses the committed connection" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `waiter reserve with a single connection pool reuses the committed connection`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = fixture.dataSource.jdbcUrl
                    username = fixture.dataSource.username
                    password = fixture.dataSource.password
                    schema = fixture.dataSource.schema
                    maximumPoolSize = 1
                    connectionTimeout = 2_000
                    poolName = "job-console-single-connection"
                },
            ).use { singleConnectionDataSource ->
                val repository =
                    JdbcJobSubmissionIdempotencyRepository(
                        singleConnectionDataSource,
                        JobRepository(singleConnectionDataSource),
                    )
                val command = command()
                repository.reserve(command, NOW).shouldBeInstanceOf<Reservation.Owner>()

                // afterCommit이 두 번째 connection을 요구하면 이 호출은 pool timeout으로 실패합니다.
                repository.reserve(command, NOW.plusMillis(10)).shouldBeInstanceOf<Reservation.Wait>()
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "cleanup deletes expired abandoned requests in bounded batches" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
            fixture.countRequestsForTenant("tenant-cleanup-batch") shouldBeEqualTo 5L

            val second = repository.cleanupExpired(NOW, batchSize = 100)
            log.debug { "second=$second" }
            second.waitersDeleted shouldBeEqualTo 0
            second.requestsDeleted shouldBeEqualTo 5
            fixture.countRequestsForTenant("tenant-cleanup-batch") shouldBeEqualTo 0L
        }
    }

    @Test
/**
 * 이 시나리오는 "legacy terminal row lazily snapshots and retention creates a new generation" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

            fixture.countRequestsByResponseStatus(202) shouldBeEqualTo 1L

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
/**
 * 이 시나리오는 "stale owner cannot finalize a request after abandonment" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * command은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun command(policy: JobSubmissionIdempotencyPolicy = JobSubmissionIdempotencyPolicy()): JobSubmissionCommand {
/**
 * scope은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val scope = DemoCallerScope("tenant-a", "submitter-a")
/**
 * request은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)
/**
 * canonicalizer은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val canonicalizer = JobSubmissionCanonicalizer()
        return JobSubmissionCommand(
            scope = scope,
            keyHash = canonicalizer.keyHash(scope, "request-key"),
            requestFingerprint = canonicalizer.fingerprint(request),
            request = request,
            policyFingerprint = policy.fingerprint,
        )
    }

/**
 * sha256은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()).toHexString()

}

package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobSubmissionIdempotencyRepository
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class JobSubmissionIdempotencyCoordinatorTest {

    @Test
    fun `owner prepares without repository connection and finalizes exactly once`() {
        val repository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val clock = VirtualClock()
        val action = RecordingAction(
            prepare = { repository.transactionActive shouldBeEqualTo false },
            commitCheck = { repository.transactionActive shouldBeEqualTo true },
        )
        val coordinator = coordinator(repository, clock)

        val outcome = coordinator.execute(command(), action)

        outcome shouldBeEqualTo JobSubmissionOutcome.OwnerCompleted(snapshot)
        action.prepareCalls shouldBeEqualTo 1
        action.commitCalls shouldBeEqualTo 1
        repository.transactionCalls shouldBeEqualTo 1
        repository.finalizeCalls shouldBeEqualTo 0
        repository.abandonCalls shouldBeEqualTo 0
    }

    @Test
    fun `replay conflict overflow and abandoned reservations do not prepare`() {
        val command = command()
        val action = RecordingAction()

        val replayRepository = FakeRepository().apply { reservation = Reservation.Replay(snapshot) }
        coordinator(replayRepository, VirtualClock()).execute(command, action) shouldBeEqualTo JobSubmissionOutcome.Replayed(snapshot)
        replayRepository.finalizeCalls shouldBeEqualTo 0

        val conflictRepository = FakeRepository().apply { reservation = Reservation.Conflict }
        coordinator(conflictRepository, VirtualClock()).execute(command, action) shouldBeEqualTo JobSubmissionOutcome.Conflict

        val overflowRepository = FakeRepository().apply { reservation = Reservation.Overflow }
        coordinator(overflowRepository, VirtualClock()).execute(command, action) shouldBeEqualTo JobSubmissionOutcome.WaiterOverflow

        val abandonedRepository = FakeRepository().apply { reservation = Reservation.Abandoned }
        coordinator(abandonedRepository, VirtualClock()).execute(command, action) shouldBeEqualTo JobSubmissionOutcome.Abandoned

        action.prepareCalls shouldBeEqualTo 0
    }

    @Test
    fun `registration deadline exceeded becomes an in-flight timeout without polling`() {
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.DeadlineExceeded
        }

        coordinator(repository, VirtualClock()).execute(command(), RecordingAction()) shouldBeEqualTo
            JobSubmissionOutcome.InFlightTimeout
        repository.registerCalls shouldBeEqualTo 1
        repository.pollCalls shouldBeEqualTo 0
    }

    @Test
    fun `waiter polls without holding a connection and backs off to the configured maximum`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            pollResults.add(PollResult.StillInFlight)
            pollResults.add(PollResult.StillInFlight)
            pollResults.add(PollResult.Terminal(snapshot))
        }
        val outcome = coordinator(repository, clock, waiter).execute(command(), RecordingAction())

        outcome shouldBeEqualTo JobSubmissionOutcome.Replayed(snapshot)
        waiter.intervals shouldBeEqualTo listOf(Duration.ofMillis(25), Duration.ofMillis(50))
        repository.registerCalls shouldBeEqualTo 1
        repository.pollCalls shouldBeEqualTo 3
        repository.removeCalls shouldBeEqualTo 1
    }

    @Test
    fun `waiter timeout at the exact deadline removes its row`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            repeat(8) { pollResults.add(PollResult.StillInFlight) }
        }

        val outcome = coordinator(repository, clock, waiter).execute(command(), RecordingAction())

        outcome shouldBeEqualTo JobSubmissionOutcome.InFlightTimeout
        clock.elapsed shouldBeEqualTo Duration.ofSeconds(2)
        repository.removeCalls shouldBeEqualTo 1
    }

    @Test
    fun `waiter can register during the final statement window and replay terminal state`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val databasePermits = ScriptedDatabaseSemaphore().apply {
            beforeRegistrationAcquire = { clock.advance(Duration.ofMillis(1_600)) }
            failPollPermit = false
        }
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            pollResults.add(PollResult.Terminal(snapshot))
        }

        coordinator(repository, clock, waiter, databasePermits = databasePermits).execute(command(), RecordingAction()) shouldBeEqualTo
            JobSubmissionOutcome.Replayed(snapshot)
        repository.registerTtls.single() shouldBeEqualTo Duration.ofMillis(400)
        repository.registerDeadlines.single() shouldBeEqualTo NOW.plusSeconds(2)
        repository.pollCalls shouldBeEqualTo 1
        clock.elapsed shouldBeEqualTo Duration.ofMillis(1_600)
    }

    @Test
    fun `waiter deadline follows the reservation database clock rather than the application wall clock`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val databaseNow = NOW.plusSeconds(5)
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, databaseNow)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            pollResults.add(PollResult.Terminal(snapshot))
        }

        coordinator(repository, clock, waiter).execute(command(), RecordingAction()) shouldBeEqualTo
            JobSubmissionOutcome.Replayed(snapshot)
        repository.registerDeadlines.single() shouldBeEqualTo databaseNow.plusSeconds(2)
    }

    @Test
    fun `waiter deadline includes time spent in the reserve transaction`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val databaseNow = NOW.plusMillis(400)
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, databaseNow)
            onReserve = { clock.advanceMonotonicOnly(Duration.ofMillis(400)) }
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            pollResults.add(PollResult.Terminal(snapshot))
        }

        coordinator(repository, clock, waiter).execute(command(), RecordingAction()) shouldBeEqualTo
            JobSubmissionOutcome.Replayed(snapshot)
        repository.registerDeadlines.single() shouldBeEqualTo databaseNow.plusSeconds(2)
    }

    @Test
    fun `waiter retries after a database permit miss and passes remaining statement timeout`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val databasePermits = ScriptedDatabaseSemaphore().apply {
            beforeSuccessfulPollAcquire = { clock.advance(Duration.ofMillis(1_700)) }
        }
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            pollResults.add(PollResult.Terminal(snapshot))
        }

        coordinator(repository, clock, waiter, databasePermits = databasePermits).execute(command(), RecordingAction()) shouldBeEqualTo
            JobSubmissionOutcome.Replayed(snapshot)
        databasePermits.attempts shouldBeEqualTo 5
        waiter.intervals shouldBeEqualTo listOf(Duration.ofMillis(25))
        repository.pollCalls shouldBeEqualTo 1
        repository.pollStatementTimeouts.single() shouldBeEqualTo Duration.ofMillis(275)
    }

    @Test
    fun `prepare deadline abandons once and does not finalize`() {
        val clock = VirtualClock()
        val repository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val action = RecordingAction(prepare = { clock.advance(Duration.ofSeconds(10)) })

        val outcome = coordinator(repository, clock).execute(command(), action)

        outcome shouldBeEqualTo JobSubmissionOutcome.Abandoned
        repository.abandonCalls shouldBeEqualTo 1
        repository.abandonReasons shouldBeEqualTo listOf(AbandonReason.PREPARE_DEADLINE)
        repository.finalizeCalls shouldBeEqualTo 0
    }

    @Test
    fun `prepare hard deadline abandons a blocked action`() {
        val clock = VirtualClock()
        val repository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val prepareExecutor = Executors.newSingleThreadExecutor()
        val policy = JobSubmissionIdempotencyPolicy(prepareDeadline = Duration.ofMillis(50))
        val action = RecordingAction(
            prepare = {
                started.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                } catch (error: InterruptedException) {
                    interrupted.countDown()
                    throw error
                }
            },
        )

        try {
            val outcome = coordinator(repository, clock, policy = policy, prepareExecutor = prepareExecutor).execute(command(policy), action)

            check(started.await(1, TimeUnit.SECONDS))
            outcome shouldBeEqualTo JobSubmissionOutcome.Abandoned
            repository.abandonReasons shouldBeEqualTo listOf(AbandonReason.PREPARE_DEADLINE)
            check(interrupted.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            prepareExecutor.shutdownNow()
            prepareExecutor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `prepare failure abandons while cancellation is rethrown`() {
        val failedClock = VirtualClock()
        val failedRepository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val failedAction = RecordingAction(prepare = { error("dependency failed") })
        coordinator(failedRepository, failedClock).execute(command(), failedAction) shouldBeEqualTo JobSubmissionOutcome.Abandoned
        failedRepository.abandonCalls shouldBeEqualTo 1

        val cancelledClock = VirtualClock()
        val cancelledRepository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val cancelledAction = RecordingAction(prepare = { throw CancellationException("client cancelled") })
        assertFailsWith<CancellationException> {
            coordinator(cancelledRepository, cancelledClock).execute(command(), cancelledAction)
        }
        cancelledRepository.abandonCalls shouldBeEqualTo 1
        cancelledRepository.abandonReasons shouldBeEqualTo listOf(AbandonReason.CANCELLED)
    }

    @Test
    fun `owner prepare permit is acquired before reservation and rejects without a row`() {
        val clock = VirtualClock()
        val repository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val policy = JobSubmissionIdempotencyPolicy(ownerPrepareConcurrency = 1, connectionAcquireTimeout = Duration.ofMillis(25))
        val coordinator = coordinator(repository, clock, policy = policy)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<JobSubmissionOutcome> {
                coordinator.execute(
                    command(policy),
                    RecordingAction {
                        entered.countDown()
                        check(release.await(2, TimeUnit.SECONDS))
                    },
                )
            }
            check(entered.await(2, TimeUnit.SECONDS))

            coordinator.execute(command(policy), RecordingAction()) shouldBeEqualTo JobSubmissionOutcome.WaiterOverflow
            repository.reserveCalls shouldBeEqualTo 1

            release.countDown()
            first.get(2, TimeUnit.SECONDS) shouldBeEqualTo JobSubmissionOutcome.OwnerCompleted(snapshot)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `waiter cancellation removes its database row before rethrowing`() {
        val clock = VirtualClock()
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
        }
        val waiter = object : InterruptibleWaitStrategy {
            override fun await(interval: Duration): Unit = throw CancellationException("waiter cancelled")
        }

        assertFailsWith<CancellationException> {
            coordinator(repository, clock, waiter).execute(command(), RecordingAction())
        }
        repository.removeCalls shouldBeEqualTo 1
    }

    @Test
    fun `finalize cancellation is rethrown after one abandon attempt`() {
        val clock = VirtualClock()
        val repository = FakeRepository().apply {
            reservation = Reservation.Owner(ownership)
            transactionFailureAfterCallback = CancellationException("commit cancelled")
        }

        assertFailsWith<CancellationException> {
            coordinator(repository, clock).execute(command(), RecordingAction())
        }
        repository.abandonCalls shouldBeEqualTo 1
        repository.abandonReasons shouldBeEqualTo listOf(AbandonReason.CANCELLED)
        repository.transactionCommitted shouldBeEqualTo false
    }

    @Test
    fun `wait jitter is deterministic and stays within the twenty percent envelope`() {
        val token = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")
        val interval = Duration.ofMillis(100)
        val jittered = deterministicWaitJitter(token, interval)
        val envelope = (0L until 100L).map { value ->
            deterministicWaitJitter(UUID(0L, value), interval).toMillis()
        }

        jittered shouldBeEqualTo deterministicWaitJitter(token, interval)
        check(envelope.min() == 80L)
        check(envelope.max() == 120L)
    }

    private fun coordinator(
        repository: FakeRepository,
        clock: VirtualClock,
        waiter: InterruptibleWaitStrategy = RecordingWaiter(clock),
        policy: JobSubmissionIdempotencyPolicy = JobSubmissionIdempotencyPolicy(),
        prepareExecutor: ExecutorService = ForkJoinPool.commonPool(),
        databasePermits: Semaphore = Semaphore(policy.idempotencyDbConcurrency, true),
    ): JobSubmissionIdempotencyCoordinator =
        JobSubmissionIdempotencyCoordinator(
            repository = repository,
            policy = policy,
            clock = clock,
            waiter = waiter,
            waitJitter = { _, interval -> interval },
            prepareExecutor = prepareExecutor,
            databasePermits = databasePermits,
        )

    private class RecordingAction(
        private val prepare: () -> Unit = {},
        private val commitCheck: () -> Unit = {},
    ) : JobSubmissionOwnerAction {
        var prepareCalls = 0
        var commitCalls = 0

        override fun prepare(ownership: JobSubmissionOwnership): PreparedJobSubmission {
            prepareCalls += 1
            prepare()
            return PreparedJobSubmission(command().request, responseBody = "accepted".toByteArray())
        }

        override fun commit(
            connection: Connection,
            ownership: JobSubmissionOwnership,
            prepared: PreparedJobSubmission,
        ): ReplayableJobSubmission {
            commitCalls += 1
            commitCheck()
            return snapshot
        }
    }

    private class RecordingWaiter(
        private val clock: VirtualClock,
    ) : InterruptibleWaitStrategy {
        val intervals = mutableListOf<Duration>()

        override fun await(interval: Duration) {
            intervals += interval
            clock.advance(interval)
        }
    }

    private class VirtualClock(
        private var instant: Instant = NOW,
    ) : JobSubmissionClock {
        var elapsed: Duration = Duration.ZERO
            private set

        override fun databaseNow(): Instant = instant

        override fun monotonicNanos(): Long = elapsed.toNanos()

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
            elapsed = elapsed.plus(duration)
        }

        fun advanceMonotonicOnly(duration: Duration) {
            elapsed = elapsed.plus(duration)
        }
    }

    private class FakeRepository : JobSubmissionIdempotencyRepository {
        var reservation: Reservation = Reservation.Owner(ownership)
        var registration: WaiterRegistration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
        val pollResults = ArrayDeque<PollResult>()
        var registerCalls = 0
        val registerTtls = mutableListOf<Duration>()
        val registerDeadlines = mutableListOf<Instant?>()
        var onReserve: (() -> Unit)? = null
        var reserveCalls = 0
        var pollCalls = 0
        val pollStatementTimeouts = mutableListOf<Duration>()
        var removeCalls = 0
        var finalizeCalls = 0
        var transactionCalls = 0
        var transactionFailure: Throwable? = null
        var transactionFailureAfterCallback: Throwable? = null
        var transactionActive = false
        var transactionCommitted = false
        var abandonCalls = 0
        val abandonReasons = mutableListOf<AbandonReason>()

        override fun reserve(command: JobSubmissionCommand, now: Instant): Reservation {
            reserveCalls += 1
            onReserve?.invoke()
            return reservation
        }

        override fun registerWaiter(ownership: InFlightOwnership, now: Instant): WaiterRegistration {
            return registerWaiter(ownership, now, Duration.ofSeconds(2))
        }

        override fun registerWaiter(
            ownership: InFlightOwnership,
            now: Instant,
            waiterTtl: Duration,
        ): WaiterRegistration {
            registerCalls += 1
            registerTtls += waiterTtl
            registerDeadlines += null
            return registration
        }

        override fun registerWaiter(
            ownership: InFlightOwnership,
            now: Instant,
            waiterTtl: Duration,
            deadlineAt: Instant,
        ): WaiterRegistration {
            registerCalls += 1
            registerTtls += waiterTtl
            registerDeadlines += deadlineAt
            return registration
        }

        override fun removeWaiter(scope: DemoCallerScope, keyHash: String, generation: Long, waiterToken: UUID): Boolean {
            removeCalls += 1
            return true
        }

        override fun poll(scope: DemoCallerScope, keyHash: String, generation: Long, now: Instant): PollResult {
            pollCalls += 1
            return if (pollResults.isEmpty()) PollResult.StillInFlight else pollResults.removeFirst()
        }

        override fun poll(
            scope: DemoCallerScope,
            keyHash: String,
            generation: Long,
            now: Instant,
            statementTimeout: Duration,
        ): PollResult {
            pollStatementTimeouts += statementTimeout
            return poll(scope, keyHash, generation, now)
        }

        override fun <T> withTransaction(block: (Connection) -> T): T {
            transactionCalls += 1
            transactionActive = true
            return try {
                transactionFailure?.let { throw it }
                val result = block(FAKE_CONNECTION)
                transactionFailureAfterCallback?.let { throw it }
                transactionCommitted = true
                result
            } finally {
                transactionActive = false
            }
        }

        override fun finalizeOwner(
            ownership: JobSubmissionOwnership,
            prepared: PreparedJobSubmission,
            now: Instant,
        ): ReplayableJobSubmission {
            finalizeCalls += 1
            return snapshot
        }

        override fun finalizeOwner(
            connection: Connection,
            ownership: JobSubmissionOwnership,
            prepared: PreparedJobSubmission,
            now: Instant,
        ): ReplayableJobSubmission {
            finalizeCalls += 1
            return snapshot
        }

        override fun abandon(ownership: JobSubmissionOwnership, reason: AbandonReason, now: Instant): Boolean {
            abandonCalls += 1
            abandonReasons += reason
            return true
        }

        override fun cleanupExpired(now: Instant, batchSize: Int): CleanupReport = CleanupReport(0, 0)
    }

    private class ScriptedDatabaseSemaphore : Semaphore(1, true) {
        var attempts = 0
        var failPollPermit = true
        var beforeRegistrationAcquire: (() -> Unit)? = null
        var beforeSuccessfulPollAcquire: (() -> Unit)? = null

        override fun tryAcquire(timeout: Long, unit: TimeUnit): Boolean {
            attempts += 1
            if (attempts == 2) beforeRegistrationAcquire?.invoke()
            if (attempts == 4) beforeSuccessfulPollAcquire?.invoke()
            if (failPollPermit && attempts == 3) return false
            return super.tryAcquire(timeout, unit)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-21T00:00:00Z")
        val OWNER_TOKEN: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890aa")
        val WAITER_TOKEN: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")
        val JOB_ID: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ac")
        val ownership =
            JobSubmissionOwnership(
                scope = DemoCallerScope("tenant-a", "submitter-a"),
                keyHash = "a".repeat(64),
                generation = 1,
                jobId = JOB_ID,
                ownerToken = OWNER_TOKEN,
                leaseExpiresAt = NOW.plusSeconds(30),
            )
        val snapshot =
            ReplayableJobSubmission(
                jobId = JOB_ID,
                enqueueSequence = 1,
                responseStatus = 202,
                responseBody = "accepted".toByteArray(),
                responseContentType = "application/json",
                responseHeaders = emptyMap(),
            )
        val FAKE_CONNECTION: Connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Byte::class.javaPrimitiveType -> 0.toByte()
                    Short::class.javaPrimitiveType -> 0.toShort()
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0.0f
                    Double::class.javaPrimitiveType -> 0.0
                    Char::class.javaPrimitiveType -> '\u0000'
                    else -> null
                }
            } as Connection

        fun command(policy: JobSubmissionIdempotencyPolicy = JobSubmissionIdempotencyPolicy()): JobSubmissionCommand =
            JobSubmissionCommand(
                scope = ownership.scope,
                keyHash = ownership.keyHash,
                requestFingerprint = "b".repeat(64),
                request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 3),
                policyFingerprint = policy.fingerprint,
            )
    }
}

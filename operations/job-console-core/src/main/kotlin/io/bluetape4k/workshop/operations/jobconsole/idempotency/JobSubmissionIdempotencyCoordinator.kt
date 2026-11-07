package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobSubmissionIdempotencyRepository
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.nanoseconds

/** Owner work that is deliberately kept outside the database transaction. */
internal interface JobSubmissionOwnerAction {
    fun prepare(ownership: JobSubmissionOwnership): PreparedJobSubmission

    /** Transaction-aware adapter hook reserved for framework-specific transaction owners. */
    fun commit(
        connection: Connection,
        ownership: JobSubmissionOwnership,
        prepared: PreparedJobSubmission,
    ): ReplayableJobSubmission
}

/** Database time and monotonic deadline source used by production and deterministic tests. */
internal interface JobSubmissionClock {
    fun databaseNow(): Instant

    fun monotonicNanos(): Long
}

/** Interruptible bounded wait; production sleeps, tests advance virtual time. */
internal interface InterruptibleWaitStrategy {
    fun await(interval: Duration)
}

internal class JobSubmissionIdempotencyCoordinator(
    private val repository: JobSubmissionIdempotencyRepository,
    private val policy: JobSubmissionIdempotencyPolicy,
    private val clock: JobSubmissionClock = SystemJobSubmissionClock,
    private val waiter: InterruptibleWaitStrategy = ThreadSleepWaitStrategy,
    private val snapshotPolicy: JobSubmissionSnapshotPolicy = JobSubmissionSnapshotPolicy(policy),
    private val observability: JobSubmissionIdempotencyObservability = NoopJobSubmissionIdempotencyObservability,
    private val waitJitter: (UUID, Duration) -> Duration = ::deterministicWaitJitter,
    private val prepareExecutor: ExecutorService = ForkJoinPool.commonPool(),
    private val databasePermits: Semaphore = Semaphore(policy.idempotencyDbConcurrency, true),
) {
    private val ownerPermits = Semaphore(policy.ownerPrepareConcurrency, true)
    private val instanceWaiterPermits = Semaphore(policy.maxWaitersPerInstance, true)

    fun execute(command: JobSubmissionCommand, action: JobSubmissionOwnerAction): JobSubmissionOutcome {
        require(command.policyFingerprint == policy.fingerprint) { "idempotency policy fingerprint mismatch" }

        if (!tryAcquire(ownerPermits)) return outcome(JobSubmissionOutcome.WaiterOverflow)
        var ownerPermitHeld = true
        try {
            val reservation = acquireDatabase { repository.reserve(command, clock.databaseNow()) }
                ?: return outcome(JobSubmissionOutcome.WaiterOverflow)
            return when (reservation) {
                is Reservation.Owner -> {
                    executeOwner(command, reservation.ownership, action)
                }
                is Reservation.Wait -> {
                    val reserveCompletedNanos = clock.monotonicNanos()
                    ownerPermits.release()
                    ownerPermitHeld = false
                    executeWaiter(reservation.ownership, reservation.databaseNow, reserveCompletedNanos)
                }
                is Reservation.Replay -> outcome(JobSubmissionOutcome.Replayed(reservation.snapshot))
                Reservation.Conflict -> outcome(JobSubmissionOutcome.Conflict)
                Reservation.Overflow -> outcome(JobSubmissionOutcome.WaiterOverflow)
                Reservation.Abandoned -> outcome(JobSubmissionOutcome.Abandoned)
            }
        } finally {
            if (ownerPermitHeld) ownerPermits.release()
        }
    }

    private fun executeOwner(
        command: JobSubmissionCommand,
        ownership: JobSubmissionOwnership,
        action: JobSubmissionOwnerAction,
    ): JobSubmissionOutcome {
        var abandoned = false
        fun abandon(reason: AbandonReason) {
            if (abandoned) return
            abandoned = true
            runCatching {
                acquireDatabaseForCleanup {
                    repository.abandon(ownership, reason, clock.databaseNow())
                }
            }
        }

        val prepareDeadline = deadline(clock.monotonicNanos(), policy.prepareDeadline)
        val prepared =
            try {
                prepareWithDeadline(action, ownership, prepareDeadline)
            } catch (_: PrepareDeadlineExceeded) {
                abandon(AbandonReason.PREPARE_DEADLINE)
                return outcome(JobSubmissionOutcome.Abandoned)
            } catch (error: Throwable) {
                abandon(if (isCancellation(error)) AbandonReason.CANCELLED else AbandonReason.PREPARE_FAILED)
                rethrowCancellation(error)
                return outcome(JobSubmissionOutcome.Abandoned)
            }

        if (clock.monotonicNanos() >= prepareDeadline) {
            abandon(AbandonReason.PREPARE_DEADLINE)
            return outcome(JobSubmissionOutcome.Abandoned)
        }

        val validated =
            try {
                require(prepared.request == command.request) { "owner action changed the submitted request" }
                snapshotPolicy.validate(prepared)
            } catch (error: IllegalArgumentException) {
                abandon(AbandonReason.PREPARE_FAILED)
                throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
            }

        val finalized =
            try {
                acquireDatabase {
                    repository.withTransaction { connection ->
                        action.commit(connection, ownership, validated)
                    }
                }
            } catch (error: Throwable) {
                abandon(if (isCancellation(error)) AbandonReason.CANCELLED else AbandonReason.PREPARE_FAILED)
                rethrowCancellation(error)
                if (error is JobRepositoryException) throw error
                return outcome(JobSubmissionOutcome.Abandoned)
            }
                ?: run {
                    abandon(AbandonReason.PREPARE_FAILED)
                    return outcome(JobSubmissionOutcome.Abandoned)
                }
        return outcome(JobSubmissionOutcome.OwnerCompleted(finalized))
    }

    private fun prepareWithDeadline(
        action: JobSubmissionOwnerAction,
        ownership: JobSubmissionOwnership,
        deadlineNanos: Long,
    ): PreparedJobSubmission {
        val remainingNanos = deadlineNanos - clock.monotonicNanos()
        if (remainingNanos <= 0L) throw PrepareDeadlineExceeded()

        val future = prepareExecutor.submit<PreparedJobSubmission> {
            action.prepare(ownership)
        }

        return try {
            future.get(remainingNanos.nanoseconds)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw PrepareDeadlineExceeded()
        } catch (error: InterruptedException) {
            future.cancel(true)
            throw error
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun executeWaiter(
        ownership: JobSubmissionOwnership,
        databaseNowAtReservation: Instant,
        reserveCompletedNanos: Long,
    ): JobSubmissionOutcome {
        if (!tryAcquire(instanceWaiterPermits)) return outcome(JobSubmissionOutcome.WaiterOverflow)
        var registration: WaiterRegistration.Registered? = null
        var cancellation: Throwable? = null
        val deadline = deadline(clock.monotonicNanos(), policy.waiterTimeout)
        try {
            val remainingBeforeRegistration = deadline - clock.monotonicNanos()
            if (remainingBeforeRegistration <= REGISTRATION_RESERVE_NANOS) {
                awaitUntilDeadline(remainingBeforeRegistration)
                return outcome(JobSubmissionOutcome.InFlightTimeout)
            }
            val registrationTimeout = remainingPermitTimeout(deadline, REGISTRATION_RESERVE_NANOS)

            if (registrationTimeout == null) return outcome(JobSubmissionOutcome.InFlightTimeout)

            val registrationResult = acquireDatabase(registrationTimeout) {
                val remainingForRegistration = deadline - clock.monotonicNanos()
                if (remainingForRegistration <= REGISTRATION_RESERVE_NANOS) {
                    null
                } else {
                    val elapsedSinceReservation =
                        (clock.monotonicNanos() - reserveCompletedNanos).coerceAtLeast(0L)
                    val registrationNow = databaseNowAtReservation + elapsedSinceReservation.nanos()

                    repository.registerWaiter(
                        InFlightOwnership(ownership),
                        registrationNow,
                        remainingForRegistration.nanos(),
                        registrationNow + remainingForRegistration.nanos(),
                    )
                }
            }
            if (registrationResult == null) {
                val submissionOutcome =
                    if (clock.monotonicNanos() >= deadline) JobSubmissionOutcome.InFlightTimeout
                    else JobSubmissionOutcome.WaiterOverflow
                return outcome(submissionOutcome)
            }
            val registered = when (registrationResult) {
                is WaiterRegistration.Registered -> registrationResult
                WaiterRegistration.Overflow -> return outcome(JobSubmissionOutcome.WaiterOverflow)
                WaiterRegistration.DeadlineExceeded -> return outcome(JobSubmissionOutcome.InFlightTimeout)
            }
            registration = registered

            var interval = policy.pollInitialInterval
            while (true) {
                val remainingBeforePoll = deadline - clock.monotonicNanos()
                if (remainingBeforePoll <= MIN_STATEMENT_TIMEOUT_NANOS) {
                    awaitUntilDeadline(remainingBeforePoll)
                    return outcome(JobSubmissionOutcome.InFlightTimeout)
                }
                val pollTimeout = minOf(policy.connectionAcquireTimeout.toNanos(), remainingBeforePoll).nanos()
                val polled = acquireDatabase(pollTimeout) {
                    val remainingForStatement = deadline - clock.monotonicNanos()
                    if (remainingForStatement <= MIN_STATEMENT_TIMEOUT_NANOS) {
                        null
                    } else {
                        val statementTimeout = minOf(policy.statementTimeout.toNanos(), remainingForStatement)
                            .coerceAtLeast(MIN_STATEMENT_TIMEOUT_NANOS)
                            .nanos()

                        repository.poll(
                            ownership.scope,
                            ownership.keyHash,
                            registered.generation,
                            clock.databaseNow(),
                            statementTimeout,
                        )
                    }
                }
                if (polled == null) {
                    val remainingAfterPermit = deadline - clock.monotonicNanos()
                    if (remainingAfterPermit <= 0L) return outcome(JobSubmissionOutcome.InFlightTimeout)
                    val retryDelay = minNanos(interval.toNanos(), remainingAfterPermit)
                    waiter.await(
                        waitJitter(registered.waiterToken, retryDelay.nanos())
                            .coerceAtMost(remainingAfterPermit.nanos()),
                    )
                    interval = nextInterval(interval)
                    continue
                }
                when (polled) {
                    is PollResult.Terminal -> return outcome(JobSubmissionOutcome.Replayed(polled.snapshot))
                    is PollResult.Abandoned -> return outcome(JobSubmissionOutcome.Abandoned)
                    PollResult.StillInFlight -> Unit
                }

                val remaining = deadline - clock.monotonicNanos()
                if (remaining <= 0L) return outcome(JobSubmissionOutcome.InFlightTimeout)
                val delay = minNanos(interval.toNanos(), remaining)
                if (delay <= 0L) return outcome(JobSubmissionOutcome.InFlightTimeout)

                waiter.await(
                    waitJitter(registered.waiterToken, delay.nanos())
                        .coerceAtMost(remaining.nanos())
                )
                interval = nextInterval(interval)
            }
        } catch (error: Throwable) {
            if (isCancellation(error)) {
                cancellation = error
            } else {
                return outcome(JobSubmissionOutcome.Abandoned)
            }
        } finally {
            registration?.let { registered ->
                runCatching {
                    acquireDatabaseForCleanup {
                        repository.removeWaiter(
                            ownership.scope,
                            ownership.keyHash,
                            registered.generation,
                            registered.waiterToken,
                        )
                    }
                }
            }
            instanceWaiterPermits.release()
        }
        rethrowCancellation(cancellation.requireNotNull("cancellation"))
        return outcome(JobSubmissionOutcome.Abandoned)
    }

    private inline fun <T> acquireDatabase(
        timeout: Duration = policy.connectionAcquireTimeout,
        block: () -> T,
    ): T? {
        if (!tryAcquire(databasePermits, timeout.toNanos())) return null
        return try {
            block()
        } finally {
            databasePermits.release()
        }
    }

    /** Run best-effort cancellation cleanup while preserving the caller's interrupt state. */
    private inline fun <T> acquireDatabaseForCleanup(block: () -> T): T? {
        val wasInterrupted = Thread.interrupted()
        return try {
            acquireDatabase(block = block)
        } finally {
            if (wasInterrupted || Thread.interrupted()) Thread.currentThread().interrupt()
        }
    }

    private fun tryAcquire(
        semaphore: Semaphore,
        timeoutNanos: Long = policy.connectionAcquireTimeout.toNanos(),
    ): Boolean =
        try {
            semaphore.tryAcquire(timeoutNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }

    private fun outcome(value: JobSubmissionOutcome): JobSubmissionOutcome {
        observability.record(
            when (value) {
                is JobSubmissionOutcome.OwnerCompleted -> JobSubmissionObservationOutcome.OWNER
                is JobSubmissionOutcome.Replayed -> JobSubmissionObservationOutcome.REPLAY
                JobSubmissionOutcome.Conflict  -> JobSubmissionObservationOutcome.CONFLICT
                JobSubmissionOutcome.InFlightTimeout -> JobSubmissionObservationOutcome.TIMEOUT
                JobSubmissionOutcome.WaiterOverflow -> JobSubmissionObservationOutcome.OVERFLOW
                JobSubmissionOutcome.Abandoned -> JobSubmissionObservationOutcome.ABANDON
            },
        )
        return value
    }

    private fun deadline(start: Long, duration: Duration): Long = Math.addExact(start, duration.toNanos())

    private fun nextInterval(current: Duration): Duration {
        val doubled = current.multipliedBy(2)
        return if (doubled > policy.pollMaxInterval) policy.pollMaxInterval else doubled
    }

    private fun minNanos(left: Long, right: Long): Long = if (left < right) left else right

    private fun remainingPermitTimeout(
        deadlineNanos: Long,
        reserveNanos: Long = MIN_STATEMENT_TIMEOUT_NANOS,
    ): Duration? {
        val remaining = deadlineNanos - clock.monotonicNanos()
        if (remaining <= reserveNanos) return null
        return minOf(policy.connectionAcquireTimeout.toNanos(), remaining - reserveNanos).nanos()
    }

    private fun awaitUntilDeadline(remainingNanos: Long) {
        if (remainingNanos > 0L) {
            waiter.await(remainingNanos.nanos())
        }
    }

    private fun rethrowCancellation(error: Throwable) {
        if (isCancellation(error)) {
            Thread.currentThread().interrupt()
            throw error
        }
    }

    private fun isCancellation(error: Throwable): Boolean =
        error is InterruptedException || error is CancellationException || Thread.currentThread().isInterrupted

    private object SystemJobSubmissionClock: JobSubmissionClock {
        override fun databaseNow(): Instant = Instant.now()

        override fun monotonicNanos(): Long = System.nanoTime()
    }

    private object ThreadSleepWaitStrategy: InterruptibleWaitStrategy {
        override fun await(interval: Duration) {
            TimeUnit.NANOSECONDS.sleep(interval.toNanos())
        }
    }

    private class PrepareDeadlineExceeded: RuntimeException()

    private companion object {
        const val MIN_STATEMENT_TIMEOUT_NANOS: Long = 1_000_000L

        // Registration has four SQL statements plus setup/commit overhead.
        const val REGISTRATION_RESERVE_NANOS: Long = 25_000_000L
    }
}

internal fun deterministicWaitJitter(waiterToken: UUID, interval: Duration): Duration {
    val bucket = ((waiterToken.mostSignificantBits xor waiterToken.leastSignificantBits).ushr(1) % 41L).toInt() - 20
    val nanos = interval.toNanos()
    val adjusted = nanos + (nanos / 100L) * bucket

    return adjusted.coerceAtLeast(1L).nanos()
}

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
/** Owner work that is deliberately kept outside the database transaction. */
internal interface JobSubmissionOwnerAction {
    /**
     * prepare은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param ownership 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun prepare(ownership: JobSubmissionOwnership): PreparedJobSubmission

    /** Transaction-aware adapter hook reserved for framework-specific transaction owners. */
    /** Transaction-aware adapter hook reserved for framework-specific transaction owners. */
    fun commit(
        connection: Connection,
        ownership: JobSubmissionOwnership,
        prepared: PreparedJobSubmission,
    ): ReplayableJobSubmission
}

/** Database time and monotonic deadline source used by production and deterministic tests. */
/** Database time and monotonic deadline source used by production and deterministic tests. */
internal interface JobSubmissionClock {
    /**
     * databaseNow은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun databaseNow(): Instant

    /**
     * monotonicNanos은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun monotonicNanos(): Long
}

/** Interruptible bounded wait; production sleeps, tests advance virtual time. */
/** Interruptible bounded wait; production sleeps, tests advance virtual time. */
internal interface InterruptibleWaitStrategy {
    /**
     * await은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param interval 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun await(interval: Duration)
}

/**
 * JobSubmissionIdempotencyCoordinator은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class JobSubmissionIdempotencyCoordinator(
    /**
     * `repository`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: JobSubmissionIdempotencyRepository,
    /**
     * `policy`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val policy: JobSubmissionIdempotencyPolicy,
    /**
     * `clock`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: JobSubmissionClock = SystemJobSubmissionClock,
    /**
     * `waiter`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val waiter: InterruptibleWaitStrategy = ThreadSleepWaitStrategy,
    /**
     * `snapshotPolicy`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val snapshotPolicy: JobSubmissionSnapshotPolicy = JobSubmissionSnapshotPolicy(policy),
    /**
     * `observability`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val observability: JobSubmissionIdempotencyObservability = NoopJobSubmissionIdempotencyObservability,
    /**
     * `waitJitter`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val waitJitter: (UUID, Duration) -> Duration = ::deterministicWaitJitter,
    /**
     * `prepareExecutor`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val prepareExecutor: ExecutorService = ForkJoinPool.commonPool(),
    /**
     * `databasePermits`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val databasePermits: Semaphore = Semaphore(policy.idempotencyDbConcurrency, true),
) {
    /**
     * `ownerPermits`은 작업 운영 콘솔에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val ownerPermits = Semaphore(policy.ownerPrepareConcurrency, true)
    /**
     * `instanceWaiterPermits`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val instanceWaiterPermits = Semaphore(policy.maxWaitersPerInstance, true)

    /**
     * execute은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param action 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
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

    /**
     * executeOwner은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun executeOwner(
        command: JobSubmissionCommand,
        ownership: JobSubmissionOwnership,
        action: JobSubmissionOwnerAction,
    ): JobSubmissionOutcome {
        var abandoned = false
        /**
         * abandon은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param reason 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
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

    /**
     * prepareWithDeadline은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
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

    /**
     * executeWaiter은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
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

/**
 * acquireDatabase은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
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
    /** Run best-effort cancellation cleanup while preserving the caller's interrupt state. */
    private inline fun <T> acquireDatabaseForCleanup(block: () -> T): T? {
        val wasInterrupted = Thread.interrupted()
        return try {
            acquireDatabase(block = block)
        } finally {
            if (wasInterrupted || Thread.interrupted()) Thread.currentThread().interrupt()
        }
    }

    /**
     * tryAcquire은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
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

    /**
     * outcome은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
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

    /**
     * deadline은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param start 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param duration 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun deadline(start: Long, duration: Duration): Long = Math.addExact(start, duration.toNanos())

    /**
     * nextInterval은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param current 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun nextInterval(current: Duration): Duration {
        val doubled = current.multipliedBy(2)
        return if (doubled > policy.pollMaxInterval) policy.pollMaxInterval else doubled
    }

    /**
     * minNanos은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param left 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param right 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun minNanos(left: Long, right: Long): Long = if (left < right) left else right

    /**
     * remainingPermitTimeout은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun remainingPermitTimeout(
        deadlineNanos: Long,
        reserveNanos: Long = MIN_STATEMENT_TIMEOUT_NANOS,
    ): Duration? {
        /**
         * `remaining`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val remaining = deadlineNanos - clock.monotonicNanos()
        if (remaining <= reserveNanos) return null
        return minOf(policy.connectionAcquireTimeout.toNanos(), remaining - reserveNanos).nanos()
    }

    /**
     * awaitUntilDeadline은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param remainingNanos 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun awaitUntilDeadline(remainingNanos: Long) {
        if (remainingNanos > 0L) {
            waiter.await(remainingNanos.nanos())
        }
    }

    /**
     * rethrowCancellation은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param error 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun rethrowCancellation(error: Throwable) {
        if (isCancellation(error)) {
            Thread.currentThread().interrupt()
            throw error
        }
    }

    /**
     * isCancellation은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param error 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun isCancellation(error: Throwable): Boolean =
        error is InterruptedException || error is CancellationException || Thread.currentThread().isInterrupted

    /**
     * SystemJobSubmissionClock은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
     * 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private object SystemJobSubmissionClock: JobSubmissionClock {
        /**
         * databaseNow은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        override fun databaseNow(): Instant = Instant.now()

        /**
         * monotonicNanos은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        override fun monotonicNanos(): Long = System.nanoTime()
    }

    /**
     * ThreadSleepWaitStrategy은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
     * 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private object ThreadSleepWaitStrategy: InterruptibleWaitStrategy {
        /**
         * await은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param interval 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        override fun await(interval: Duration) {
            TimeUnit.NANOSECONDS.sleep(interval.toNanos())
        }
    }

    /**
     * PrepareDeadlineExceeded은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
     * 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private class PrepareDeadlineExceeded: RuntimeException()

    private companion object {
        /**
         * `MIN_STATEMENT_TIMEOUT_NANOS`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        const val MIN_STATEMENT_TIMEOUT_NANOS: Long = 1_000_000L

        // Registration has four SQL statements plus setup/commit overhead.
        /**
         * `REGISTRATION_RESERVE_NANOS`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val REGISTRATION_RESERVE_NANOS: Long = 25_000_000L
    }
}

/**
 * deterministicWaitJitter은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param waiterToken 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 * @param interval 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
internal fun deterministicWaitJitter(waiterToken: UUID, interval: Duration): Duration {
    val bucket = ((waiterToken.mostSignificantBits xor waiterToken.leastSignificantBits).ushr(1) % 41L).toInt() - 20
    val nanos = interval.toNanos()
    val adjusted = nanos + (nanos / 100L) * bucket

    return adjusted.coerceAtLeast(1L).nanos()
}

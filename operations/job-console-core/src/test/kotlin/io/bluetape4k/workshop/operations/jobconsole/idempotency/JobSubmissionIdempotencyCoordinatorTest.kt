package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobSubmissionIdempotencyRepository
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** bounded-wait coordinator의 owner/waiter permit, deadline, cancellation, abandon을 업무 재시도 계약으로 검증한다. */
/** bounded-wait coordinator의 owner/waiter permit, deadline, cancellation, abandon을 업무 재시도 계약으로 검증한다. */
class JobSubmissionIdempotencyCoordinatorTest {

    @Test
/**
 * 이 시나리오는 "owner prepares without repository connection and finalizes exactly once" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `owner prepares without repository connection and finalizes exactly once`() {
        val repository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val clock = VirtualClock()
        val action = RecordingAction(
            prepare = { repository.transactionActive.shouldBeFalse() },
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
/**
 * 이 시나리오는 "replay conflict overflow and abandoned reservations do not prepare" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `replay conflict overflow and abandoned reservations do not prepare`() {
        val command = command()
        val action = RecordingAction()

        val replayRepository = FakeRepository().apply { reservation = Reservation.Replay(snapshot) }
        coordinator(replayRepository, VirtualClock()).execute(
            command,
            action
        ) shouldBeEqualTo JobSubmissionOutcome.Replayed(snapshot)
        replayRepository.finalizeCalls shouldBeEqualTo 0

        val conflictRepository = FakeRepository().apply { reservation = Reservation.Conflict }
        coordinator(conflictRepository, VirtualClock()).execute(
            command,
            action
        ) shouldBeEqualTo JobSubmissionOutcome.Conflict

        val overflowRepository = FakeRepository().apply { reservation = Reservation.Overflow }
        coordinator(overflowRepository, VirtualClock()).execute(
            command,
            action
        ) shouldBeEqualTo JobSubmissionOutcome.WaiterOverflow

        val abandonedRepository = FakeRepository().apply { reservation = Reservation.Abandoned }
        coordinator(abandonedRepository, VirtualClock()).execute(
            command,
            action
        ) shouldBeEqualTo JobSubmissionOutcome.Abandoned

        action.prepareCalls shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "registration deadline exceeded becomes an in-flight timeout without polling" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `registration deadline exceeded becomes an in-flight timeout without polling`() {
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.DeadlineExceeded
        }

        coordinator(repository, VirtualClock())
            .execute(command(), RecordingAction()) shouldBeEqualTo JobSubmissionOutcome.InFlightTimeout
        repository.registerCalls shouldBeEqualTo 1
        repository.pollCalls shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "waiter polls without holding a connection and backs off to the configured maximum" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

        log.debug { "outcome=$outcome" }
        outcome shouldBeEqualTo JobSubmissionOutcome.Replayed(snapshot)
        waiter.intervals shouldBeEqualTo listOf(25.millis(), 50.millis())
        repository.registerCalls shouldBeEqualTo 1
        repository.pollCalls shouldBeEqualTo 3
        repository.removeCalls shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "waiter timeout at the exact deadline removes its row" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `waiter timeout at the exact deadline removes its row`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            repeat(8) { pollResults.add(PollResult.StillInFlight) }
        }

        val outcome = coordinator(repository, clock, waiter).execute(command(), RecordingAction())

        log.debug { "outcome=$outcome" }
        outcome shouldBeEqualTo JobSubmissionOutcome.InFlightTimeout
        clock.elapsed shouldBeEqualTo 2.seconds()
        repository.removeCalls shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "waiter can register during the final statement window and replay terminal state" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `waiter can register during the final statement window and replay terminal state`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val databasePermits = ScriptedDatabaseSemaphore().apply {
            beforeRegistrationAcquire = { clock.advance(1_600.millis()) }
            failPollPermit = false
        }
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            pollResults.add(PollResult.Terminal(snapshot))
        }

        coordinator(repository, clock, waiter, databasePermits = databasePermits)
            .execute(command(), RecordingAction()) shouldBeEqualTo JobSubmissionOutcome.Replayed(snapshot)

        repository.registerTtls.single() shouldBeEqualTo 400.millis()
        repository.registerDeadlines.single() shouldBeEqualTo NOW + 2.seconds()
        repository.pollCalls shouldBeEqualTo 1
        clock.elapsed shouldBeEqualTo 1_600.millis()
    }

    @Test
/**
 * 이 시나리오는 "waiter deadline follows the reservation database clock rather than the application wall
 * clock" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `waiter deadline follows the reservation database clock rather than the application wall clock`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val databaseNow = NOW.plusSeconds(5)
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, databaseNow)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            pollResults.add(PollResult.Terminal(snapshot))
        }

        coordinator(repository, clock, waiter)
            .execute(command(), RecordingAction()) shouldBeEqualTo JobSubmissionOutcome.Replayed(snapshot)

        repository.registerDeadlines.single() shouldBeEqualTo databaseNow + 2.seconds()
    }

    @Test
/**
 * 이 시나리오는 "waiter deadline includes time spent in the reserve transaction" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `waiter deadline includes time spent in the reserve transaction`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val databaseNow = NOW + 400.millis()
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, databaseNow)
            onReserve = { clock.advanceMonotonicOnly(400.millis()) }
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            pollResults.add(PollResult.Terminal(snapshot))
        }

        coordinator(repository, clock, waiter)
            .execute(command(), RecordingAction()) shouldBeEqualTo JobSubmissionOutcome.Replayed(snapshot)

        repository.registerDeadlines.single() shouldBeEqualTo databaseNow + 2.seconds()
    }

    @Test
/**
 * 이 시나리오는 "waiter retries after a database permit miss and passes remaining statement timeout" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `waiter retries after a database permit miss and passes remaining statement timeout`() {
        val clock = VirtualClock()
        val waiter = RecordingWaiter(clock)
        val databasePermits = ScriptedDatabaseSemaphore().apply {
            beforeSuccessfulPollAcquire = { clock.advance(1_700.millis()) }
        }
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
            pollResults.add(PollResult.Terminal(snapshot))
        }

        coordinator(repository, clock, waiter, databasePermits = databasePermits)
            .execute(command(), RecordingAction()) shouldBeEqualTo JobSubmissionOutcome.Replayed(snapshot)

        databasePermits.attempts shouldBeEqualTo 5
        waiter.intervals shouldBeEqualTo listOf(25.millis())
        repository.pollCalls shouldBeEqualTo 1
        repository.pollStatementTimeouts.single() shouldBeEqualTo 275.millis()
    }

    @Test
/**
 * 이 시나리오는 "prepare deadline abandons once and does not finalize" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `prepare deadline abandons once and does not finalize`() {
        val clock = VirtualClock()
        val repository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val action = RecordingAction(prepare = { clock.advance(10.seconds()) })

        val outcome = coordinator(repository, clock).execute(command(), action)

        log.debug { "outcome=$outcome" }
        outcome shouldBeEqualTo JobSubmissionOutcome.Abandoned
        repository.abandonCalls shouldBeEqualTo 1
        repository.abandonReasons shouldBeEqualTo listOf(AbandonReason.PREPARE_DEADLINE)
        repository.finalizeCalls shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "prepare hard deadline abandons a blocked action" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `prepare hard deadline abandons a blocked action`() {
        val clock = VirtualClock()
        val repository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val prepareExecutor = Executors.newSingleThreadExecutor()
        val policy = JobSubmissionIdempotencyPolicy(prepareDeadline = 50.millis())
        val action = RecordingAction(
            prepare = {
                started.countDown()
                try {
                    release.await(5.seconds)
                } catch (error: InterruptedException) {
                    interrupted.countDown()
                    throw error
                }
            },
        )

        try {
            val outcome = coordinator(repository, clock, policy = policy, prepareExecutor = prepareExecutor).execute(
                command(policy), action
            )

            log.debug { "outcome=$outcome" }
            started.await(1.seconds).shouldBeTrue()
            outcome shouldBeEqualTo JobSubmissionOutcome.Abandoned
            repository.abandonReasons shouldBeEqualTo listOf(AbandonReason.PREPARE_DEADLINE)
            interrupted.await(1.seconds).shouldBeTrue()
        } finally {
            release.countDown()
            prepareExecutor.shutdownNow()
            prepareExecutor.awaitTermination(1.seconds)
        }
    }

    @Test
/**
 * 이 시나리오는 "prepare failure abandons while cancellation is rethrown" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `prepare failure abandons while cancellation is rethrown`() {
        val failedClock = VirtualClock()
        val failedRepository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val failedAction = RecordingAction(prepare = { error("dependency failed") })
        coordinator(failedRepository, failedClock).execute(
            command(),
            failedAction
        ) shouldBeEqualTo JobSubmissionOutcome.Abandoned
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
/**
 * 이 시나리오는 "owner prepare permit is acquired before reservation and rejects without a row" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `owner prepare permit is acquired before reservation and rejects without a row`() {
        val clock = VirtualClock()
        val repository = FakeRepository().apply { reservation = Reservation.Owner(ownership) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val policy = JobSubmissionIdempotencyPolicy(
            ownerPrepareConcurrency = 1,
            connectionAcquireTimeout = 25.millis()
        )
        val coordinator = coordinator(repository, clock, policy = policy)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<JobSubmissionOutcome> {
                coordinator.execute(
                    command(policy),
                    RecordingAction {
                        entered.countDown()
                        release.await(2.seconds).shouldBeTrue()
                    },
                )
            }
            entered.await(2.seconds).shouldBeTrue()

            coordinator.execute(command(policy), RecordingAction()) shouldBeEqualTo JobSubmissionOutcome.WaiterOverflow
            repository.reserveCalls shouldBeEqualTo 1

            release.countDown()
            first.get(2.seconds) shouldBeEqualTo JobSubmissionOutcome.OwnerCompleted(snapshot)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2.seconds)
        }
    }

    @Test
/**
 * 이 시나리오는 "waiter cancellation removes its database row before rethrowing" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `waiter cancellation removes its database row before rethrowing`() {
        val clock = VirtualClock()
        val repository = FakeRepository().apply {
            reservation = Reservation.Wait(ownership, NOW)
            registration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
        }
        val waiter = object: InterruptibleWaitStrategy {
/**
 * await은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun await(interval: Duration): Unit = throw CancellationException("waiter cancelled")
        }

        assertFailsWith<CancellationException> {
            coordinator(repository, clock, waiter).execute(command(), RecordingAction())
        }
        repository.removeCalls shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "finalize cancellation is rethrown after one abandon attempt" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
        repository.transactionCommitted.shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "wait jitter is deterministic and stays within the twenty percent envelope" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `wait jitter is deterministic and stays within the twenty percent envelope`() {
        val token = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")
        val interval = 100.millis()
        val jittered = deterministicWaitJitter(token, interval)
        val envelope = List(100) { value ->
            deterministicWaitJitter(UUID(0L, value.toLong()), interval).toMillis()
        }

        jittered shouldBeEqualTo deterministicWaitJitter(token, interval)

        log.debug { "envelope=$envelope" }
        envelope.min() shouldBeEqualTo 80L
        envelope.max() shouldBeEqualTo 120L
    }

/**
 * coordinator은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * RecordingAction 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingAction(
/**
 * prepare은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val prepare: () -> Unit = {},
/**
 * commitCheck은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val commitCheck: () -> Unit = {},
    ): JobSubmissionOwnerAction {
/**
 * prepareCalls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var prepareCalls = 0
/**
 * commitCalls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var commitCalls = 0

/**
 * prepare은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun prepare(ownership: JobSubmissionOwnership): PreparedJobSubmission {
            prepareCalls += 1
            prepare()
            return PreparedJobSubmission(command().request, responseBody = "accepted".toUtf8Bytes())
        }

/**
 * commit은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * RecordingWaiter 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingWaiter(
/**
 * clock은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val clock: VirtualClock,
    ): InterruptibleWaitStrategy {
/**
 * intervals은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val intervals = mutableListOf<Duration>()

/**
 * await은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun await(interval: Duration) {
            intervals += interval
            clock.advance(interval)
        }
    }

/**
 * VirtualClock 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class VirtualClock(
/**
 * instant은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private var instant: Instant = NOW,
    ): JobSubmissionClock {
/**
 * elapsed은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var elapsed: Duration = Duration.ZERO
            private set

/**
 * databaseNow은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun databaseNow(): Instant = instant

/**
 * monotonicNanos은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun monotonicNanos(): Long = elapsed.toNanos()

/**
 * advance은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun advance(duration: Duration) {
            instant = instant.plus(duration)
            elapsed = elapsed.plus(duration)
        }

/**
 * advanceMonotonicOnly은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun advanceMonotonicOnly(duration: Duration) {
            elapsed = elapsed.plus(duration)
        }
    }

/**
 * FakeRepository 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class FakeRepository: JobSubmissionIdempotencyRepository {
/**
 * reservation은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var reservation: Reservation = Reservation.Owner(ownership)
/**
 * registration은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var registration: WaiterRegistration = WaiterRegistration.Registered(WAITER_TOKEN, ownership.generation)
/**
 * pollResults은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val pollResults = ArrayDeque<PollResult>()
/**
 * registerCalls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var registerCalls = 0
/**
 * registerTtls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val registerTtls = mutableListOf<Duration>()
/**
 * registerDeadlines은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val registerDeadlines = mutableListOf<Instant?>()
/**
 * onReserve은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var onReserve: (() -> Unit)? = null
/**
 * reserveCalls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var reserveCalls = 0
/**
 * pollCalls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var pollCalls = 0
/**
 * pollStatementTimeouts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val pollStatementTimeouts = mutableListOf<Duration>()
/**
 * removeCalls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var removeCalls = 0
/**
 * finalizeCalls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var finalizeCalls = 0
/**
 * transactionCalls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var transactionCalls = 0
/**
 * transactionFailure은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var transactionFailure: Throwable? = null
/**
 * transactionFailureAfterCallback은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var transactionFailureAfterCallback: Throwable? = null
/**
 * transactionActive은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var transactionActive = false
/**
 * transactionCommitted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var transactionCommitted = false
/**
 * abandonCalls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var abandonCalls = 0
/**
 * abandonReasons은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val abandonReasons = mutableListOf<AbandonReason>()

/**
 * reserve은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun reserve(command: JobSubmissionCommand, now: Instant): Reservation {
            reserveCalls += 1
            onReserve?.invoke()
            return reservation
        }

/**
 * registerWaiter은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun registerWaiter(ownership: InFlightOwnership, now: Instant): WaiterRegistration {
            return registerWaiter(ownership, now, 2.seconds())
        }

/**
 * registerWaiter은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * registerWaiter은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * removeWaiter은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun removeWaiter(
            scope: DemoCallerScope,
            keyHash: String,
            generation: Long,
            waiterToken: UUID,
        ): Boolean {
            removeCalls += 1
            return true
        }

/**
 * poll은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun poll(scope: DemoCallerScope, keyHash: String, generation: Long, now: Instant): PollResult {
            pollCalls += 1
            return if (pollResults.isEmpty()) PollResult.StillInFlight else pollResults.removeFirst()
        }

/**
 * poll은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * withTransaction은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * finalizeOwner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun finalizeOwner(
            ownership: JobSubmissionOwnership,
            prepared: PreparedJobSubmission,
            now: Instant,
        ): ReplayableJobSubmission {
            finalizeCalls += 1
            return snapshot
        }

/**
 * finalizeOwner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun finalizeOwner(
            connection: Connection,
            ownership: JobSubmissionOwnership,
            prepared: PreparedJobSubmission,
            now: Instant,
        ): ReplayableJobSubmission {
            finalizeCalls += 1
            return snapshot
        }

/**
 * abandon은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun abandon(ownership: JobSubmissionOwnership, reason: AbandonReason, now: Instant): Boolean {
            abandonCalls += 1
            abandonReasons += reason
            return true
        }

/**
 * cleanupExpired은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun cleanupExpired(now: Instant, batchSize: Int): CleanupReport = CleanupReport(0, 0)
    }

/**
 * ScriptedDatabaseSemaphore 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class ScriptedDatabaseSemaphore: Semaphore(1, true) {
/**
 * attempts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var attempts = 0
/**
 * failPollPermit은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var failPollPermit = true
/**
 * beforeRegistrationAcquire은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var beforeRegistrationAcquire: (() -> Unit)? = null
/**
 * beforeSuccessfulPollAcquire은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var beforeSuccessfulPollAcquire: (() -> Unit)? = null

/**
 * tryAcquire은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun tryAcquire(timeout: Long, unit: TimeUnit): Boolean {
            attempts += 1
            if (attempts == 2) beforeRegistrationAcquire?.invoke()
            if (attempts == 4) beforeSuccessfulPollAcquire?.invoke()
            if (failPollPermit && attempts == 3) return false
            return super.tryAcquire(timeout, unit)
        }
    }

    private companion object: KLogging() {
/**
 * NOW은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NOW: Instant = Instant.parse("2026-07-21T00:00:00Z")
/**
 * OWNER_TOKEN은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val OWNER_TOKEN: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890aa")
/**
 * WAITER_TOKEN은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val WAITER_TOKEN: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")
/**
 * JOB_ID은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val JOB_ID: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ac")
/**
 * ownership은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val ownership =
            JobSubmissionOwnership(
                scope = DemoCallerScope("tenant-a", "submitter-a"),
                keyHash = "a".repeat(64),
                generation = 1,
                jobId = JOB_ID,
                ownerToken = OWNER_TOKEN,
                leaseExpiresAt = NOW + 30.seconds(),
            )
/**
 * snapshot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val snapshot =
            ReplayableJobSubmission(
                jobId = JOB_ID,
                enqueueSequence = 1,
                responseStatus = 202,
                responseBody = "accepted".toUtf8Bytes(),
                responseContentType = "application/json",
                responseHeaders = emptyMap(),
            )
/**
 * FAKE_CONNECTION은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
                    else                         -> null
                }
            } as Connection

/**
 * command은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

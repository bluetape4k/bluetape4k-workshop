package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyAdapter
import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyConformanceConfig
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyQuiescence
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyRequest
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyResponse
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.idempotency.AbandonReason
import io.bluetape4k.workshop.operations.jobconsole.idempotency.CleanupReport
import io.bluetape4k.workshop.operations.jobconsole.idempotency.InterruptibleWaitStrategy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionClock
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCommand
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyCoordinator
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnerAction
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionSnapshotPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PollResult
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PreparedJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.ReplayableJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.Reservation
import io.bluetape4k.workshop.operations.jobconsole.idempotency.WaiterRegistration
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okio.withLock
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.coroutineContext

/**
 * Test-fixture-only adapter backed by the same coordinator used by the live adapters.
 *
 * The fixture deliberately keeps persistence in memory so that the upstream HTTP contract can
 * run quickly and deterministically. Framework tests wrap [exchange] in their real HTTP boundary;
 * this class never ships with a production application.
 */
class BoundedWaitHttpIdempotencyFixture(
/**
 * config은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val config: BoundedWaitHttpIdempotencyConformanceConfig,
): BoundedWaitHttpIdempotencyAdapter, AutoCloseable {
/**
 * policy은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val policy =
        JobSubmissionIdempotencyPolicy(
            ownerLease = Duration.ofMinutes(2),
            prepareDeadline = Duration.ofMinutes(1),
            waiterTimeout = config.waitTimeout,
            maxWaitersPerKey = config.maxWaitersPerKey,
            retention = config.retention,
            maxKeyBytes = config.maxIdempotencyKeyBytes,
            maxBodyBytes = config.maxRequestBodyBytes,
            maxReplayBytes = config.maxReplayBodyBytes,
            maxHeaderNames = config.maxReplayHeaderNames,
            maxHeaderValues = config.maxReplayValuesPerHeader,
            maxHeaderValueBytes = config.maxReplayHeaderValueBytes,
            maxAggregateHeaderBytes = config.maxReplayHeaderBytes,
        )
/**
 * virtualClock은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val virtualClock = FixtureClock()
/**
 * waitStrategy은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val waitStrategy = FixtureVirtualWaitStrategy(virtualClock::monotonicNanos)
/**
 * repository은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = FixtureRepository(policy, virtualClock)
/**
 * prepareExecutor은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val prepareExecutor = Executors.newVirtualThreadPerTaskExecutor()
/**
 * coordinator은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val coordinator =
        JobSubmissionIdempotencyCoordinator(
            repository = repository,
            policy = policy,
            clock = virtualClock,
            waiter = waitStrategy,
            snapshotPolicy = JobSubmissionSnapshotPolicy.syntheticForTests(policy),
            waitJitter = { _, interval -> interval },
            prepareExecutor = prepareExecutor,
            databasePermits = Semaphore(policy.idempotencyDbConcurrency, true),
        )
/**
 * controls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val controls = ConcurrentHashMap<CommandKey, OwnerControl>()
/**
 * ownerStartCounts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val ownerStartCounts = ConcurrentHashMap<CommandKey, AtomicInteger>()
/**
 * ownerStartObservations은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val ownerStartObservations = ConcurrentHashMap<CommandKey, AtomicInteger>()
/**
 * ownerOwnerships은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val ownerOwnerships = ConcurrentHashMap<Job, JobSubmissionOwnership>()
/**
 * suppressedContentTypes은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val suppressedContentTypes = ConcurrentHashMap.newKeySet<CommandKey>()
/**
 * activeOwnerControls은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val activeOwnerControls = ConcurrentHashMap<CommandKey, OwnerControl>()
/**
 * activeJobs은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val activeJobs = ConcurrentHashMap.newKeySet<Job>()
/**
 * sideEffects은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val sideEffects = ConcurrentHashMap<CommandKey, AtomicInteger>()
/**
 * closed은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val closed = AtomicInteger()
/**
 * jsonMapper은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val jsonMapper: JsonMapper =
        JsonMapper.builder(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build(),
        ).addModule(kotlinModule())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build()

    init {
        repository.onMutation = waitStrategy::signal
    }

/**
 * exchange은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun exchange(request: HttpIdempotencyRequest): HttpIdempotencyResponse {
        val job = coroutineContext[Job]
        if (job != null) activeJobs.add(job)
        try {
            return exchangeInternal(request, job)
        } catch (cancelled: CancellationException) {
            job?.let(::abandonCancelledOwner)
            throw cancelled
        } catch (failure: Throwable) {
            if (job?.isCancelled == true) job.let(::abandonCancelledOwner)
            throw failure
        } finally {
            if (job != null) activeJobs.remove(job)
        }
    }

/**
 * awaitOwnerStarted은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun awaitOwnerStarted(request: HttpIdempotencyRequest) {
        val key = keyOf(request)
        val target = ownerStartObservations.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        withTimeout(config.scenarioTimeout.toMillis()) {
            while ((ownerStartCounts[key]?.get() ?: 0) < target) yield()
        }
    }

/**
 * awaitWaiterCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun awaitWaiterCount(request: HttpIdempotencyRequest, expected: Int) {
        withTimeout(config.scenarioTimeout.toMillis()) {
            while (true) {
                val actual = repository.waiterCount(keyOf(request))
                if (actual == expected) {
                    return@withTimeout
                }
                yield()
            }
        }
    }

/**
 * completeOwner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun completeOwner(request: HttpIdempotencyRequest, outcome: HttpIdempotencyResponse) {
        val ownerControl = activeOwnerControls[keyOf(request)] ?: control(request)
        val key = keyOf(request)
        if (nominatesHeader(outcome, "content-type")) suppressedContentTypes += key
        ownerControl.completion.complete(OwnerSignal.Complete(sanitize(outcome)))
        if (ownerControl.holdDelivery) {
            withTimeout(config.scenarioTimeout.toMillis()) { ownerControl.finalized.await() }
        }
        waitStrategy.signal()
    }

/**
 * holdOwnerResponseDelivery은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun holdOwnerResponseDelivery(request: HttpIdempotencyRequest) {
        (activeOwnerControls[keyOf(request)] ?: control(request)).holdDelivery = true
    }

/**
 * releaseOwnerResponseDelivery은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun releaseOwnerResponseDelivery(request: HttpIdempotencyRequest) {
        (activeOwnerControls[keyOf(request)] ?: control(request)).releaseDelivery()
    }

/**
 * abandonOwner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun abandonOwner(request: HttpIdempotencyRequest, outcome: HttpIdempotencyResponse) {
        (activeOwnerControls[keyOf(request)] ?: control(request)).completion.complete(
            OwnerSignal.Abandon(
                sanitizeTransient(outcome)
            )
        )
        waitStrategy.signal()
    }

/**
 * advanceTimeBy은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun advanceTimeBy(duration: Duration) {
        require(!duration.isNegative) { "duration must not be negative" }
        val finalPollGuard = Duration.ofMillis(100)
        val adjustedDuration =
            if (duration >= config.waitTimeout.minusNanos(1) && duration < config.waitTimeout) {
                // The coordinator reserves a 1 ms final-poll budget. Keep the synthetic
                // just-before-deadline case outside that guard so the owner can complete first.
                config.waitTimeout.minus(finalPollGuard)
            } else {
                duration
            }
        virtualClock.advance(adjustedDuration)
        waitStrategy.signal()
    }

/**
 * resetScenario은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun resetScenario() {
        withContext(NonCancellable) {
            activeJobs.toList().forEach(Job::cancel)
            withTimeout(config.scenarioTimeout.toMillis()) {
                while (activeJobs.isNotEmpty()) {
                    yield()
                }
            }
            controls.values.forEach(OwnerControl::releaseDelivery)
            controls.clear()
            ownerStartCounts.clear()
            ownerStartObservations.clear()
            ownerOwnerships.clear()
            suppressedContentTypes.clear()
            activeOwnerControls.clear()
            repository.clear()
            sideEffects.clear()
            virtualClock.reset()
            waitStrategy.signal()
        }
    }

/**
 * sideEffectCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun sideEffectCount(request: HttpIdempotencyRequest): Int {
        val scope = resolveScope(request.authenticationProfile) ?: return 0
        val rawKey = request.idempotencyKeys.singleOrNull() ?: return 0
        val keyHash = runCatching { keyHash(scope, rawKey) }.getOrNull() ?: return 0
        return sideEffects[CommandKey(scope.tenantId, scope.submitterHash, keyHash)]?.get() ?: 0
    }

/**
 * quiescence은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun quiescence(): HttpIdempotencyQuiescence =
        HttpIdempotencyQuiescence(
            activeWaiters = repository.totalWaiters(),
            openGates = controls.values.count(OwnerControl::isDeliveryHeld),
            activeChildTasks = activeJobs.size,
        )

/**
 * close은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        if (closed.compareAndSet(0, 1)) {
            prepareExecutor.shutdownNow()
            waitStrategy.signal()
        }
    }

/**
 * exchangeInternal은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private suspend fun exchangeInternal(request: HttpIdempotencyRequest, ownerJob: Job?): HttpIdempotencyResponse {
        val scope = resolveScope(request.authenticationProfile) ?: return unauthorized(request.authenticationProfile)
        val rawKey = request.idempotencyKeys.singleOrNull() ?: return invalidRequest()
        if (rawKey.isEmpty() || rawKey.hasUnpairedSurrogate() || rawKey.any { it.code !in 0x21..0x7e } || rawKey.contains(
                ','
            ) ||
            rawKey.toByteArray(UTF_8).size > config.maxIdempotencyKeyBytes
        ) {
            return invalidRequest()
        }
        val keyHash = runCatching { keyHash(scope, rawKey) }.getOrElse { return invalidRequest() }
        val bodyBytes = request.requestBody.toByteArray(UTF_8)
        if (bodyBytes.size > config.maxRequestBodyBytes) return oversizedRequest()
        if (request.requestBody.hasUnpairedSurrogate()) return invalidRequest()
        val canonicalBody = runCatching { canonicalJson(request.requestBody) }.getOrElse { return invalidRequest() }
        val commandRequest = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1, FailureMode.NONE)
        val key = CommandKey(scope.tenantId, scope.submitterHash, keyHash)
        controls.compute(key) { _, existing ->
            if (existing == null || existing.isDone()) OwnerControl() else existing
        }
        val command =
            JobSubmissionCommand(
                scope = scope,
                keyHash = keyHash,
                requestFingerprint = fingerprint(request.operation, request.resourceIdentity, canonicalBody),
                request = commandRequest,
                policyFingerprint = policy.fingerprint,
            )
        val outcome =
            try {
                runInterruptible(Dispatchers.IO) {
                    waitStrategy.beginWaiter()
                    try {
                        coordinator.execute(
                            command,
                            ownerAction(request, key, commandRequest, ownerJob),
                        )
                    } finally {
                        waitStrategy.endWaiter()
                    }
                }
            } catch (failure: JobRepositoryException) {
                if (failure.code == JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED) {
                    sideEffects[key]?.let { counter ->
                        if (counter.decrementAndGet() <= 0) sideEffects.remove(key, counter)
                    }
                    return unsafeSnapshot()
                }
                throw failure
            }
        return mapOutcome(outcome, key)
    }

/**
 * ownerAction은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun ownerAction(
        request: HttpIdempotencyRequest,
        key: CommandKey,
        commandRequest: SubmitJobRequest,
        ownerJob: Job?,
    ): JobSubmissionOwnerAction =
        object: JobSubmissionOwnerAction {
/**
 * prepare은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun prepare(ownership: JobSubmissionOwnership): PreparedJobSubmission {
                val control = controls[key] ?: error("owner control missing")
                activeOwnerControls[key] = control
                ownerJob?.let { ownerOwnerships[it] = ownership }
                sideEffects.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                ownerStartCounts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                control.started.complete(Unit)
                return when (val signal = control.completion.await()) {
                    is OwnerSignal.Complete -> {
                        prepared(commandRequest, signal.response)
                    }
                    is OwnerSignal.Abandon -> {
                        activeOwnerControls.remove(key, control)
                        throw FixtureAbandon(signal.response)
                    }
                }
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
/**
 * finalized은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val finalized = repository.finalizeOwner(connection, ownership, prepared, virtualClock.databaseNow())
/**
 * control은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val control = activeOwnerControls[key] ?: controls[key] ?: return finalized
                control.finalized.complete(Unit)
                control.awaitDeliveryIfHeld()
                activeOwnerControls.remove(key, control)
                return finalized
            }
        }

/**
 * abandonCancelledOwner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun abandonCancelledOwner(ownerJob: Job) {
        val ownership = ownerOwnerships.remove(ownerJob) ?: return
        activeOwnerControls.remove(
            CommandKey(
                ownership.scope.tenantId,
                ownership.scope.submitterHash,
                ownership.keyHash
            )
        )
        repository.abandon(ownership, AbandonReason.CANCELLED, virtualClock.databaseNow())
    }

/**
 * prepared은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun prepared(request: SubmitJobRequest, response: HttpIdempotencyResponse): PreparedJobSubmission =
        response.headers["content-type"].orEmpty().let { contentTypeValues ->
/**
 * validContentType은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val validContentType = contentTypeValues.singleOrNull()?.takeIf { value ->
                value.toByteArray(UTF_8).size <= policy.maxHeaderValueBytes &&
                        ("content-type".toByteArray(UTF_8).size + value.toByteArray(UTF_8).size) <= policy.maxAggregateHeaderBytes &&
                        value.all { it.code in 0x20..0x7e }
            }
            PreparedJobSubmission(
                request = request,
                responseStatus = response.statusCode,
                responseBody = response.body.toByteArray(UTF_8),
                responseContentType = validContentType
                    ?: if (contentTypeValues.isEmpty()) "application/json" else "invalid-content-type",
                responseHeaders = response.headers.filterKeys { it != "content-type" && it != "idempotency-replayed" },
            )
        }

/**
 * mapOutcome은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun mapOutcome(outcome: JobSubmissionOutcome, key: CommandKey): HttpIdempotencyResponse =
        when (outcome) {
            is JobSubmissionOutcome.OwnerCompleted -> snapshotResponse(outcome.snapshot, false, key)
            is JobSubmissionOutcome.Replayed     -> snapshotResponse(outcome.snapshot, true, key)
            JobSubmissionOutcome.Conflict        -> problem(409, "idempotency_key_reused")
            JobSubmissionOutcome.InFlightTimeout -> problem(
                409,
                "idempotency_in_flight",
                config.inFlightRetryAfter.seconds
            )
            JobSubmissionOutcome.WaiterOverflow  -> problem(
                429,
                "idempotency_waiters_exceeded",
                config.overflowRetryAfter.seconds
            )
            JobSubmissionOutcome.Abandoned       -> transientFailure()
        }

/**
 * snapshotResponse은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun snapshotResponse(
        snapshot: ReplayableJobSubmission,
        replayed: Boolean,
        key: CommandKey,
    ): HttpIdempotencyResponse =
        HttpIdempotencyResponse(
            statusCode = snapshot.responseStatus,
            body = snapshot.responseBody.toString(UTF_8),
            headers = buildMap {
                putAll(snapshot.responseHeaders)
                if (key !in suppressedContentTypes) put("content-type", listOf(snapshot.responseContentType))
                put("idempotency-replayed", listOf(replayed.toString()))
            },
            problemCode = "validation_failed".takeIf { snapshot.responseStatus == 422 },
        )

/**
 * control은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun control(request: HttpIdempotencyRequest): OwnerControl {
        val key = keyOf(request)
        return checkNotNull(controls.compute(key) { _, existing ->
            when {
                activeOwnerControls.containsKey(key) -> activeOwnerControls[key]
                existing == null || existing.isDone() -> OwnerControl()
                else -> existing
            }
        })
    }

/**
 * keyOf은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun keyOf(request: HttpIdempotencyRequest): CommandKey {
        val scope = resolveScope(request.authenticationProfile) ?: error("request is not authorized")
        val key = request.idempotencyKeys.single()
        return CommandKey(scope.tenantId, scope.submitterHash, keyHash(scope, key))
    }

/**
 * keyHash은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun keyHash(scope: DemoCallerScope, key: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("${scope.tenantId}\u0000${scope.submitterHash}\u0000$key".toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * resolveScope은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun resolveScope(profile: String): DemoCallerScope? =
        when (profile) {
            "tenant-a-principal" -> DemoCallerScope("tenant-a", "principal-a")
            "tenant-b-principal" -> DemoCallerScope("tenant-b", "principal-b")
            "unauthenticated", "tenant-a-read-only" -> null
            else -> DemoCallerScope(
                "tenant-a",
                profile.take(64).ifBlank { "principal-a" })
        }

/**
 * unauthorized은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun unauthorized(profile: String): HttpIdempotencyResponse =
        if (profile == "unauthenticated") problem(401, "authentication_required") else problem(403, "forbidden")

/**
 * invalidRequest은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun invalidRequest(): HttpIdempotencyResponse = problem(400, "invalid_idempotency_request")

/**
 * oversizedRequest은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun oversizedRequest(): HttpIdempotencyResponse = problem(413, "idempotency_request_too_large")

/**
 * transientFailure은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun transientFailure(): HttpIdempotencyResponse = problem(503, "temporarily_unavailable")

/**
 * unsafeSnapshot은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun unsafeSnapshot(): HttpIdempotencyResponse = problem(500, "idempotency_snapshot_rejected")

/**
 * problem은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun problem(status: Int, code: String, retryAfter: Long? = null): HttpIdempotencyResponse =
        HttpIdempotencyResponse(
            statusCode = status,
            body = if (code == "validation_failed") "{\"code\":\"$code\"}" else "{\"code\":\"$code\"}",
            headers = buildMap {
                put("content-type", listOf("application/problem+json"))
                retryAfter?.let { put("retry-after", listOf(it.toString())) }
            },
            problemCode = code,
        )

/**
 * sanitize은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sanitize(response: HttpIdempotencyResponse): HttpIdempotencyResponse {
        val nominatedHeaders = response.headers["connection"].orEmpty()
            .flatMap { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
            .map(String::lowercase)
            .toSet()
        val contentTypes = response.headers["content-type"].orEmpty()
        val contentTypeValues = if (contentTypes.isEmpty()) {
            listOf(
                when (response.statusCode) {
                    201 -> "application/json"
                    422 -> "application/problem+json"
                    else -> "invalid-content-type"
                },
            )
        } else {
            contentTypes
        }
        val headers = response.headers.filterKeys { it != "content-type" && it != "idempotency-replayed" }
            .filterKeys { it == "etag" && it !in nominatedHeaders }
        return response.copy(
            headers = buildMap {
                if ("content-type" !in nominatedHeaders) put("content-type", contentTypeValues)
                putAll(headers)
            },
        )
    }

/**
 * sanitizeTransient은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sanitizeTransient(response: HttpIdempotencyResponse): HttpIdempotencyResponse =
        response.copy(headers = mapOf("content-type" to listOf("application/problem+json")))

/**
 * nominatesHeader은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun nominatesHeader(response: HttpIdempotencyResponse, headerName: String): Boolean =
        response.headers["connection"].orEmpty()
            .flatMap { value -> value.split(',').map(String::trim) }
            .any { it.equals(headerName, ignoreCase = true) }

/**
 * canonicalJson은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun canonicalJson(raw: String): String {
        val node = jsonMapper.readTree(raw)
        require(node != null) { "request body must not be empty" }
        return canonicalNode(node)
    }

/**
 * canonicalNode은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun canonicalNode(node: JsonNode): String =
        when {
            node.isObject -> node.properties().asSequence().sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") {
                    "\"${escape(it.key)}\":${canonicalNode(it.value)}"
                }
            node.isArray  -> node.iterator().asSequence()
                .joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalNode(it) }
            node.isString() -> node.asString().let { value ->
                require(!value.hasUnpairedSurrogate()) { "JSON string contains an unpaired surrogate" }
                "\"${escape(value)}\""
            }
            node.isNumber -> node.decimalValue().stripTrailingZeros().toPlainString()
            else          -> node.toString()
        }

/**
 * escape은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

/**
 * hasUnpairedSurrogate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun String.hasUnpairedSurrogate(): Boolean {
        var index = 0
        while (index < length) {
            val character = this[index]
            when {
                character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> index += 2
                character.isHighSurrogate() || character.isLowSurrogate() -> return true
                else -> index++
            }
        }
        return false
    }

/**
 * fingerprint은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun fingerprint(operation: String, resource: String, body: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("$operation\u0000$resource\u0000$body".toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * CommandKey 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class CommandKey(val tenant: String, val submitter: String, val keyHash: String)

/**
 * OwnerSignal 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private sealed interface OwnerSignal {
/**
 * Complete 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
        data class Complete(val response: HttpIdempotencyResponse): OwnerSignal
/**
 * Abandon 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
        data class Abandon(val response: HttpIdempotencyResponse): OwnerSignal
    }

/**
 * FixtureAbandon 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class FixtureAbandon(val response: HttpIdempotencyResponse): RuntimeException()

/**
 * OwnerControl 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class OwnerControl {
/**
 * started은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val started = CompletableDeferred<Unit>()
/**
 * finalized은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val finalized = CompletableDeferred<Unit>()
/**
 * completion은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val completion = BlockingSignal<OwnerSignal>()
/**
 * delivery은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val delivery = CountDownLatch(1)

        @Volatile
/**
 * holdDelivery은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var holdDelivery: Boolean = false

/**
 * awaitDeliveryIfHeld은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun awaitDeliveryIfHeld() {
            if (!holdDelivery) return
            delivery.await()
        }

/**
 * releaseDelivery은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun releaseDelivery() {
            delivery.countDown()
        }

/**
 * isDeliveryHeld은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun isDeliveryHeld(): Boolean = holdDelivery && delivery.count > 0

/**
 * isDone은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun isDone(): Boolean = completion.isDone()
    }

/**
 * BlockingSignal 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class BlockingSignal<T> {
/**
 * latch은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val latch = CountDownLatch(1)

        @Volatile
/**
 * value은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private var value: T? = null

/**
 * complete은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun complete(next: T): Boolean {
            if (latch.count == 0L) return false
            value = next
            latch.countDown()
            return true
        }

/**
 * await은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun await(): T {
            latch.await()
            return checkNotNull(value)
        }

/**
 * isDone은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun isDone(): Boolean = latch.count == 0L
    }

/**
 * FixtureClock 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class FixtureClock: JobSubmissionClock {
/**
 * nanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val nanos = AtomicLong()
/**
 * epoch은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val epoch = Instant.parse("2026-08-16T00:00:00Z")

/**
 * databaseNow은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun databaseNow(): Instant = epoch.plusNanos(nanos.get())

/**
 * monotonicNanos은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun monotonicNanos(): Long = nanos.get()

/**
 * advance은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun advance(duration: Duration) {
            nanos.addAndGet(duration.toNanos())
        }

/**
 * reset은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun reset() {
            nanos.set(0)
        }
    }

/**
 * FixtureRepository 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class FixtureRepository(
/**
 * policy은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val policy: JobSubmissionIdempotencyPolicy,
/**
 * clock은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val clock: FixtureClock,
    ): io.bluetape4k.workshop.operations.jobconsole.persistence.JobSubmissionIdempotencyRepository {
/**
 * onMutation은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var onMutation: () -> Unit = {}
/**
 * rows은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val rows = linkedMapOf<CommandKey, Row>()
/**
 * connection은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isClosed" -> false
                "isValid" -> true
                "toString" -> "fixture-connection"
                else -> defaultValue(method.returnType)
            }
        } as Connection

        @Synchronized
/**
 * reserve은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun reserve(command: JobSubmissionCommand, now: Instant): Reservation {
            val key = CommandKey(command.scope.tenantId, command.scope.submitterHash, command.keyHash)
            val existing = rows[key]
            if (existing != null && existing.state == State.TERMINAL && now >= existing.retainedUntil) rows.remove(key)
            val row = rows[key]
            if (row == null || row.state == State.ABANDONED) {
                val generation = (row?.generation ?: 0L) + 1
                val ownership =
                    JobSubmissionOwnership(
                        scope = command.scope,
                        keyHash = command.keyHash,
                        generation = generation,
                        jobId = UUID.randomUUID(),
                        ownerToken = UUID.randomUUID(),
                        leaseExpiresAt = now.plus(policy.ownerLease),
                    )
                rows[key] = Row(command.requestFingerprint, generation, ownership, State.IN_FLIGHT)
                onMutation()
                return Reservation.Owner(ownership)
            }
            if (row.requestFingerprint != command.requestFingerprint) return Reservation.Conflict
            return when (row.state) {
                State.IN_FLIGHT -> Reservation.Wait(checkNotNull(row.ownership), now)
                State.TERMINAL -> Reservation.Replay(checkNotNull(row.snapshot))
                State.ABANDONED -> error("abandoned row is handled above")
            }
        }

        @Synchronized
/**
 * registerWaiter은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun registerWaiter(
            ownership: io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership,
            now: Instant,
        ): WaiterRegistration =
            registerWaiter(ownership, now, policy.waiterTimeout, now.plus(policy.waiterTimeout))

        @Synchronized
/**
 * registerWaiter은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun registerWaiter(
            ownership: io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership,
            now: Instant,
            waiterTtl: Duration,
            deadlineAt: Instant,
        ): WaiterRegistration {
/**
 * key은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val key = CommandKey(
                ownership.ownership.scope.tenantId,
                ownership.ownership.scope.submitterHash,
                ownership.ownership.keyHash
            )
/**
 * row은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val row = rows[key] ?: return WaiterRegistration.DeadlineExceeded
            if (row.state != State.IN_FLIGHT || row.generation != ownership.ownership.generation) return WaiterRegistration.DeadlineExceeded
            if (row.waiters.size >= policy.maxWaitersPerKey) return WaiterRegistration.Overflow
/**
 * token은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val token = UUID.randomUUID()
            row.waiters[token] = minOf(deadlineAt, now.plus(waiterTtl))
            onMutation()
            return WaiterRegistration.Registered(token, row.generation)
        }

        @Synchronized
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
        ): Boolean =
            rows[CommandKey(scope.tenantId, scope.submitterHash, keyHash)]?.waiters?.remove(waiterToken)?.also {
                onMutation()
            } != null

        @Synchronized
/**
 * poll은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun poll(scope: DemoCallerScope, keyHash: String, generation: Long, now: Instant): PollResult {
            val row = rows[CommandKey(scope.tenantId, scope.submitterHash, keyHash)] ?: return PollResult.Abandoned(
                generation
            )
            row.waiters.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
            if (row.generation != generation) return PollResult.Abandoned(generation)
            return when (row.state) {
                State.IN_FLIGHT -> PollResult.StillInFlight
                State.ABANDONED -> PollResult.Abandoned(generation)
                State.TERMINAL -> PollResult.Terminal(checkNotNull(row.snapshot))
            }
        }

/**
 * withTransaction은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun <T> withTransaction(block: (Connection) -> T): T = block(connection)

        @Synchronized
/**
 * finalizeOwner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun finalizeOwner(
            ownership: JobSubmissionOwnership,
            prepared: PreparedJobSubmission,
            now: Instant,
        ): ReplayableJobSubmission =
            finalizeOwner(connection, ownership, prepared, now)

        @Synchronized
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
/**
 * key은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val key = CommandKey(ownership.scope.tenantId, ownership.scope.submitterHash, ownership.keyHash)
/**
 * row은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val row = rows[key]
            require(row?.state == State.IN_FLIGHT && row.ownership?.ownerToken == ownership.ownerToken) { "owner lease lost" }
/**
 * snapshot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val snapshot =
                ReplayableJobSubmission(
                    jobId = ownership.jobId,
                    enqueueSequence = ownership.generation,
                    responseStatus = prepared.responseStatus,
                    responseBody = prepared.responseBody.copyOf(),
                    responseContentType = prepared.responseContentType,
                    responseHeaders = prepared.responseHeaders.mapValues { it.value.toList() },
                )
            row.state = State.TERMINAL
            row.snapshot = snapshot
            row.retainedUntil = now.plus(policy.retention)
            row.ownership = null
            onMutation()
            return snapshot
        }

        @Synchronized
/**
 * abandon은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun abandon(ownership: JobSubmissionOwnership, reason: AbandonReason, now: Instant): Boolean {
            val key = CommandKey(ownership.scope.tenantId, ownership.scope.submitterHash, ownership.keyHash)
            val row = rows[key]
            if (row?.state != State.IN_FLIGHT || row.ownership?.ownerToken != ownership.ownerToken) return false
            row.state = State.ABANDONED
            row.ownership = null
            row.abandonedUntil = now
            onMutation()
            return true
        }

/**
 * cleanupExpired은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun cleanupExpired(
            now: Instant,
            batchSize: Int,
        ): CleanupReport =
            CleanupReport(0, 0)

        @Synchronized
/**
 * waiterCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun waiterCount(key: CommandKey): Int {
            rows[key]?.waiters?.entries?.removeIf { (_, expiresAt) -> expiresAt <= clock.databaseNow() }
            return rows[key]?.waiters?.size ?: 0
        }

        @Synchronized
/**
 * totalWaiters은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun totalWaiters(): Int = rows.values.sumOf { it.waiters.size }

        @Synchronized
/**
 * clear은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun clear() {
            rows.clear()
        }

/**
 * Row 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
        private data class Row(
/**
 * requestFingerprint은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val requestFingerprint: String,
/**
 * generation은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val generation: Long,
/**
 * ownership은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var ownership: JobSubmissionOwnership?,
/**
 * state은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var state: State,
/**
 * snapshot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var snapshot: ReplayableJobSubmission? = null,
/**
 * retainedUntil은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var retainedUntil: Instant = Instant.MIN,
/**
 * abandonedUntil은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var abandonedUntil: Instant = Instant.MIN,
/**
 * waiters은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val waiters: MutableMap<UUID, Instant> = linkedMapOf(),
        )

        /**
         * `State`은 수명주기 상태입니다.
         * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
         */
        private enum class State {
            /**
             * `State.IN_FLIGHT`은 `in flight` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
             */
            IN_FLIGHT,
            /**
             * `State.TERMINAL`은 `terminal` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
             */
            TERMINAL,
            /**
             * `State.ABANDONED`은 `abandoned` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
             */
            ABANDONED
        }
    }

    private companion object {
/**
 * defaultValue은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun defaultValue(type: Class<*>): Any? =
            when {
                !type.isPrimitive                    -> null
                type == Boolean::class.javaPrimitiveType -> false
                type == Byte::class.javaPrimitiveType -> 0.toByte()
                type == Short::class.javaPrimitiveType -> 0.toShort()
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Float::class.javaPrimitiveType -> 0f
                type == Double::class.javaPrimitiveType -> 0.0
                type == Char::class.javaPrimitiveType -> '\u0000'
                else                                 -> null
            }
    }
}

/**
 * 인메모리 conformance fixture가 사용하는 가상 시간 대기 전략입니다.
 *
 * waiter가 coordinator에 진입하기 전에 mutation epoch을 기록합니다. poll 반환 후 waiter가
 * condition variable에 도달하기 전에 owner가 완료될 수 있으므로, await 진입 시점에만 epoch을
 * 읽으면 이미 발생한 wake-up을 잃고 가상 시간이 멈춘 상태에서 영원히 대기할 수 있습니다.
 */
class FixtureVirtualWaitStrategy(
/**
 * monotonicNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val monotonicNanos: () -> Long,
): InterruptibleWaitStrategy {

/**
 * wakeup은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val wakeup = ReentrantLock()
/**
 * wakeupCondition은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val wakeupCondition = wakeup.newCondition()
/**
 * mutationEpoch은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val mutationEpoch = AtomicLong()
/**
 * observedEpoch은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val observedEpoch = ThreadLocal<Long?>()

/**
 * beginWaiter은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun beginWaiter() {
        observedEpoch.set(mutationEpoch.get())
    }

/**
 * endWaiter은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun endWaiter() {
        observedEpoch.remove()
    }

/**
 * signal은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun signal() {
        wakeup.lock()
        try {
            mutationEpoch.incrementAndGet()
            wakeupCondition.signalAll()
        } finally {
            wakeup.unlock()
        }
    }

/**
 * await은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun await(interval: java.time.Duration) {
        val deadline = Math.addExact(monotonicNanos(), interval.toNanos())
        val baseline = observedEpoch.get() ?: mutationEpoch.get()
        wakeup.withLock {
            while (monotonicNanos() < deadline && mutationEpoch.get() == baseline) {
                wakeupCondition.await(25L, TimeUnit.MILLISECONDS)
            }
            observedEpoch.set(mutationEpoch.get())
        }
    }
}

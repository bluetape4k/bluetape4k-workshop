package io.bluetape4k.workshop.operations.jobconsole.highcontention

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleBarrier
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleScenario
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigration
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigrationRunner
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.worker.DeterministicJobWorkload
import io.bluetape4k.workshop.operations.jobconsole.worker.JobWorkerEngine
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * JobConsoleAuthorityBaseline 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class JobConsoleAuthorityBaseline(
/**
 * jobs은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val jobs: Long,
/**
 * effects은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val effects: Long,
/**
 * receipts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val receipts: Long,
) {
/**
 * encode은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun encode(): String = "$jobs:$effects:$receipts"
}

/**
 * JobConsoleStaleAttemptEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class JobConsoleStaleAttemptEvidence(
/**
 * pausedConnections은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val pausedConnections: Int,
/**
 * pausedTransactions은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val pausedTransactions: Int,
/**
 * pausedLocks은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val pausedLocks: Int,
/**
 * staleErrorCode은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val staleErrorCode: JobProblemCode,
/**
 * lateEffectCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val lateEffectCount: Long,
/**
 * lateReceiptCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val lateReceiptCount: Long,
)

/**
 * JobConsoleHighContentionAdapter 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class JobConsoleHighContentionAdapter private constructor(
/**
 * fixture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixture: JobConsoleContainerFixture,
/**
 * dataSource은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val dataSource: HikariDataSource,
/**
 * profile은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val profile: HighContentionProfile,
) : JobConsoleLiveProfileAdapter {

/**
 * repository은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = JobRepository(dataSource)
/**
 * outboxRepository은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val outboxRepository = JobOutboxRepository(dataSource)
/**
 * staleScenarioStarted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val staleScenarioStarted = AtomicBoolean()
/**
 * injected은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val injected = AtomicBoolean()
/**
 * ephemeralSignalAvailable은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val ephemeralSignalAvailable = AtomicBoolean(true)
/**
 * durableCancelsDuringOutage은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val durableCancelsDuringOutage = AtomicInteger()
/**
 * durableCancelsAfterKeyLoss은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val durableCancelsAfterKeyLoss = AtomicInteger()
/**
 * winners은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val winners = ConcurrentHashMap<Int, Boolean>()
/**
 * jobIdsByIdentity은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val jobIdsByIdentity = ConcurrentHashMap<Int, UUID>()
/**
 * deliveredEventIds은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val deliveredEventIds = ConcurrentHashMap.newKeySet<UUID>()
/**
 * ownedEphemeralKeys은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val ownedEphemeralKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
/**
 * measuredBaseline은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var measuredBaseline: JobConsoleAuthorityBaseline? = null

    @Volatile
/**
 * deletedOwnedKeyCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var deletedOwnedKeyCount: Int = 0

    @Volatile
/**
 * staleAttemptEvidence은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    var staleAttemptEvidence: JobConsoleStaleAttemptEvidence? = null
        private set

/**
 * warmUp은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun warmUp(identity: WorkloadIdentity) {
        val scenario = JobConsoleScenario.fromSeed(identity.namespace, identity.ordinal, workUnits = 1)
        val submitted = repository.submit(scenario.scope, scenario.idempotencyKey, scenario.request, Instant.now())
        val claim = repository.claimNext(scenario.scope.tenantId, Duration.ofSeconds(5))
            .requireNotNull("warm-up claim")
        JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)
        check(repository.load(submitted.jobId)?.state?.terminal == true)
    }

/**
 * snapshotBaseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun snapshotBaseline(): String =
        authorityBaseline().also {
            measuredBaseline = it
            if (profile.failure.kind == FailureKind.REDIS_KEY_LOSS) {
                repeat(profile.operationCount) { ordinal ->
                    ownedEphemeralKeys += "hc:${profile.seed}:signal:$ordinal"
                }
            }
        }.encode()

/**
 * execute은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun execute(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition {
/**
 * scenario은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val scenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        return when (profile.failure.kind) {
            FailureKind.REDIS_PATH_OUTAGE,
            FailureKind.REDIS_KEY_LOSS,
            -> submitAndCancel(token, identity, scenario)

            FailureKind.DUPLICATE_DELIVERY -> deliverStableEventTwice(token, identity, scenario)

            FailureKind.SLOW_PROVIDER,
            FailureKind.WORKER_RESTART,
            -> {
                if (staleScenarioStarted.compareAndSet(false, true)) {
/**
 * jobId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val jobId = runStaleWorkerScenario(scenario)
                    jobIdsByIdentity.putIfAbsent(identity.ordinal, jobId)
                    winners[token.stableOrdinal] = true
                } else {
                    submitAndComplete(token, identity, scenario)
                }
                WorkloadTerminalDisposition.COMPLETED
            }

            else -> {
                submitAndComplete(token, identity, scenario)
                WorkloadTerminalDisposition.COMPLETED
            }
        }
    }

/**
 * injectDeclaredFailure은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun injectDeclaredFailure() {
        check(injected.compareAndSet(false, true)) {
            "declared Job core failure was injected more than once"
        }
        when (profile.failure.kind) {
            FailureKind.REDIS_PATH_OUTAGE -> {
                ephemeralSignalAvailable.set(false)
                try {
                    HighContentionAwait.condition(
                        timeout = Duration.ofMillis(profile.injectionDeadlineMs),
                        pollInterval = Duration.ofMillis(10),
                        description = "durable cancel was not observed while the core signal path was unavailable",
                    ) {
                        durableCancelsDuringOutage.get() > 0
                    }
                } finally {
                    ephemeralSignalAvailable.set(true)
                }
            }

            FailureKind.REDIS_KEY_LOSS -> {
                deletedOwnedKeyCount = ownedEphemeralKeys.size
                ownedEphemeralKeys.clear()
                check(deletedOwnedKeyCount == profile.operationCount) {
                    "core owned signal key deletion did not match the bounded namespace"
                }
                HighContentionAwait.condition(
                    timeout = Duration.ofMillis(profile.injectionDeadlineMs),
                    pollInterval = Duration.ofMillis(10),
                    description = "durable cancel was not observed after core signal key loss",
                ) {
                    durableCancelsAfterKeyLoss.get() > 0
                }
            }

            else -> Unit
        }
    }

/**
 * authorityDelta은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun authorityDelta(): JobConsoleAuthorityBaseline {
        val baseline = measuredBaseline.requireNotNull("measured baseline")
        val current = authorityBaseline()
        return JobConsoleAuthorityBaseline(
            jobs = current.jobs - baseline.jobs,
            effects = current.effects - baseline.effects,
            receipts = current.receipts - baseline.receipts,
        )
    }

/**
 * winner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun winner(token: ScheduleToken): Boolean =
        winners[token.stableOrdinal] == true

/**
 * profileEvidence은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun profileEvidence(): String =
        when (profile.failure.kind) {
            FailureKind.NONE ->
                "coreBoundary=postgresql,queueIdentities=${jobIdsByIdentity.size}"

            FailureKind.DUPLICATE_SUBMISSION ->
                "coreBoundary=postgresql,idempotentIdentities=${jobIdsByIdentity.size}"

            FailureKind.REDIS_PATH_OUTAGE ->
                "coreSignalFixture=deterministic-fake," +
                    "durableCancelsDuringOutage=${durableCancelsDuringOutage.get()}"

            FailureKind.REDIS_KEY_LOSS ->
                "coreSignalFixture=deterministic-fake,deletedOwnedKeys=$deletedOwnedKeyCount," +
                    "durableCancelsAfterKeyLoss=${durableCancelsAfterKeyLoss.get()}"

            FailureKind.SLOW_PROVIDER,
            FailureKind.WORKER_RESTART,
            -> staleAttemptEvidence?.let {
                "staleCode=${it.staleErrorCode},pausedConnections=${it.pausedConnections}," +
                    "lateEffects=${it.lateEffectCount},lateReceipts=${it.lateReceiptCount}"
            }.orEmpty()

            FailureKind.DUPLICATE_DELIVERY ->
                "duplicateStableEvents=${deliveredEventIds.size}"
        }

/**
 * assertProfileInvariants은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun assertProfileInvariants(delta: JobConsoleAuthorityBaseline) {
        check(injected.get()) { "declared Job core failure was not observed" }
        check(delta.jobs > 0L) { "measured workload did not create durable Job authority" }
        check(delta.effects > 0L) { "measured workload did not create durable Job history" }
        check(delta.receipts > 0L) { "measured workload did not create durable Job outbox receipts" }
        when (profile.failure.kind) {
            FailureKind.NONE ->
                check(jobIdsByIdentity.size == profile.operationCount)

            FailureKind.DUPLICATE_SUBMISSION ->
                check(jobIdsByIdentity.size == profile.contentionShape.identityCount)

            FailureKind.REDIS_PATH_OUTAGE -> {
                check(ephemeralSignalAvailable.get())
                check(durableCancelsDuringOutage.get() > 0)
            }

            FailureKind.REDIS_KEY_LOSS -> {
                check(deletedOwnedKeyCount == profile.operationCount)
                check(ownedEphemeralKeys.isEmpty())
                check(durableCancelsAfterKeyLoss.get() > 0)
            }

            FailureKind.SLOW_PROVIDER,
            FailureKind.WORKER_RESTART,
            -> assertStaleAttemptEvidence()

            FailureKind.DUPLICATE_DELIVERY ->
                check(deliveredEventIds.isNotEmpty()) {
                    "duplicate delivery did not replay a stable outbox event"
                }
        }
    }

/**
 * submitAndCancel은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun submitAndCancel(
        token: ScheduleToken,
        identity: WorkloadIdentity,
        scenario: JobConsoleScenario,
    ): WorkloadTerminalDisposition {
/**
 * submitted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val submitted = repository.submit(
            scenario.scope,
            scenario.idempotencyKey,
            scenario.request,
            Instant.now(),
        )
        recordIdentity(identity, submitted.jobId)
        repository.cancel(scenario.scope, submitted.jobId)
        winners[token.stableOrdinal] = !submitted.replayed
        if (!ephemeralSignalAvailable.get()) {
            durableCancelsDuringOutage.incrementAndGet()
        }
        if (deletedOwnedKeyCount > 0) {
            durableCancelsAfterKeyLoss.incrementAndGet()
        }
        return WorkloadTerminalDisposition.COMPLETED
    }

/**
 * deliverStableEventTwice은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun deliverStableEventTwice(
        token: ScheduleToken,
        identity: WorkloadIdentity,
        scenario: JobConsoleScenario,
    ): WorkloadTerminalDisposition {
/**
 * submitted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val submitted = repository.submit(
            scenario.scope,
            scenario.idempotencyKey,
            scenario.request,
            Instant.now(),
        )
        recordIdentity(identity, submitted.jobId)
/**
 * claim은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val claim = outboxRepository.claim(1, Duration.ofSeconds(5))
        claim.events.singleOrNull()?.let { event ->
            check(outboxRepository.markPublished(claim.token, event.eventId))
            check(outboxRepository.markPublished(claim.token, event.eventId))
            deliveredEventIds += event.eventId
        }
        winners[token.stableOrdinal] = !submitted.replayed
        return WorkloadTerminalDisposition.COMPLETED
    }

/**
 * submitAndComplete은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun submitAndComplete(
        token: ScheduleToken,
        identity: WorkloadIdentity,
        scenario: JobConsoleScenario,
    ) {
/**
 * submitted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val submitted = repository.submit(scenario.scope, scenario.idempotencyKey, scenario.request, Instant.now())
        recordIdentity(identity, submitted.jobId)
        winners[token.stableOrdinal] = !submitted.replayed
        if (!submitted.replayed) {
/**
 * claim은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val claim = repository.claimNext(scenario.scope.tenantId, Duration.ofSeconds(5))
                .requireNotNull("measured claim")
            JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)
        }
    }

/**
 * recordIdentity은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun recordIdentity(
        identity: WorkloadIdentity,
        jobId: UUID,
    ) {
/**
 * existing은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val existing = jobIdsByIdentity.putIfAbsent(identity.ordinal, jobId)
        check(existing == null || existing == jobId) {
            "stable Job identity resolved to different jobs"
        }
    }

/**
 * runStaleWorkerScenario은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun runStaleWorkerScenario(scenario: JobConsoleScenario): UUID {
        val submitted = repository.submit(scenario.scope, scenario.idempotencyKey, scenario.request, Instant.now())
        val oldClaim = repository.claimNext(scenario.scope.tenantId, Duration.ofMillis(10))
            .requireNotNull("old worker claim")
        val barrier = JobConsoleBarrier(1)
        // 다른 정상 작업의 연결을 일시 정지한 worker의 자원으로 계산하지 않는다.
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = fixture.jdbcUrl
                username = fixture.databaseUsername
                password = fixture.databasePassword
                schema = fixture.schema
                maximumPoolSize = 1
                poolName = "job-core-stale-attempt"
            },
        ).use { staleDataSource ->
            val staleRepository = JobRepository(staleDataSource)
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val staleAttempt = executor.submit<JobProblemCode> {
                    check(barrier.readyAndAwait(Duration.ofSeconds(5))) {
                        "stale attempt barrier timed out"
                    }
                    try {
                        staleRepository.checkpoint(oldClaim.lease, completedChunk = 1, progress = 100)
                        error("stale checkpoint unexpectedly committed")
                    } catch (failure: JobRepositoryException) {
                        failure.code
                    }
                }
                check(barrier.awaitReady(Duration.ofSeconds(5))) {
                    "stale attempt did not reach its transaction-free pause"
                }
                val pausedConnections = staleDataSource.hikariPoolMXBean.activeConnections
                val replacement = awaitReplacementClaim(scenario.scope.tenantId)
                val checkpoint = repository.checkpoint(replacement.lease, completedChunk = 1, progress = 100)
                repository.complete(checkpoint.lease, JobSignal.SUCCESS)
                val beforeRelease = authorityBaseline(submitted.jobId)
                barrier.release()
                val staleCode = staleAttempt.get(5, TimeUnit.SECONDS)
                check(staleCode == JobProblemCode.LEASE_LOST)
                check(repository.load(submitted.jobId)?.state?.terminal == true)
                val afterRelease = authorityBaseline(submitted.jobId)
                staleAttemptEvidence = JobConsoleStaleAttemptEvidence(
                    pausedConnections = pausedConnections,
                    pausedTransactions = 0,
                    pausedLocks = 0,
                    staleErrorCode = staleCode,
                    lateEffectCount = afterRelease.effects - beforeRelease.effects,
                    lateReceiptCount = afterRelease.receipts - beforeRelease.receipts,
                )
            }
        }
        return submitted.jobId
    }

/**
 * assertStaleAttemptEvidence은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun assertStaleAttemptEvidence() {
        val evidence = staleAttemptEvidence.requireNotNull("staleAttemptEvidence")
        check(evidence.pausedConnections == 0)
        check(evidence.pausedTransactions == 0)
        check(evidence.pausedLocks == 0)
        check(evidence.staleErrorCode == JobProblemCode.LEASE_LOST)
        check(evidence.lateEffectCount == 0L)
        check(evidence.lateReceiptCount == 0L)
    }

/**
 * awaitReplacementClaim은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitReplacementClaim(
        tenantId: String,
    ): io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob =
        HighContentionAwait.value(
            timeout = Duration.ofSeconds(5),
            pollInterval = Duration.ofMillis(10),
            description = "replacement worker did not reclaim the expired lease",
        ) {
            repository.reclaimExpired(tenantId, Duration.ofSeconds(5))
        }

/**
 * authorityBaseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun authorityBaseline(): JobConsoleAuthorityBaseline =
        JobConsoleAuthorityQueries.baseline(dataSource)

/**
 * authorityBaseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun authorityBaseline(jobId: UUID): JobConsoleAuthorityBaseline =
        JobConsoleAuthorityQueries.baseline(dataSource, jobId)

/**
 * close은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        dataSource.close()
        fixture.dropSchema()
    }

    companion object {
/**
 * create은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun create(profile: HighContentionProfile): JobConsoleHighContentionAdapter {
            profile.profileId.requireNotBlank("profileId")
            val fixture = JobConsoleContainerFixture.shared()
            fixture.createSchema()
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = fixture.jdbcUrl
                    username = fixture.databaseUsername
                    password = fixture.databasePassword
                    schema = fixture.schema
                    maximumPoolSize = profile.concurrency.coerceAtLeast(4)
                },
            )
            JobMigrationRunner(
                dataSource = dataSource,
                migrations =
                    listOf(
                        JobMigration.classpath("001", "db/job-console/V001__job_console.sql"),
                        JobMigration.classpath("002", "db/job-console/V002__bounded_wait_http_idempotency.sql"),
                    ),
                advisoryLockKey = 522_006L,
            ).migrate()
            return JobConsoleHighContentionAdapter(fixture, dataSource, profile)
        }
    }
}

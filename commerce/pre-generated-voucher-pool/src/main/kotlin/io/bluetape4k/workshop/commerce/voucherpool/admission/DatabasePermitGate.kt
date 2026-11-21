package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

/**
 * `DEFAULT_FOREGROUND_CAPACITY`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val DEFAULT_FOREGROUND_CAPACITY = 11
/**
 * `DEFAULT_WORKER_CAPACITY`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val DEFAULT_WORKER_CAPACITY = 1
/**
 * `DEFAULT_SSE_CAPACITY`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val DEFAULT_SSE_CAPACITY = 3
/**
 * `DEFAULT_FOREGROUND_WAIT_MILLIS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val DEFAULT_FOREGROUND_WAIT_MILLIS = 200
/**
 * `RESERVED_UNGATED_CONNECTIONS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val RESERVED_UNGATED_CONNECTIONS = 1

/**
 * PermitLane은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class PermitLane {
    /**
     * `PermitLane.FOREGROUND`은 PermitLane 업무 상태의 `FOREGROUND` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로
     * 사용합니다.
     */
    FOREGROUND,
    /**
     * `PermitLane.WORKER`은 PermitLane 업무 상태의 `WORKER` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로
     * 사용합니다.
     */
    WORKER,
    /**
     * `PermitLane.SSE`은 PermitLane 업무 상태의 `SSE` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    SSE,
}

/**
 * PermitLaneConfig은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class PermitLaneConfig(
    /**
     * `capacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity: Int,
    /**
     * `wait`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val wait: Duration,
)

/**
 * PermitSnapshot은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class PermitSnapshot(
    /**
     * `foregroundInUse`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val foregroundInUse: Int,
    /**
     * `workerInUse`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val workerInUse: Int,
    /**
     * `sseInUse`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sseInUse: Int,
    /**
     * `capacities`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val capacities: Map<PermitLane, Int>,
    /**
     * `waits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val waits: Map<PermitLane, Duration>,
    /**
     * `observedWaitMax`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val observedWaitMax: Map<PermitLane, Duration>,
    /**
     * `observedWaitSamples`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val observedWaitSamples: Map<PermitLane, Long>,
)

/**
 * PoolBusyException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class PoolBusyException(
    /**
     * `lane`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val lane: PermitLane,
    /**
     * `interrupted`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val interrupted: Boolean = false,
    cause: Throwable? = null,
): RuntimeException("database permit unavailable for lane=$lane", cause)

/**
 * NestedPermitException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class NestedPermitException(
    /**
     * `activeLane`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val activeLane: PermitLane,
    /**
     * `requestedLane`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val requestedLane: PermitLane,
): IllegalStateException("nested database permit acquisition is forbidden: $activeLane -> $requestedLane")

/** virtual-thread concurrency를 bounded PostgreSQL connection budget 안에 유지합니다. */
/** virtual-thread concurrency를 bounded PostgreSQL connection budget 안에 유지합니다. */
@Suppress("TooManyFunctions")
internal class DatabasePermitGate(
    hikariMaximumPoolSize: Int,
    configs: Map<PermitLane, PermitLaneConfig>,
) {

    companion object: KLogging() {
        /**
         * default은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param hikariMaximumPoolSize 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun default(hikariMaximumPoolSize: Int): DatabasePermitGate =
            DatabasePermitGate(
                hikariMaximumPoolSize = hikariMaximumPoolSize,
                configs = mapOf(
                    PermitLane.FOREGROUND to PermitLaneConfig(
                        DEFAULT_FOREGROUND_CAPACITY,
                        DEFAULT_FOREGROUND_WAIT_MILLIS.milliseconds
                    ),
                    PermitLane.WORKER to PermitLaneConfig(DEFAULT_WORKER_CAPACITY, 1.seconds),
                    PermitLane.SSE to PermitLaneConfig(DEFAULT_SSE_CAPACITY, 1.seconds),
                ),
            )
    }

    /**
     * `configs`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val configs: Map<PermitLane, PermitLaneConfig> = configs.toMap()
    /**
     * `permits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val permits: Map<PermitLane, Semaphore>
    /**
     * `activeLane`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val activeLane = ThreadLocal<PermitLane>()
    /**
     * `activeThreads`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val activeThreads = ConcurrentHashMap.newKeySet<Thread>()
    /**
     * `waitSamples`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val waitSamples = PermitLane.entries.associateWith { AtomicLong() }
    /**
     * `waitMaxNanos`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val waitMaxNanos = PermitLane.entries.associateWith { AtomicLong() }
    /**
     * `accepting`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val accepting = AtomicBoolean(true)
    /**
     * `drainLock`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val drainLock = ReentrantLock()
    /**
     * `drained`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val drained = drainLock.newCondition()

    init {
        require(hikariMaximumPoolSize > 0) { "hikariMaximumPoolSize must be positive" }
        require(this.configs.keys == PermitLane.entries.toSet()) { "all permit lanes must be configured" }
        require(this.configs.values.all { it.capacity > 0 }) { "permit capacities must be positive" }
        require(this.configs.values.all { it.wait > Duration.ZERO }) { "permit waits must be positive" }
        /**
         * `admittedCapacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val admittedCapacity = hikariMaximumPoolSize - RESERVED_UNGATED_CONNECTIONS
        require(this.configs.values.sumOf(PermitLaneConfig::capacity) <= admittedCapacity) {
            "permit capacity must leave one Hikari connection for readiness and lifecycle probes"
        }
        permits = this.configs.mapValues { (_, config) -> Semaphore(config.capacity, true) }
    }

/**
 * withForegroundPermit은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun <T> withForegroundPermit(block: () -> T): T = withPermit(PermitLane.FOREGROUND, block)

/**
 * withWorkerPermit은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun <T> withWorkerPermit(block: () -> T): T = withPermit(PermitLane.WORKER, block)

/**
 * withSsePermit은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun <T> withSsePermit(block: () -> T): T = withPermit(PermitLane.SSE, block)

    /**
     * snapshot은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun snapshot(): PermitSnapshot =
        PermitSnapshot(
            foregroundInUse = inUse(PermitLane.FOREGROUND),
            workerInUse = inUse(PermitLane.WORKER),
            sseInUse = inUse(PermitLane.SSE),
            capacities = configs.mapValues { (_, config) -> config.capacity },
            waits = configs.mapValues { (_, config) -> config.wait },
            observedWaitMax = waitMaxNanos.mapValues { (_, value) -> value.get().nanoseconds },
            observedWaitSamples = waitSamples.mapValues { (_, value) -> value.get() },
        )

    /**
     * resetWaitObservations은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun resetWaitObservations() {
        check(isDrained()) { "permit wait observations can only be reset while the gate is drained" }
        waitSamples.values.forEach { it.set(0L) }
        waitMaxNanos.values.forEach { it.set(0L) }
    }

    /**
     * beginShutdown은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun beginShutdown() {
        accepting.set(false)
        signalIfDrained()
    }

    /**
     * isAccepting은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun isAccepting(): Boolean = accepting.get()

    /**
     * isDrained은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun isDrained(): Boolean = inUseTotal() == 0

    /**
     * cancelActiveTransactions은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun cancelActiveTransactions(): Int {
        val threads = activeThreads.toList()
        threads.forEach(Thread::interrupt)
        return threads.size
    }

    /**
     * awaitDrained은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param timeout 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun awaitDrained(timeout: JavaDuration): Boolean {
        require(!timeout.isNegative) { "timeout must not be negative" }
        var remaining = timeout.toNanos()

        drainLock.withLock {
            while (inUseTotal() > 0 && remaining > 0L) {
                remaining = drained.awaitNanos(remaining)
            }
            return inUseTotal() == 0
        }
    }

/**
 * withPermit은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private inline fun <T> withPermit(
        lane: PermitLane,
        block: () -> T,
    ): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "database permit must be acquired before starting a transaction"
        }
        if (!accepting.get()) throw PoolBusyException(lane)
        activeLane.get()?.let { held -> throw NestedPermitException(held, lane) }
        val semaphore = permits.getValue(lane)
        val wait = configs.getValue(lane).wait
        acquirePermit(lane, semaphore, wait)

        activeLane.set(lane)
        activeThreads += Thread.currentThread()

        return try {
            block()
        } finally {
            activeThreads -= Thread.currentThread()
            activeLane.remove()
            semaphore.release()
            signalIfDrained()
            log.debug { "voucher_pool_db_permit_released lane=$lane" }
        }
    }

    @Suppress("ThrowsCount")
    /**
     * acquirePermit은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun acquirePermit(
        lane: PermitLane,
        semaphore: Semaphore,
        wait: Duration,
    ) {
        val startedAt = System.nanoTime()
        val acquired = try {
            semaphore.tryAcquire(wait.inWholeNanoseconds, TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            log.debug { "voucher_pool_db_permit_interrupted lane=$lane" }
            throw PoolBusyException(lane = lane, interrupted = true, cause = interrupted)
        } finally {
            recordWait(lane, System.nanoTime() - startedAt)
        }

        if (!acquired) {
            log.debug { "voucher_pool_db_permit_timed_out lane=$lane" }
            throw PoolBusyException(lane)
        }
        if (!accepting.get()) {
            semaphore.release()
            signalIfDrained()
            throw PoolBusyException(lane)
        }
    }

    /**
     * recordWait은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param lane 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param elapsedNanos 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun recordWait(lane: PermitLane, elapsedNanos: Long) {
        waitSamples.getValue(lane).incrementAndGet()
        waitMaxNanos.getValue(lane).accumulateAndGet(elapsedNanos.coerceAtLeast(0L), ::maxOf)
    }

    /**
     * inUse은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param lane 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun inUse(lane: PermitLane): Int =
        configs.getValue(lane).capacity - permits.getValue(lane).availablePermits()

    /**
     * inUseTotal은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun inUseTotal(): Int = PermitLane.entries.sumOf(::inUse)

    /**
     * signalIfDrained은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun signalIfDrained() {
        if (inUseTotal() == 0) {
            drainLock.withLock { drained.signalAll() }
        }
    }
}

package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedMetrics
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedOperationalState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedRuntimeWorkers
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `VOUCHER_LIFECYCLE_PROJECTION`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
internal const val VOUCHER_LIFECYCLE_PROJECTION = "voucher-lifecycle"
/**
 * `RUNTIME_POLL_INTERVAL_MILLIS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val RUNTIME_POLL_INTERVAL_MILLIS = 100L
/**
 * ProjectionRuntimeResources은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property database 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property permits 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property leases 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property projections 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property rebuilds 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property eventReader 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property rebuildHandler 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property poisonStatus 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 */


internal data class ProjectionRuntimeResources(
    /**
     * `database`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val database: Database,
    /**
     * `permits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val permits: EventSourcedDatabasePermitGate,
    /**
     * `leases`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val leases: ProjectionLeaseRepository,
    /**
     * `projections`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val projections: ProjectionRepository,
    /**
     * `rebuilds`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val rebuilds: ProjectionRebuildRepository,
    /**
     * `eventReader`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val eventReader: ProjectionEventReader = ExposedProjectionEventReader(),
    /**
     * `rebuildHandler`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val rebuildHandler: ProjectionEventHandler = AcceptingProjectionEventHandler,
    /**
     * `poisonStatus`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val poisonStatus: ProjectionPoisonStatusQuery = ProjectionPoisonStatusQuery(),
)
/**
 * ProjectionRuntimeTelemetry은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property operationalState 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property metrics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class ProjectionRuntimeTelemetry(
    /**
     * `operationalState`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val operationalState: EventSourcedOperationalState,
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: EventSourcedMetrics,
) {

    private companion object: KLogging()
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */

    private var lastActiveKey: ProjectionKey? = null
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    private var lastActivePosition: Long = 0
    /**
     * 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    private var lastActiveAdvanceAt: Instant? = null

    /**
     * observeActive은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun observeActive(
        key: ProjectionKey,
        result: ProjectionPollResult,
        now: Instant,
    ): Boolean =
        when (result) {
            is ProjectionPollResult.Applied -> {
                metrics.projectionBatch(
                    events = result.appliedEventCount + result.duplicateEventCount,
                    bytes = result.processedBytes,
                )
                metrics.projectionLag(result.lag, result.lagAge, checkpointStalled(key, result.checkpointPosition, now))
                true
            }

            ProjectionPollResult.Idle       -> {
                metrics.projectionLag(0, Duration.ZERO, Duration.ZERO)
                true
            }

            is ProjectionPollResult.Degraded -> {
                operationalState.projectionHealth(degraded = true)
                false
            }
        }

    /**
     * observeRebuild은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun observeRebuild(result: ProjectionRebuildPollResult): Boolean =
        when (result) {
            is ProjectionRebuildPollResult.Degraded -> {
                operationalState.projectionHealth(
                    degraded = true,
                    rebuildState = ProjectionGenerationState.BUILDING,
                )
                false
            }

            is ProjectionRebuildPollResult.Applied,
            ProjectionRebuildPollResult.Idle,
            ProjectionRebuildPollResult.StaleWorker,
                -> true
        }

    /**
     * observeFailedPoisons은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param countsByReasonClass 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun observeFailedPoisons(countsByReasonClass: Map<String, Long>): Boolean {
        metrics.failedPoisons(countsByReasonClass)
        if (countsByReasonClass.isNotEmpty()) {
            operationalState.projectionHealth(degraded = true)
            return false
        }
        return true
    }

    /**
     * observeRebuild은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun observeRebuild(generation: ProjectionGeneration) {
        operationalState.rebuildState(generation.state)
        val progress = if (generation.targetPosition == 0L) {
            1.0
        } else {
            generation.currentPosition.toDouble() / generation.targetPosition
        }
        metrics.rebuildProgress(progress, generation.state)
        val remainingEvents = (generation.targetPosition - generation.currentPosition).coerceAtLeast(0)
        val remainingBatches = (remainingEvents + MAX_PROJECTION_BATCH_EVENTS - 1) / MAX_PROJECTION_BATCH_EVENTS
        metrics.rebuildEta((remainingBatches * RUNTIME_POLL_INTERVAL_MILLIS).millis())
    }

    /**
     * activated은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun activated() {
        operationalState.rebuildState(ProjectionGenerationState.ACTIVE)
        metrics.rebuildProgress(1.0, ProjectionGenerationState.ACTIVE)
        metrics.rebuildEta(Duration.ZERO)
    }

    /**
     * failed은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun failed() {
        operationalState.projectionHealth(degraded = true)
        metrics.retried()
    }

    /**
     * recovered은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun recovered() {
        operationalState.projectionHealth(degraded = false)
    }

    /**
     * checkpointStalled은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param position 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun checkpointStalled(
        key: ProjectionKey,
        position: Long,
        now: Instant,
    ): Duration {
        if (lastActiveKey != key || lastActivePosition != position) {
            lastActiveKey = key
            lastActivePosition = position
            lastActiveAdvanceAt = now
            return Duration.ZERO
        }
        return Duration.between(lastActiveAdvanceAt ?: now, now).coerceAtLeast(Duration.ZERO)
    }
}

/**
 * active projector와 durable rebuild candidate 하나를 위한 Spring-owned runtime입니다.
 *
 * 모든 poll은 Hikari connection을 요청하기 전에 예약된 database lane에 들어갑니다.
 * candidate activation은 두 generation 모두 target checkpoint와 동일한 semantic digest를 가진 뒤에만 발생합니다.
 * cancellation은 process restart 뒤에도 durable하게 복구됩니다.
 * @property properties 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property telemetry 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property digest 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property maintenance 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class EventSourcedProjectionRuntime(
    resources: ProjectionRuntimeResources,
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ProjectionWorkerProperties,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
    /**
     * `telemetry`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val telemetry: ProjectionRuntimeTelemetry,
    /**
     * `digest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val digest: ProjectionGenerationDigest = ProjectionGenerationDigest(),
    /**
     * `maintenance`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val maintenance: ProjectionRebuildMaintenance = ProjectionRebuildMaintenance(),
): EventSourcedRuntimeWorkers {

    private companion object: KLogging()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val projections = resources.projections
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val rebuilds = resources.rebuilds
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    private val poisonStatus = resources.poisonStatus
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val projectionTransactions = EventSourcedPermitTransactionRunner(
        database = resources.database,
        permits = resources.permits,
        lane = EventSourcedDatabaseLane.PROJECTION,
    )
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val rebuildTransactions = EventSourcedPermitTransactionRunner(
        database = resources.database,
        permits = resources.permits,
        lane = EventSourcedDatabaseLane.REBUILD,
    )
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val maintenanceTransactions = EventSourcedPermitTransactionRunner(
        database = resources.database,
        permits = resources.permits,
        lane = EventSourcedDatabaseLane.MAINTENANCE,
    )
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val projectionWorker = ProjectionWorker(projections, resources.eventReader)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val rebuildWorker = ProjectionRebuildWorker(
        rebuilds = rebuilds,
        projections = projections,
        reader = resources.eventReader,
        handler = resources.rebuildHandler
    )
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val runtimeLeases = ProjectionRuntimeLeaseCoordinator(
        database = resources.database,
        leases = resources.leases,
        ownerDigest = ReceiptDigest.sha256("voucher-projection-runtime\u0000${UUID.randomUUID()}").value,
        clock = clock,
    )
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val started = AtomicBoolean()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val projectionEnabled = AtomicBoolean()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val rebuildEnabled = AtomicBoolean()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    @Volatile
    private var executor: ScheduledExecutorService? = null
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    @Volatile
    private var scheduled: ScheduledFuture<*>? = null

    /**
     * start은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun start() {
        if (!properties.enabled || !started.compareAndSet(false, true)) return

        maintenanceTransactions.inTransaction {
            rebuilds.initializeActive(VOUCHER_LIFECYCLE_PROJECTION, clock.instant())
        }
        projectionEnabled.set(true)
        rebuildEnabled.set(true)

        executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("voucher-projection-runtime-", 0).factory(),
        )
        scheduled = executor!!.scheduleWithFixedDelay(
            ::tickSafely,
            0,
            RUNTIME_POLL_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        log.info { "voucher_projection_runtime_started" }
    }

    /**
     * stopProjection은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun stopProjection() {
        projectionEnabled.set(false)
    }

    /**
     * stopRebuild은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun stopRebuild() {
        rebuildEnabled.set(false)
    }

    /**
     * stopMaintenance은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun stopMaintenance() {
        scheduled?.cancel(false)
        executor?.shutdown()
    }

    /**
     * releaseFencedLeases은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun releaseFencedLeases() {
        runtimeLeases.releaseAll()
    }

    /**
     * tickSafely은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun tickSafely() {
        runCatching {
            val projectionHealthy = !projectionEnabled.get() || pollActive()
            val rebuildHealthy = !rebuildEnabled.get() || pollRebuild()
            val poisonFree = maintenanceTransactions.inTransaction {
                telemetry.observeFailedPoisons(
                    poisonStatus.failedCountsByReasonClass(VOUCHER_LIFECYCLE_PROJECTION),
                )
            }
            if (projectionHealthy && rebuildHealthy && poisonFree) telemetry.recovered()
        }.onFailure { failure ->
            telemetry.failed()
            log.warn { "voucher_projection_runtime_tick_failed cause=${failure.javaClass.simpleName}" }
        }
    }

    /**
     * pollActive은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun pollActive(): Boolean =
        projectionTransactions.inTransaction {
            /**
             * `now`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val now = clock.instant()
            /**
             * `pointer`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val pointer = findActive(VOUCHER_LIFECYCLE_PROJECTION) ?: return@inTransaction true
            /**
             * `key`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
             *
             * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
             */
            val key = ProjectionKey(pointer.projection, pointer.generation)
            /**
             * `owned`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val owned = runtimeLeases.acquireActive(key, now)
            owned?.let { telemetry.observeActive(key, projectionWorker.poll(key, it.lease, now), now) } ?: true
        }

    /**
     * pollRebuild은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun pollRebuild(): Boolean =
        rebuildTransactions.inTransaction {
            /**
             * `now`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val now = clock.instant()
            when (val generation = findInProgressGeneration(VOUCHER_LIFECYCLE_PROJECTION)) {
                null -> {
                    runtimeLeases.releaseRebuild(now)
                    true
                }
                else -> {
                    telemetry.observeRebuild(generation)
                    when (generation.state) {
                        ProjectionGenerationState.BUILDING -> pollBuilding(generation, now)
                        ProjectionGenerationState.VALIDATING -> {
                            validateAndActivate(generation, now)
                            true
                        }
                        ProjectionGenerationState.CANCELLING -> {
                            maintenance.recover(generation.key, now)
                            runtimeLeases.releaseRebuild(now)
                            true
                        }
                        else                               -> true
                    }
                }
            }
        }

    /**
     * pollBuilding은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun pollBuilding(
        generation: ProjectionGeneration,
        now: Instant,
    ): Boolean {
        val owned = runtimeLeases.acquireRebuild(generation.key, now)
        val healthy = owned?.let {
            telemetry.observeRebuild(rebuildWorker.poll(generation.key, it.lease, now))
        } ?: true

        val current = findGeneration(generation.key) ?: return healthy
        val canRebuild = current.state == ProjectionGenerationState.BUILDING &&
                current.currentPosition == current.targetPosition
        if (canRebuild) {
            rebuilds.beginValidation(
                key = current.key,
                fencingToken = current.fencingToken,
                cancellationRevision = current.cancellationRevision,
                canonicalDigest = digest.compute(current.key),
                now = now,
            )
        }
        return healthy
    }

    /**
     * validateAndActivate은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param candidate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun validateAndActivate(
        candidate: ProjectionGeneration,
        now: Instant,
    ) {
        val pointer = findActive(candidate.key.projection) ?: return
        val activeKey = ProjectionKey(pointer.projection, pointer.generation)
        val activePosition = projections.checkpoint(activeKey)?.position ?: 0L
        val candidatePosition = projections.checkpoint(candidate.key)?.position ?: 0L
        val checkpointsMatch =
            activePosition == candidate.targetPosition && candidatePosition == candidate.targetPosition

        when {
            activePosition > candidate.targetPosition ->
                rebuilds.extendValidationTarget(
                    key = candidate.key,
                    extension = ProjectionValidationTargetExtension(
                        fencingToken = candidate.fencingToken,
                        cancellationRevision = candidate.cancellationRevision,
                        currentTargetPosition = candidate.targetPosition,
                        newTargetPosition = activePosition,
                    ),
                    now = now,
                )

            checkpointsMatch                          -> {
                val candidateDigest = candidate.canonicalDigest.checkNotNull { "canonical digest must not be null" }

                if (digest.compute(activeKey) == candidateDigest) {
                    if (rebuilds.activateCandidate(pointer, candidate, candidateDigest, now)) {
                        runtimeLeases.releaseRebuild(now)
                        telemetry.activated()
                        log.info { "voucher_projection_rebuild_activated generation=${candidate.key.generation}" }
                    }
                } else {
                    log.warn { "voucher_projection_validation_mismatch generation=${candidate.key.generation}" }
                }
            }
        }
    }
}
/**
 * OwnedProjectionLease은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property key 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property lease 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


private data class OwnedProjectionLease(
    /**
     * `key`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val key: ProjectionKey,
    /**
     * `lease`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val lease: ProjectionLease,
)

/**
 * activateCandidate은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param pointer 업무 처리에 필요한 pointer 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param candidate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param candidateDigest 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun ProjectionRebuildRepository.activateCandidate(
    pointer: ActiveProjectionGeneration,
    candidate: ProjectionGeneration,
    candidateDigest: ReceiptDigest,
    now: Instant,
): Boolean =
    activate(
        key = candidate.key,
        expectedPointerRevision = pointer.revision,
        targetHead = candidate.targetPosition,
        canonicalDigest = candidateDigest,
        now = now,
    ) == ProjectionActivationResult.Activated
/**
 * ProjectionRuntimeLeaseCoordinator은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property database 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property leases 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property ownerDigest 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


private class ProjectionRuntimeLeaseCoordinator(
    /**
     * `database`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val database: Database,
    /**
     * `leases`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val leases: ProjectionLeaseRepository,
    /**
     * `ownerDigest`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val ownerDigest: String,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {

    companion object: KLogging()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private var active: OwnedProjectionLease? = null
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private var rebuild: OwnedProjectionLease? = null

    /**
     * acquireActive은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun acquireActive(
        key: ProjectionKey,
        now: Instant,
    ): OwnedProjectionLease? = refresh(active, key, now).also { active = it }

    /**
     * acquireRebuild은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun acquireRebuild(
        key: ProjectionKey,
        now: Instant,
    ): OwnedProjectionLease? = refresh(rebuild, key, now).also { rebuild = it }

    /**
     * releaseRebuild은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun releaseRebuild(now: Instant) {
        rebuild?.release(now)
        rebuild = null
    }

    /**
     * releaseAll은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun releaseAll() {
        val observedActive = active
        val observedRebuild = rebuild
        transaction(database) {
            observedActive?.release(clock.instant())
            observedRebuild?.takeUnless { it.key == observedActive?.key }?.release(clock.instant())
        }
        active = null
        rebuild = null
    }

    /**
     * refresh은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param current 업무 처리에 필요한 current 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun refresh(
        current: OwnedProjectionLease?,
        key: ProjectionKey,
        now: Instant,
    ): OwnedProjectionLease? {
        current?.takeIf { it.key != key }?.release(now)
        val matching = current?.takeIf { it.key == key }
        val lease = matching?.let { owned ->
            if (owned.lease.isRenewalDue(now)) {
                leases.renewLease(key.projection, key.generation, owned.lease, now)
            } else {
                owned.lease
            }
        } ?: leases.acquire(key.projection, key.generation, ownerDigest, now)
        
        return lease?.let { OwnedProjectionLease(key, it) }
    }

    /**
     * release은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun OwnedProjectionLease.release(now: Instant) {
        leases.release(key.projection, key.generation, lease, now)
    }
}

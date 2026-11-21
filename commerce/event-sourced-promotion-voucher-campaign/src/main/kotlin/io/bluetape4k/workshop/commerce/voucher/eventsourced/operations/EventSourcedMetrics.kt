package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedCommandMetrics
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreAppendMetrics
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * bounded operational telemetry입니다. tenant, campaign, generation, stream, event, principal
 * identifier는 Micrometer tag로 절대 사용하지 않습니다.
 * @property registry 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Suppress("TooManyFunctions") // Metrics facade keeps one bounded vocabulary for all runtime lanes.
internal class EventSourcedMetrics(
    /**
     * `registry`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val registry: MeterRegistry,
): EventSourcedDatabasePermitMetrics,
   EventSourcedCommandMetrics,
   EventStoreAppendMetrics {

    private companion object: KLogging() {
        /**
         * `PROGRESS_SCALE`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val PROGRESS_SCALE = 1_000_000L
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private const val MIN_HTTP_STATUS = 100
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private const val MAX_HTTP_STATUS = 599
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private const val HTTP_STATUS_CLASS_DIVISOR = 100
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val KNOWN_REASON_CLASSES = setOf("HANDLER_REJECTED", "DATABASE_UNAVAILABLE", "UNKNOWN")

        /**
         * boundedReasonClass은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        private fun String.boundedReasonClass(): String =
            takeIf(KNOWN_REASON_CLASSES::contains) ?: "UNKNOWN"
    }
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */

    private val lagEvents = AtomicLong()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val lagAgeSeconds = AtomicLong()
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    private val checkpointStalledSeconds = AtomicLong()
    /**
     * `rebuildProgressMicros`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val rebuildProgressMicros = AtomicLong()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val maintenanceQueueDepth = AtomicLong()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val rebuildEtaSeconds = AtomicLong()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val failedPoisonGauges = ConcurrentHashMap<String, AtomicLong>()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val rebuildTimers = ConcurrentHashMap<OperatorAuditOutcome, Timer>()
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    private val rebuildStates = ProjectionGenerationState.entries.associateWith { AtomicLong() }
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val retries = Counter.builder("voucher_projection_retry_total").register(registry)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val bulkheadRejections = Counter.builder("voucher_db_bulkhead_rejected_total").register(registry)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val appendCommitted = Counter.builder("voucher_event_append_committed").register(registry)
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val appendDuration = Timer.builder("voucher_event_append_duration").register(registry)
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val streamHeadWait = Timer.builder("voucher_event_stream_head_wait").register(registry)
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val appendFenceWait = Timer.builder("voucher_event_append_fence_wait").register(registry)
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val replayEvents = DistributionSummary.builder("voucher_replay_events").register(registry)
    /**
     * `replayBytes`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val replayBytes = DistributionSummary.builder("voucher_replay_bytes").register(registry)
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val projectionBatchEvents =
        DistributionSummary.builder("voucher_projection_batch_events").register(registry)
    /**
     * `projectionBatchBytes`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val projectionBatchBytes = DistributionSummary.builder("voucher_projection_batch_bytes").register(registry)
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val maintenanceQueueWait = Timer.builder("voucher_maintenance_queue_wait").register(registry)
    /**
     * 프로모션 바우처 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */
    private val terminalTimers = ConcurrentHashMap<String, Timer>()

    init {
        Gauge.builder("voucher_projection_lag_events", lagEvents) { it.get().toDouble() }
            .register(registry)

        Gauge.builder("voucher_projection_lag_age_seconds", lagAgeSeconds) { it.get().toDouble() }
            .baseUnit("seconds")
            .register(registry)

        Gauge.builder("voucher_projection_checkpoint_stalled_seconds", checkpointStalledSeconds) { it.get().toDouble() }
            .baseUnit("seconds")
            .register(registry)

        Gauge.builder("voucher_rebuild_progress_ratio", rebuildProgressMicros) { it.get() / PROGRESS_SCALE.toDouble() }
            .register(registry)

        Gauge.builder("voucher_maintenance_queue_depth", maintenanceQueueDepth) { it.get().toDouble() }
            .register(registry)

        Gauge.builder("voucher_rebuild_eta_seconds", rebuildEtaSeconds) { it.get().toDouble() }
            .baseUnit("seconds")
            .register(registry)

        rebuildStates.forEach { (state, value) ->
            Gauge.builder("voucher_rebuild_state", value) { it.get().toDouble() }
                .tag("state", state.name)
                .register(registry)
        }

        KNOWN_REASON_CLASSES.forEach { reasonClass ->
            /**
             * `value`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val value = AtomicLong()
            failedPoisonGauges[reasonClass] = value
            Gauge.builder("voucher_projection_poison_events", value) { it.get().toDouble() }
                .tag("reasonClass", reasonClass)
                .register(registry)
        }
    }

    /**
     * bind은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param gate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun bind(gate: EventSourcedDatabasePermitGate) {
        Gauge.builder("voucher_db_bulkhead_active", gate) {
            EventSourcedDatabaseLane.entries.sumOf { lane -> it.snapshot(lane).active }.toDouble()
        }.register(registry)

        Gauge.builder("voucher_db_bulkhead_queued", gate) {
            EventSourcedDatabaseLane.entries.sumOf { lane -> it.snapshot(lane).queued }.toDouble()
        }.register(registry)

        Gauge.builder("voucher_db_permit_utilization", gate) {
            val snapshots = EventSourcedDatabaseLane.entries.map(it::snapshot)
            val active = snapshots.sumOf(EventSourcedDatabasePermitSnapshot::active)
            val capacity = snapshots.sumOf { snapshot -> snapshot.active + snapshot.available }
            if (capacity == 0) 0.0 else active.toDouble() / capacity
        }.register(registry)
    }

    /**
     * bind은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param dataSource 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun bind(dataSource: HikariDataSource) {
        Gauge.builder("voucher_hikari_active", dataSource) {
            it.hikariPoolMXBean.activeConnections.toDouble()
        }.register(registry)

        Gauge.builder("voucher_hikari_waiting", dataSource) {
            it.hikariPoolMXBean.threadsAwaitingConnection.toDouble()
        }.register(registry)
    }

    /**
     * projectionLag은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param events 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param age 업무 처리에 필요한 age 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param checkpointStalled 업무 처리에 필요한 checkpointStalled 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun projectionLag(
        events: Long,
        age: Duration,
        checkpointStalled: Duration,
    ) {
        lagEvents.set(events.coerceAtLeast(0))
        lagAgeSeconds.set(age.seconds.coerceAtLeast(0))
        checkpointStalledSeconds.set(checkpointStalled.seconds.coerceAtLeast(0))
    }

    /**
     * failedPoisons은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param countsByReasonClass 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun failedPoisons(countsByReasonClass: Map<String, Long>) {
        val boundedCounts = countsByReasonClass.entries
            .groupingBy { (reasonClass) -> reasonClass.boundedReasonClass() }
            .fold(0L) { count, (_, value) ->
                count + value.requireZeroOrPositiveNumber("failed poison count")
            }

        failedPoisonGauges.values.forEach { it.set(0) }

        boundedCounts.forEach { (reasonClass, count) ->
            failedPoisonGauges.getValue(reasonClass).set(count)
        }
    }

    /**
     * retried은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun retried() {
        retries.increment()
    }

    /**
     * rebuildProgress은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param progress 업무 처리에 필요한 progress 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun rebuildProgress(
        progress: Double,
        state: ProjectionGenerationState,
    ) {
        rebuildProgressMicros.set((progress.coerceIn(0.0, 1.0) * PROGRESS_SCALE).toLong())
        rebuildStates.values.forEach { it.set(0) }
        rebuildStates.getValue(state).set(1)
    }

    /**
     * rebuildCompleted은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게
     * 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun rebuildCompleted(
        duration: Duration,
        outcome: OperatorAuditOutcome,
    ) {
        rebuildTimers
            .computeIfAbsent(outcome) {
                Timer.builder("voucher_rebuild_duration_seconds")
                    .tag("outcome", it.name)
                    .register(registry)
            }
            .record(duration)
    }

    /**
     * commandTerminal은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    override fun commandTerminal(
        status: Int,
        duration: Duration,
    ) {
        val validStatus = status.requireInRange(MIN_HTTP_STATUS, MAX_HTTP_STATUS, "status")
        val statusClass = "${validStatus / HTTP_STATUS_CLASS_DIVISOR}xx"
        terminalTimers
            .computeIfAbsent(statusClass) {
                Timer.builder("voucher_command_terminal")
                    .tag("status", it)
                    .register(registry)
            }
            .record(duration)
    }

    /**
     * appendCommitted은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송
     * 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventCount 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    override fun appendCommitted(
        eventCount: Int,
        duration: Duration,
    ) {
        appendCommitted.increment(eventCount.requirePositiveNumber("eventCount").toDouble())
        appendDuration.record(duration)
    }

    /**
     * streamHeadWait은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    override fun streamHeadWait(duration: Duration) {
        streamHeadWait.record(duration)
    }

    /**
     * appendFenceWait은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송
     * 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    override fun appendFenceWait(duration: Duration) {
        appendFenceWait.record(duration)
    }

    /**
     * replay은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param events 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun replay(
        events: Int,
        bytes: Int,
    ) {
        replayEvents.record(events.requireZeroOrPositiveNumber("events").toDouble())
        replayBytes.record(bytes.requireZeroOrPositiveNumber("bytes").toDouble())
    }

    /**
     * projectionBatch은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param events 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun projectionBatch(
        events: Int,
        bytes: Int,
    ) {
        projectionBatchEvents.record(events.requireZeroOrPositiveNumber("events").toDouble())
        projectionBatchBytes.record(bytes.requireZeroOrPositiveNumber("bytes").toDouble())
    }

    /**
     * maintenanceQueue은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param wait 업무 처리에 필요한 wait 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param depth 업무 처리에 필요한 depth 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun maintenanceQueue(
        wait: Duration,
        depth: Int,
    ) {
        maintenanceQueueWait.record(wait)
        maintenanceQueueDepth.set(depth.requireZeroOrPositiveNumber("depth").toLong())
    }

    /**
     * rebuildEta은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun rebuildEta(duration: Duration) {
        rebuildEtaSeconds.set(duration.seconds.requireZeroOrPositiveNumber("duration.seconds"))
    }

    /**
     * rejected은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun rejected(lane: EventSourcedDatabaseLane) {
        bulkheadRejections.increment()
    }

}

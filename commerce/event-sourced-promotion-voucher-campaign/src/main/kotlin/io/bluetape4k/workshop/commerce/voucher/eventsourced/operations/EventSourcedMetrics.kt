package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import com.zaxxer.hikari.HikariDataSource
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
 */
@Suppress("TooManyFunctions") // Metrics facade keeps one bounded vocabulary for all runtime lanes.
internal class EventSourcedMetrics(
    private val registry: MeterRegistry,
) : EventSourcedDatabasePermitMetrics,
    EventSourcedCommandMetrics,
    EventStoreAppendMetrics {
    private val lagEvents = AtomicLong()
    private val lagAgeSeconds = AtomicLong()
    private val checkpointStalledSeconds = AtomicLong()
    private val rebuildProgressMicros = AtomicLong()
    private val maintenanceQueueDepth = AtomicLong()
    private val rebuildEtaSeconds = AtomicLong()
    private val failedPoisonGauges = ConcurrentHashMap<String, AtomicLong>()
    private val rebuildTimers = ConcurrentHashMap<OperatorAuditOutcome, Timer>()
    private val rebuildStates = ProjectionGenerationState.entries.associateWith { AtomicLong() }
    private val retries = Counter.builder("voucher_projection_retry_total").register(registry)
    private val bulkheadRejections = Counter.builder("voucher_db_bulkhead_rejected_total").register(registry)
    private val appendCommitted = Counter.builder("voucher_event_append_committed").register(registry)
    private val appendDuration = Timer.builder("voucher_event_append_duration").register(registry)
    private val streamHeadWait = Timer.builder("voucher_event_stream_head_wait").register(registry)
    private val appendFenceWait = Timer.builder("voucher_event_append_fence_wait").register(registry)
    private val replayEvents = DistributionSummary.builder("voucher_replay_events").register(registry)
    private val replayBytes = DistributionSummary.builder("voucher_replay_bytes").register(registry)
    private val projectionBatchEvents =
        DistributionSummary.builder("voucher_projection_batch_events").register(registry)
    private val projectionBatchBytes = DistributionSummary.builder("voucher_projection_batch_bytes").register(registry)
    private val maintenanceQueueWait = Timer.builder("voucher_maintenance_queue_wait").register(registry)
    private val terminalTimers = ConcurrentHashMap<String, Timer>()

    init {
        Gauge.builder("voucher_projection_lag_events", lagEvents) { it.get().toDouble() }.register(registry)
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
            val value = AtomicLong()
            failedPoisonGauges[reasonClass] = value
            Gauge.builder("voucher_projection_poison_events", value) { it.get().toDouble() }
                .tag("reasonClass", reasonClass)
                .register(registry)
        }
    }

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

    fun bind(dataSource: HikariDataSource) {
        Gauge.builder("voucher_hikari_active", dataSource) {
            it.hikariPoolMXBean.activeConnections.toDouble()
        }.register(registry)
        Gauge.builder("voucher_hikari_waiting", dataSource) {
            it.hikariPoolMXBean.threadsAwaitingConnection.toDouble()
        }.register(registry)
    }

    fun projectionLag(
        events: Long,
        age: Duration,
        checkpointStalled: Duration,
    ) {
        lagEvents.set(events.coerceAtLeast(0))
        lagAgeSeconds.set(age.seconds.coerceAtLeast(0))
        checkpointStalledSeconds.set(checkpointStalled.seconds.coerceAtLeast(0))
    }

    fun failedPoisons(countsByReasonClass: Map<String, Long>) {
        val boundedCounts =
            countsByReasonClass.entries
                .groupingBy { (reasonClass) -> reasonClass.boundedReasonClass() }
                .fold(0L) { count, (_, value) ->
                    count + value.requireZeroOrPositiveNumber("failed poison count")
                }
        failedPoisonGauges.values.forEach { it.set(0) }
        boundedCounts.forEach { (reasonClass, count) ->
            failedPoisonGauges.getValue(reasonClass).set(count)
        }
    }

    fun retried() {
        retries.increment()
    }

    fun rebuildProgress(
        progress: Double,
        state: ProjectionGenerationState,
    ) {
        rebuildProgressMicros.set((progress.coerceIn(0.0, 1.0) * PROGRESS_SCALE).toLong())
        rebuildStates.values.forEach { it.set(0) }
        rebuildStates.getValue(state).set(1)
    }

    fun rebuildCompleted(
        duration: Duration,
        outcome: OperatorAuditOutcome,
    ) {
        rebuildTimers.computeIfAbsent(outcome) {
            Timer.builder("voucher_rebuild_duration_seconds")
                .tag("outcome", it.name)
                .register(registry)
        }.record(duration)
    }

    override fun commandTerminal(
        status: Int,
        duration: Duration,
    ) {
        val validStatus = status.requireInRange(MIN_HTTP_STATUS, MAX_HTTP_STATUS, "status")
        val statusClass = "${validStatus / HTTP_STATUS_CLASS_DIVISOR}xx"
        terminalTimers.computeIfAbsent(statusClass) {
            Timer.builder("voucher_command_terminal")
                .tag("status", it)
                .register(registry)
        }.record(duration)
    }

    override fun appendCommitted(
        eventCount: Int,
        duration: Duration,
    ) {
        appendCommitted.increment(eventCount.requirePositiveNumber("eventCount").toDouble())
        appendDuration.record(duration)
    }

    override fun streamHeadWait(duration: Duration) {
        streamHeadWait.record(duration)
    }

    override fun appendFenceWait(duration: Duration) {
        appendFenceWait.record(duration)
    }

    fun replay(
        events: Int,
        bytes: Int,
    ) {
        replayEvents.record(events.requireZeroOrPositiveNumber("events").toDouble())
        replayBytes.record(bytes.requireZeroOrPositiveNumber("bytes").toDouble())
    }

    fun projectionBatch(
        events: Int,
        bytes: Int,
    ) {
        projectionBatchEvents.record(events.requireZeroOrPositiveNumber("events").toDouble())
        projectionBatchBytes.record(bytes.requireZeroOrPositiveNumber("bytes").toDouble())
    }

    fun maintenanceQueue(
        wait: Duration,
        depth: Int,
    ) {
        maintenanceQueueWait.record(wait)
        maintenanceQueueDepth.set(depth.requireZeroOrPositiveNumber("depth").toLong())
    }

    fun rebuildEta(duration: Duration) {
        rebuildEtaSeconds.set(duration.seconds.requireZeroOrPositiveNumber("duration.seconds"))
    }

    override fun rejected(lane: EventSourcedDatabaseLane) {
        bulkheadRejections.increment()
    }

    private companion object {
        private const val PROGRESS_SCALE = 1_000_000L
        private const val MIN_HTTP_STATUS = 100
        private const val MAX_HTTP_STATUS = 599
        private const val HTTP_STATUS_CLASS_DIVISOR = 100
        private val KNOWN_REASON_CLASSES = setOf("HANDLER_REJECTED", "DATABASE_UNAVAILABLE", "UNKNOWN")

        private fun String.boundedReasonClass(): String = takeIf(KNOWN_REASON_CLASSES::contains) ?: "UNKNOWN"
    }
}

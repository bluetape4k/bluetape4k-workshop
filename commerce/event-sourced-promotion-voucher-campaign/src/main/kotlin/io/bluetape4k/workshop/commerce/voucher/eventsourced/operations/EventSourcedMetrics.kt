package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded operational telemetry. No tenant, campaign, generation, stream, event, or principal
 * identifier is ever used as a Micrometer tag.
 */
internal class EventSourcedMetrics(
    private val registry: MeterRegistry,
) : EventSourcedDatabasePermitMetrics {
    private val lagEvents = AtomicLong()
    private val lagAgeSeconds = AtomicLong()
    private val checkpointStalledSeconds = AtomicLong()
    private val rebuildProgressMicros = AtomicLong()
    private val poisonCounters = ConcurrentHashMap<String, Counter>()
    private val rebuildTimers = ConcurrentHashMap<OperatorAuditOutcome, Timer>()
    private val rebuildStates = ProjectionGenerationState.entries.associateWith { AtomicLong() }
    private val retries = Counter.builder("voucher_projection_retry_total").register(registry)
    private val bulkheadRejections = Counter.builder("voucher_db_bulkhead_rejected_total").register(registry)

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
        rebuildStates.forEach { (state, value) ->
            Gauge.builder("voucher_rebuild_state", value) { it.get().toDouble() }
                .tag("state", state.name)
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

    fun poisoned(reasonClass: String) {
        poisonCounters.computeIfAbsent(reasonClass.boundedReasonClass()) {
            Counter.builder("voucher_projection_poison_events")
                .tag("reasonClass", it)
                .register(registry)
        }.increment()
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

    override fun rejected(lane: EventSourcedDatabaseLane) {
        bulkheadRejections.increment()
    }

    private companion object {
        private const val PROGRESS_SCALE = 1_000_000L
        private val KNOWN_REASON_CLASSES = setOf("HANDLER_REJECTED", "DATABASE_UNAVAILABLE", "UNKNOWN")

        private fun String.boundedReasonClass(): String = takeIf(KNOWN_REASON_CLASSES::contains) ?: "UNKNOWN"
    }
}

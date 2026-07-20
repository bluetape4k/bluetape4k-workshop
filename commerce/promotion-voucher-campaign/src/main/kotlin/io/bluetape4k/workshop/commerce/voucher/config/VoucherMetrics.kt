package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitMetrics
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal enum class VoucherLeaderState(val metricValue: Int) {
    UNKNOWN(0),
    SKIPPED(1),
    ELECTED(2),
    DEGRADED(3),
}

/** Registers the bounded-cardinality operational contract used by dashboards and alerts. */
@Component
internal class VoucherMetrics(
    private val registry: MeterRegistry,
) : DatabasePermitMetrics {
    private val commandTimers = ConcurrentHashMap<MetricKey, Timer>()
    private val databaseRejections = ConcurrentHashMap<String, Counter>()
    private val redisDegradations = ConcurrentHashMap<String, Counter>()
    private val sseRejections = ConcurrentHashMap<String, Counter>()
    private val openReviews = AtomicInteger()
    private val oldestBacklogSeconds = AtomicLong()
    private val workerLastSuccessEpochSeconds = AtomicLong()
    private val workerAttempts = AtomicLong()
    private val activeSse = AtomicInteger()
    private val leaderState = AtomicInteger(VoucherLeaderState.UNKNOWN.metricValue)

    init {
        Gauge.builder("voucher.review.open", openReviews) { it.get().toDouble() }.register(registry)
        Gauge.builder("voucher.backlog.oldest.age", oldestBacklogSeconds) { it.get().toDouble() }
            .baseUnit("seconds")
            .register(registry)
        Gauge.builder("voucher.worker.last.success", workerLastSuccessEpochSeconds) { it.get().toDouble() }
            .baseUnit("seconds")
            .register(registry)
        Gauge.builder("voucher.worker.attempts", workerAttempts) { it.get().toDouble() }.register(registry)
        Gauge.builder("voucher.sse.active", activeSse) { it.get().toDouble() }.register(registry)
        Gauge.builder("voucher.leader.state", leaderState) { it.get().toDouble() }.register(registry)
    }

    fun recordCommand(
        operation: String,
        outcome: String,
        duration: Duration,
    ) {
        val key = MetricKey(bounded(operation), bounded(outcome))
        commandTimers.computeIfAbsent(key) {
            Timer.builder("voucher.command.duration")
                .tag("operation", key.operation)
                .tag("outcome", key.outcome)
                .register(registry)
        }.record(duration)
    }

    fun databaseRejected(lane: String) {
        databaseRejections.computeIfAbsent(bounded(lane)) {
            Counter.builder("voucher.db.bulkhead.rejected")
                .tag("admission", it)
                .register(registry)
        }.increment()
    }

    override fun rejected(lane: DatabaseLane) {
        databaseRejected(lane.name)
    }

    fun redisDegraded(backend: String) {
        redisDegradations.computeIfAbsent(bounded(backend)) {
            Counter.builder("voucher.redis.degraded")
                .tag("backend", it)
                .register(registry)
        }.increment()
    }

    fun reviewOpen(count: Int) {
        openReviews.set(count.coerceAtLeast(0))
    }

    fun backlogOldestAge(age: Duration) {
        oldestBacklogSeconds.set(age.seconds.coerceAtLeast(0))
    }

    fun workerSucceeded(
        epochSeconds: Long,
        attempts: Int,
    ) {
        workerLastSuccessEpochSeconds.set(epochSeconds.coerceAtLeast(0))
        workerAttempts.addAndGet(attempts.coerceAtLeast(0).toLong())
    }

    fun sseOpened() {
        activeSse.incrementAndGet()
    }

    fun sseClosed() {
        activeSse.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun sseRejected(reason: String) {
        sseRejections.computeIfAbsent(bounded(reason)) {
            Counter.builder("voucher.sse.rejected")
                .tag("reason", it)
                .register(registry)
        }.increment()
    }

    fun leaderState(state: VoucherLeaderState) {
        leaderState.set(state.metricValue)
    }

    private data class MetricKey(val operation: String, val outcome: String)

    private companion object {
        private val BOUNDED_VALUE = Regex("[A-Z][A-Z0-9_]{0,31}")

        fun bounded(value: String): String = value.uppercase().takeIf(BOUNDED_VALUE::matches) ?: "UNKNOWN"
    }
}

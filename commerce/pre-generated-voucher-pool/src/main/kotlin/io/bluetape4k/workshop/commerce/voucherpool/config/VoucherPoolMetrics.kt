@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.workshop.commerce.voucherpool.admission.PermitLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcExecutionLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcTimeoutPhase
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcMetrics
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal enum class VoucherPoolAlert {
    HIKARI_PENDING,
    WORKER_NO_PROGRESS,
    REDIS_DEGRADED,
    ELIGIBLE_DEPTH_LOW,
    SSE_RESET_BURST,
    QUARANTINE_PRESENT,
    PURGE_LAG,
    RESTORE_FAILURE,
}

internal class VoucherPoolAlertWindow(
    val threshold: Double,
    val duration: Duration,
)

internal object VoucherPoolAlertPolicy {
    private val windows =
        mapOf(
            VoucherPoolAlert.HIKARI_PENDING to VoucherPoolAlertWindow(0.0, Duration.ofSeconds(10)),
            VoucherPoolAlert.WORKER_NO_PROGRESS to VoucherPoolAlertWindow(0.0, Duration.ofSeconds(30)),
            VoucherPoolAlert.REDIS_DEGRADED to VoucherPoolAlertWindow(0.0, Duration.ofSeconds(30)),
            VoucherPoolAlert.ELIGIBLE_DEPTH_LOW to VoucherPoolAlertWindow(0.10, Duration.ofSeconds(60)),
            VoucherPoolAlert.SSE_RESET_BURST to VoucherPoolAlertWindow(10.0, Duration.ofMinutes(1)),
            VoucherPoolAlert.QUARANTINE_PRESENT to VoucherPoolAlertWindow(1.0, Duration.ZERO),
            VoucherPoolAlert.PURGE_LAG to VoucherPoolAlertWindow(0.0, Duration.ofHours(24)),
            VoucherPoolAlert.RESTORE_FAILURE to VoucherPoolAlertWindow(1.0, Duration.ZERO),
        )

    operator fun get(alert: VoucherPoolAlert): VoucherPoolAlertWindow = windows.getValue(alert)
}

/** voucher pool을 위한 bounded-tag operational metric만 등록합니다. */
@Component
@Suppress("TooManyFunctions")
internal class VoucherPoolMetrics(
    private val registry: MeterRegistry,
) : VoucherPoolJdbcMetrics {
    private val hikariActive = AtomicInteger()
    private val hikariPending = AtomicInteger()
    private val workerBacklog = ConcurrentHashMap<WorkerKind, AtomicLong>()
    private val workerOldestAge = ConcurrentHashMap<WorkerKind, AtomicLong>()
    private val workerCheckpoint = ConcurrentHashMap<WorkerKind, AtomicLong>()
    private val activeSse = AtomicInteger()
    private val sseResets = AtomicLong()
    private val poolEligibleRatio = AtomicLong(RATIO_SCALE.toLong())
    private val quarantineCount = AtomicLong()
    private val purgeLagSeconds = AtomicLong()
    private val restoreFailures = AtomicLong()
    private val permitWaits = ConcurrentHashMap<MetricKey, Timer>()
    private val degradedComponents = ConcurrentHashMap<String, Counter>()
    private val jdbcTimeouts = ConcurrentHashMap<MetricKey, Counter>()

    init {
        gauge("voucher.pool.hikari.active", hikariActive)
        gauge("voucher.pool.hikari.pending", hikariPending)
        gauge("voucher.pool.sse.subscribers", activeSse)
        gauge("voucher.pool.sse.resets", sseResets)
        Gauge.builder("voucher.pool.depth.eligible.ratio", poolEligibleRatio) { it.get().toDouble() / RATIO_SCALE }
            .register(registry)
        gauge("voucher.pool.quarantine.count", quarantineCount)
        gauge("voucher.pool.purge.lag", purgeLagSeconds, "seconds")
        gauge("voucher.pool.restore.failures", restoreFailures)
        WorkerKind.entries.forEach { kind ->
            gauge(
                "voucher.pool.worker.backlog",
                workerBacklog.computeIfAbsent(kind) { AtomicLong() },
                tags = arrayOf("worker", kind.name),
            )
            gauge(
                "voucher.pool.worker.oldest.age",
                workerOldestAge.computeIfAbsent(kind) { AtomicLong() },
                "seconds",
                "worker",
                kind.name,
            )
            gauge(
                "voucher.pool.worker.checkpoint",
                workerCheckpoint.computeIfAbsent(kind) { AtomicLong() },
                tags = arrayOf("worker", kind.name),
            )
        }
    }

    fun hikari(active: Int, pending: Int) {
        hikariActive.set(active.coerceAtLeast(0))
        hikariPending.set(pending.coerceAtLeast(0))
    }

    fun permitWait(lane: PermitLane, outcome: String, duration: Duration) {
        val key = MetricKey(lane.name, bounded(outcome))
        permitWaits.computeIfAbsent(key) {
            Timer.builder("voucher.pool.permit.wait")
                .tag("lane", it.first)
                .tag("outcome", it.second)
                .register(registry)
        }.record(duration)
    }

    override fun timedOut(lane: JdbcExecutionLane, phase: JdbcTimeoutPhase) {
        val key = MetricKey(lane.name, phase.name)
        jdbcTimeouts.computeIfAbsent(key) {
            Counter.builder("voucher.pool.jdbc.timeout")
                .tag("lane", it.first)
                .tag("phase", it.second)
                .register(registry)
        }.increment()
    }

    fun worker(kind: WorkerKind, backlog: Long, oldestAge: Duration, checkpoint: Long) {
        workerBacklog.getValue(kind).set(backlog.coerceAtLeast(0))
        workerOldestAge.getValue(kind).set(oldestAge.seconds.coerceAtLeast(0))
        workerCheckpoint.getValue(kind).set(checkpoint.coerceAtLeast(0))
    }

    fun sseSubscribers(count: Int) {
        activeSse.set(count.coerceAtLeast(0))
    }

    fun sseReset() {
        sseResets.incrementAndGet()
    }

    fun eligiblePoolRatio(ratio: Double) {
        poolEligibleRatio.set((ratio.coerceIn(0.0, 1.0) * RATIO_SCALE).toLong())
    }

    fun degraded(component: VoucherPoolHealthComponent) {
        degradedComponents.computeIfAbsent(component.name) {
            Counter.builder("voucher.pool.degraded")
                .tag("component", it)
                .register(registry)
        }.increment()
    }

    fun quarantine(count: Long) {
        quarantineCount.set(count.coerceAtLeast(0))
    }

    fun purgeLag(age: Duration) {
        purgeLagSeconds.set(age.seconds.coerceAtLeast(0))
    }

    fun restoreFailed() {
        restoreFailures.incrementAndGet()
    }

    private fun gauge(
        name: String,
        value: AtomicInteger,
        baseUnit: String? = null,
        vararg tags: String,
    ) {
        val builder = Gauge.builder(name, value) { it.get().toDouble() }.tags(*tags)
        baseUnit?.let(builder::baseUnit)
        builder.register(registry)
    }

    private fun gauge(
        name: String,
        value: AtomicLong,
        baseUnit: String? = null,
        vararg tags: String,
    ) {
        val builder = Gauge.builder(name, value) { it.get().toDouble() }.tags(*tags)
        baseUnit?.let(builder::baseUnit)
        builder.register(registry)
    }

    private class MetricKey(val first: String, val second: String) {
        override fun equals(other: Any?): Boolean = other is MetricKey && first == other.first && second == other.second

        override fun hashCode(): Int = 31 * first.hashCode() + second.hashCode()
    }

    private companion object {
        const val RATIO_SCALE = 100_000.0
        val BOUNDED_VALUE = Regex("[A-Z][A-Z0-9_]{0,31}")

        fun bounded(value: String): String = value.uppercase().takeIf(BOUNDED_VALUE::matches) ?: "UNKNOWN"
    }
}

package io.bluetape4k.workshop.leader.k8slease.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Stable tag set used by the workshop-specific Kubernetes Lease meters.
 */
data class LeaseMetricTags(
    val lockName: String,
    val namespace: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Registers and records Micrometer meters for the Kubernetes Lease workshop.
 *
 * The meters are intentionally application-level signals. Library-level
 * `leader-micrometer` decorator meters are still available on the real elector path.
 */
class K8sLeaseMetrics(
    private val registry: MeterRegistry,
) {

    private val activeGauges = ConcurrentHashMap<LeaseMetricTags, AtomicInteger>()

    fun recordGuardAttempt(tags: LeaseMetricTags) {
        counter("workshop.k8s.lease.guard.attempts", tags).increment()
    }

    fun recordSkipped(tags: LeaseMetricTags, reason: String) {
        counter("workshop.k8s.lease.guard.skipped", tags, "reason", reason).increment()
    }

    fun recordRenewAttempt(tags: LeaseMetricTags) {
        counter("workshop.k8s.lease.renew.attempts", tags).increment()
    }

    fun recordRenewFailure(tags: LeaseMetricTags, reason: String) {
        counter("workshop.k8s.lease.renew.failures", tags, "reason", reason).increment()
    }

    fun markActive(tags: LeaseMetricTags) {
        activeGauge(tags).set(1)
    }

    fun markInactive(tags: LeaseMetricTags) {
        activeGauge(tags).set(0)
    }

    fun recordTask(tags: LeaseMetricTags, result: String, duration: Duration) {
        counter("workshop.k8s.lease.task.executions", tags, "result", result).increment()
        Timer.builder("workshop.k8s.lease.task.duration")
            .tag("lock.name", tags.lockName)
            .tag("namespace", tags.namespace)
            .register(registry)
            .record(duration.toJavaDuration())
    }

    private fun counter(name: String, tags: LeaseMetricTags, vararg extraTags: String): Counter {
        val builder = Counter.builder(name)
            .tag("lock.name", tags.lockName)
            .tag("namespace", tags.namespace)
        require(extraTags.size % 2 == 0) { "extraTags must contain key/value pairs" }
        extraTags.asList().chunked(2).forEach { (key, value) ->
            builder.tag(key, value)
        }
        return builder.register(registry)
    }

    private fun activeGauge(tags: LeaseMetricTags): AtomicInteger =
        activeGauges.computeIfAbsent(tags) {
            val value = AtomicInteger(0)
            Gauge.builder("workshop.k8s.lease.leader.active", value) { active -> active.get().toDouble() }
                .tag("lock.name", tags.lockName)
                .tag("namespace", tags.namespace)
                .register(registry)
            value
        }
}

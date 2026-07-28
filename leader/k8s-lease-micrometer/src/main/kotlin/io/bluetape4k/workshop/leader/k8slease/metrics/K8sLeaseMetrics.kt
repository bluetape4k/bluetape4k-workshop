package io.bluetape4k.workshop.leader.k8slease.metrics

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
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
 * 워크숍 전용 Kubernetes Lease meter가 사용하는 안정적인 tag 집합입니다.
 */
data class LeaseMetricTags(
    val lockName: String,
    val namespace: String,
) : Serializable {

    init {
        lockName.requireNotBlank("lockName")
        namespace.requireNotBlank("namespace")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Kubernetes Lease 워크숍의 Micrometer meter를 등록하고 기록합니다.
 *
 * 이 meter들은 의도적으로 application-level signal입니다. Library-level
 * `leader-micrometer` decorator meter는 실제 elector path에서 계속 사용할 수 있습니다.
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
        extraTags.requireKeyValuePairs().asList().chunked(2).forEach { (key, value) ->
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

private fun Array<out String>.requireKeyValuePairs(): Array<out String> = apply {
    (size % 2).requireInRange(0, 0, "extraTags.size % 2")
}

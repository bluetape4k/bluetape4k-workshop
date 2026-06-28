package io.bluetape4k.workshop.leader.k8slease.metrics

import io.bluetape4k.assertions.shouldBeEqualTo
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class K8sLeaseMetricsTest {

    @Test
    fun `records leader state guard renew and task meters with stable tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = K8sLeaseMetrics(registry)
        val tags = LeaseMetricTags(lockName = "orders-export", namespace = "workshop")

        metrics.recordGuardAttempt(tags)
        metrics.recordSkipped(tags, reason = "not-elected")
        metrics.recordRenewAttempt(tags)
        metrics.recordRenewFailure(tags, reason = "backend-error")
        metrics.markActive(tags)
        metrics.recordTask(tags, result = "success", duration = 25.milliseconds)
        metrics.markInactive(tags)

        registry.counter("workshop.k8s.lease.guard.attempts", "lock.name", "orders-export", "namespace", "workshop").count() shouldBeEqualTo 1.0
        registry.counter(
            "workshop.k8s.lease.guard.skipped",
            "lock.name",
            "orders-export",
            "namespace",
            "workshop",
            "reason",
            "not-elected",
        ).count() shouldBeEqualTo 1.0
        registry.counter("workshop.k8s.lease.renew.attempts", "lock.name", "orders-export", "namespace", "workshop").count() shouldBeEqualTo 1.0
        registry.counter(
            "workshop.k8s.lease.renew.failures",
            "lock.name",
            "orders-export",
            "namespace",
            "workshop",
            "reason",
            "backend-error",
        ).count() shouldBeEqualTo 1.0
        registry.counter(
            "workshop.k8s.lease.task.executions",
            "lock.name",
            "orders-export",
            "namespace",
            "workshop",
            "result",
            "success",
        ).count() shouldBeEqualTo 1.0
        registry.timer("workshop.k8s.lease.task.duration", "lock.name", "orders-export", "namespace", "workshop").count() shouldBeEqualTo 1L
        registry.find("workshop.k8s.lease.leader.active")
            .tag("lock.name", "orders-export")
            .tag("namespace", "workshop")
            .gauge()
            ?.value() shouldBeEqualTo 0.0
    }
}

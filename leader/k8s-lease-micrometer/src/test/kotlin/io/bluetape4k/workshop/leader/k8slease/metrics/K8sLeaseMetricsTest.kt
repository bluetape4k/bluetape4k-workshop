package io.bluetape4k.workshop.leader.k8slease.metrics

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.micrometer.LeaderMetricTagMode
import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.bluetape4k.leader.micrometer.LeaderMetricTagRule
import io.bluetape4k.leader.micrometer.LeaderMetricTagSanitizer
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

        registry.counter("workshop.k8s.lease.guard.attempts", "lock.name", "redacted-lock", "namespace", "redacted").count() shouldBeEqualTo 1.0
        registry.counter(
            "workshop.k8s.lease.guard.skipped",
            "lock.name", "redacted-lock", "namespace", "redacted",
            "reason",
            "not-elected",
        ).count() shouldBeEqualTo 1.0
        registry.counter("workshop.k8s.lease.renew.attempts", "lock.name", "redacted-lock", "namespace", "redacted").count() shouldBeEqualTo 1.0
        registry.counter(
            "workshop.k8s.lease.renew.failures",
            "lock.name", "redacted-lock", "namespace", "redacted",
            "reason",
            "backend-error",
        ).count() shouldBeEqualTo 1.0
        registry.counter(
            "workshop.k8s.lease.task.executions",
            "lock.name", "redacted-lock", "namespace", "redacted",
            "result",
            "success",
        ).count() shouldBeEqualTo 1.0
        registry.timer("workshop.k8s.lease.task.duration", "lock.name", "redacted-lock", "namespace", "redacted").count() shouldBeEqualTo 1L
        registry.find("workshop.k8s.lease.leader.active")
            .tag("lock.name", "redacted-lock")
            .tag("namespace", "redacted")
            .gauge()
            ?.value() shouldBeEqualTo 0.0
    }

    @Test
    fun `custom tag policy hashes lock identity and allowlists stable result`() {
        val registry = SimpleMeterRegistry()
        val options = LeaderMetricTagOptions(
            lockName = LeaderMetricTagRule(mode = LeaderMetricTagMode.HASH, hashLength = 12),
            defaultRule = LeaderMetricTagRule(
                mode = LeaderMetricTagMode.RAW,
                allowList = setOf("success"),
                redactedValue = "redacted",
            ),
        )
        val sanitizer = LeaderMetricTagSanitizer.from(options)
        val metrics = K8sLeaseMetrics(registry, sanitizer)
        val tags = LeaseMetricTags(lockName = "tenant-secret-42", namespace = "prod-private")

        metrics.recordTask(tags, result = "success", duration = 1.milliseconds)

        val hashedLock = sanitizer.sanitize("lock.name", tags.lockName)
        registry.counter(
            "workshop.k8s.lease.task.executions",
            "lock.name", hashedLock,
            "namespace", "redacted",
            "result", "success",
        ).count() shouldBeEqualTo 1.0
        hashedLock shouldBeEqualTo sanitizer.sanitize("lock.name", tags.lockName)
        hashedLock shouldBeEqualTo "c2e094dac90d"

        val truncatingSanitizer = LeaderMetricTagSanitizer.from(
            LeaderMetricTagOptions(
                defaultRule = LeaderMetricTagRule(
                    mode = LeaderMetricTagMode.TRUNCATE,
                    maxLength = 5,
                ),
            ),
        )
        truncatingSanitizer.sanitize("namespace", tags.namespace) shouldBeEqualTo "prod-"
    }

    @Test
    fun `metric tags reject blank identity fields`() {
        assertFailsWith<IllegalArgumentException> {
            LeaseMetricTags(lockName = " ", namespace = "workshop")
        }
        assertFailsWith<IllegalArgumentException> {
            LeaseMetricTags(lockName = "orders-export", namespace = " ")
        }
    }
}

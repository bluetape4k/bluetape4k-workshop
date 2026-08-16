package io.bluetape4k.workshop.leader.k8slease.job

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.leader.k8slease.config.K8sLeaseMicrometerProperties
import io.bluetape4k.workshop.leader.k8slease.leader.LeaderCoordinator
import io.bluetape4k.workshop.leader.k8slease.metrics.K8sLeaseMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class K8sLeaseGuardedTaskTest {

    @Test
    fun `elected coordinator executes task and records success metrics`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val task = K8sLeaseGuardedTask(
            coordinator = FakeLeaderCoordinator(elected = true),
            properties = K8sLeaseMicrometerProperties(simulatedWorkTime = java.time.Duration.ofMillis(1)),
            metrics = K8sLeaseMetrics(registry),
        )

        val report = task.runOnce()

        report.executed.shouldBeTrue()
        report.reason shouldBeEqualTo "elected"
        task.executionCount.get() shouldBeEqualTo 1
        registry.counter("workshop.k8s.lease.guard.attempts", "lock.name", "redacted-lock", "namespace", "redacted").count() shouldBeEqualTo 1.0
        registry.counter(
            "workshop.k8s.lease.task.executions",
            "lock.name", "redacted-lock", "namespace", "redacted",
            "result",
            "success",
        ).count() shouldBeEqualTo 1.0
    }

    @Test
    fun `skipped coordinator does not execute task and records skip metrics`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val task = K8sLeaseGuardedTask(
            coordinator = FakeLeaderCoordinator(elected = false),
            properties = K8sLeaseMicrometerProperties(),
            metrics = K8sLeaseMetrics(registry),
        )

        val report = task.runOnce()

        report.executed.shouldBeFalse()
        report.reason shouldBeEqualTo "not-elected"
        task.executionCount.get() shouldBeEqualTo 0
        registry.counter(
            "workshop.k8s.lease.guard.skipped",
            "lock.name", "redacted-lock", "namespace", "redacted",
            "reason",
            "not-elected",
        ).count() shouldBeEqualTo 1.0
    }

    @Test
    fun `failing leader task records failure and resets active gauge`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val task = K8sLeaseGuardedTask(
            coordinator = FakeLeaderCoordinator(elected = true),
            properties = K8sLeaseMicrometerProperties(simulatedWorkTime = java.time.Duration.ofMillis(1)),
            metrics = K8sLeaseMetrics(registry),
        ).also { it.failWork = true }

        val report = task.runOnce()

        report.executed.shouldBeFalse()
        report.reason shouldBeEqualTo "task-failed"
        registry.counter(
            "workshop.k8s.lease.task.executions",
            "lock.name", "redacted-lock", "namespace", "redacted",
            "result",
            "failure",
        ).count() shouldBeEqualTo 1.0
        registry.find("workshop.k8s.lease.leader.active")
            .tag("lock.name", "redacted-lock")
            .tag("namespace", "redacted")
            .gauge()
            ?.value() shouldBeEqualTo 0.0
    }

    private class FakeLeaderCoordinator(
        private val elected: Boolean,
    ) : LeaderCoordinator {
        val attempts = AtomicInteger()

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
            attempts.incrementAndGet()
            return if (elected) action() else null
        }
    }
}

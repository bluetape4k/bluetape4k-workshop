package io.bluetape4k.workshop.leader.k8slease.job

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderElectionListener
import io.bluetape4k.leader.micrometer.OBSERVATION_LEADER_AOP_ACQUIRE
import io.bluetape4k.leader.micrometer.OBSERVATION_LEADER_AOP_EXECUTION
import io.bluetape4k.leader.micrometer.OBSERVATION_TAG_OUTCOME
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.leader.k8slease.config.K8sLeaseMicrometerProperties
import io.bluetape4k.workshop.leader.k8slease.leader.LeaderCoordinator
import io.bluetape4k.workshop.leader.k8slease.metrics.K8sLeaseMetrics
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class K8sLeaseGuardedTaskObservationTest {

    @Test
    fun `elected path emits acquire and execute success with sanitized identifiers`() = runSuspendIO {
        val (registry, handler) = observationRegistry()
        val task = task(
            registry = registry,
            coordinator = FakeLeaderCoordinator(elected = true),
        )

        val report = task.runOnce()

        report.executed.shouldBeTrue()
        handler.stopped.map { it.name } shouldBeEqualTo listOf(
            "leader.election.event",
            OBSERVATION_LEADER_AOP_ACQUIRE,
            OBSERVATION_LEADER_AOP_EXECUTION,
            "leader.election.event",
        )
        val execution = handler.stopped.first { it.name == OBSERVATION_LEADER_AOP_EXECUTION }
        execution.high["lock.name"] shouldBeEqualTo "redacted-lock"
        execution.high["leader.id"] shouldBeEqualTo "redacted-leader"
        execution.low[OBSERVATION_TAG_OUTCOME] shouldBeEqualTo "success"
    }

    @Test
    fun `task failure emits error execution observation`() = runSuspendIO {
        val (registry, handler) = observationRegistry()
        val task = task(
            registry = registry,
            coordinator = FakeLeaderCoordinator(elected = true),
        ).also { it.failWork = true }

        val report = task.runOnce()

        report.executed.shouldBeFalse()
        handler.stopped.first { it.name == OBSERVATION_LEADER_AOP_EXECUTION }
            .low[OBSERVATION_TAG_OUTCOME] shouldBeEqualTo "error"
    }

    @Test
    fun `cancellation emits cancelled execution observation and propagates`() = runSuspendIO {
        val (registry, handler) = observationRegistry()
        val task = task(
            registry = registry,
            coordinator = FakeLeaderCoordinator(elected = true),
            simulatedWorkTime = java.time.Duration.ofSeconds(5),
        )

        assertFailsWith<CancellationException> { withTimeout(25) { task.runOnce() } }

        handler.stopped.first { it.name == OBSERVATION_LEADER_AOP_EXECUTION }
            .low[OBSERVATION_TAG_OUTCOME] shouldBeEqualTo "cancelled"
        handler.stopped.first { it.name == OBSERVATION_LEADER_AOP_EXECUTION }.error shouldBeEqualTo null
    }

    private fun task(
        registry: ObservationRegistry,
        coordinator: LeaderCoordinator,
        simulatedWorkTime: java.time.Duration = java.time.Duration.ofMillis(1),
    ): K8sLeaseGuardedTask = K8sLeaseGuardedTask(
        coordinator = coordinator,
        properties = K8sLeaseMicrometerProperties(simulatedWorkTime = simulatedWorkTime),
        metrics = K8sLeaseMetrics(io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
        observationRecorder = MicrometerObservationLeaderAopMetricsRecorder(
            registry = registry,
            options = LeaderObservationOptions(includeLockName = true, includeLeaderId = true),
        ),
        observationListener = MicrometerObservationLeaderElectionListener(registry),
    )

    private fun observationRegistry(): Pair<ObservationRegistry, CollectingObservationHandler> {
        val handler = CollectingObservationHandler()
        val registry = ObservationRegistry.create().also { it.observationConfig().observationHandler(handler) }
        return registry to handler
    }

    private class FakeLeaderCoordinator(
        private val elected: Boolean,
    ) : LeaderCoordinator {
        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
            if (!elected) null else action()
    }

    private class CollectingObservationHandler : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<Snapshot>()

        override fun supportsContext(context: Observation.Context): Boolean = true

        override fun onStop(context: Observation.Context) {
            stopped += Snapshot(
                name = context.name.orEmpty(),
                low = context.lowCardinalityKeyValues.associate { it.key to it.value },
                high = context.highCardinalityKeyValues.associate { it.key to it.value },
                error = context.error,
            )
        }
    }

    private data class Snapshot(
        val name: String,
        val low: Map<String, String>,
        val high: Map<String, String>,
        val error: Throwable?,
    )
}

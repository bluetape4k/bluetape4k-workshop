package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

internal val EVENT_SOURCED_DEGRADED_STATUS = Status("DEGRADED")

internal enum class EventSourcedReadinessState {
    STARTING,
    READY,
    AUTHORITY_FAILED,
    STOPPING,
}

/** process readiness를 advisory aggregate/projection/rebuild degradation과 분리합니다. */
@Component
internal class EventSourcedOperationalState {
    private val readiness = AtomicReference(EventSourcedReadinessState.STARTING)
    private val aggregateDegraded = AtomicReference(false)
    private val projectionDegraded = AtomicReference(false)
    private val rebuild = AtomicReference<ProjectionGenerationState?>(null)

    fun markReady() {
        readiness.set(EventSourcedReadinessState.READY)
    }

    fun markAuthorityFailure() {
        readiness.set(EventSourcedReadinessState.AUTHORITY_FAILED)
    }

    fun beginShutdown() {
        readiness.set(EventSourcedReadinessState.STOPPING)
    }

    fun degradeAggregate() {
        aggregateDegraded.set(true)
    }

    fun projectionHealth(
        degraded: Boolean,
        rebuildState: ProjectionGenerationState? = null,
    ) {
        projectionDegraded.set(degraded)
        rebuildState?.let(rebuild::set)
    }

    fun rebuildState(state: ProjectionGenerationState) {
        rebuild.set(state)
    }

    fun readinessState(): EventSourcedReadinessState = readiness.get()

    fun isAggregateDegraded(): Boolean = aggregateDegraded.get()

    fun isProjectionDegraded(): Boolean = projectionDegraded.get()

    fun rebuildState(): ProjectionGenerationState? = rebuild.get()
}

@Component("eventSourcedReadinessHealthIndicator")
internal class EventSourcedReadinessHealthIndicator(
    private val state: EventSourcedOperationalState,
) : HealthIndicator {
    override fun health(): Health =
        when (state.readinessState()) {
            EventSourcedReadinessState.READY -> Health.up().build()
            EventSourcedReadinessState.AUTHORITY_FAILED -> Health.down().build()
            EventSourcedReadinessState.STARTING,
            EventSourcedReadinessState.STOPPING,
            -> Health.status(Status.OUT_OF_SERVICE).build()
        }
}

@Component("eventSourcedAggregateHealthIndicator")
internal class EventSourcedAggregateHealthIndicator(
    private val state: EventSourcedOperationalState,
) : HealthIndicator {
    override fun health(): Health =
        if (state.isAggregateDegraded()) Health.status(EVENT_SOURCED_DEGRADED_STATUS).build() else Health.up().build()
}

@Component("eventSourcedProjectionHealthIndicator")
internal class EventSourcedProjectionHealthIndicator(
    private val state: EventSourcedOperationalState,
) : HealthIndicator {
    override fun health(): Health =
        Health.status(if (state.isProjectionDegraded()) EVENT_SOURCED_DEGRADED_STATUS else Status.UP)
            .withDetail("rebuildState", state.rebuildState()?.name ?: "NONE")
            .build()
}

/** liveness는 process-local health만 보고합니다. external authority는 readiness에 속합니다. */
@Component("eventSourcedLivenessHealthIndicator")
internal class EventSourcedLivenessHealthIndicator : HealthIndicator {
    override fun health(): Health = Health.up().build()
}

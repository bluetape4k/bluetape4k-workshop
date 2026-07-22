package io.bluetape4k.workshop.commerce.metering.eventsourcing.worker

import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationQueryRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionRebuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

data class ProjectionCycle(
    val activeGeneration: Int,
    val buildingGeneration: Int?,
    val buildingResult: ProjectionBatchResult?,
)

@Component
class ProjectionGenerationRunner(
    transactionManager: PlatformTransactionManager,
    private val worker: ProjectionWorker,
    private val generations: ProjectionGenerationRepository,
    private val generationQueries: ProjectionGenerationQueryRepository,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun runOnce(projectionName: String): ProjectionCycle? {
        val active = transactions.execute { generations.active(projectionName) } ?: return null
        worker.runOnce(projectionName, active.generation, UUID.randomUUID())
        val building = transactions.execute { generationQueries.building(projectionName) }
        val buildingResult = building?.let {
            worker.runOnce(projectionName, it.generation, UUID.randomUUID())
        }
        return ProjectionCycle(active.generation, building?.generation, buildingResult)
    }
}

@Component
class ProjectionGenerationSwitcher(
    transactionManager: PlatformTransactionManager,
    private val checkpoints: ProjectionCheckpointRepository,
    private val rebuilder: ProjectionRebuilder,
    private val eventStore: EventStore,
    private val clock: Clock,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun switchIfCaughtUp(projectionName: String, cycle: ProjectionCycle): Boolean {
        val generation = cycle.buildingGeneration
        val result = cycle.buildingResult
        val switchReady = result?.let { it.acquired && it.lag == 0L } == true
        if (generation == null || !switchReady) return false
        return transactions.execute {
            val now = clock.instant()
            val lease = checkpoints.acquireLease(
                projectionName,
                generation,
                UUID.randomUUID(),
                now,
                SWITCH_LEASE,
            ) ?: return@execute false
            rebuilder.catchUpAndSwitch(lease, cycle.activeGeneration, eventStore.latestGlobalPosition(), now)
        }
    }

    private companion object {
        val SWITCH_LEASE: Duration = Duration.ofSeconds(30)
    }
}

@Component
@ConditionalOnProperty(
    prefix = "workshop.metering-events.projection",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ProjectionScheduler(
    private val runner: ProjectionGenerationRunner,
    private val switcher: ProjectionGenerationSwitcher,
) {
    @Scheduled(
        fixedDelayString = "\${workshop.metering-events.projection.fixed-delay:1s}",
        initialDelayString = "\${workshop.metering-events.projection.initial-delay:1s}",
    )
    fun runOnce() {
        val cycle = runner.runOnce(BILLING_PROJECTION) ?: return
        switcher.switchIfCaughtUp(BILLING_PROJECTION, cycle)
    }

    private companion object {
        const val BILLING_PROJECTION = "billing"
    }
}

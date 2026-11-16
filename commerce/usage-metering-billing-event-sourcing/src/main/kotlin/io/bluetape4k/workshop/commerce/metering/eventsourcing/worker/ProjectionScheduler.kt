package io.bluetape4k.workshop.commerce.metering.eventsourcing.worker

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationQueryRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionRebuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

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
    companion object: KLogging()

    private val transactions = TransactionTemplate(transactionManager)

    fun runOnce(projectionName: String): ProjectionCycle? {
        val active = transactions.execute { generations.active(projectionName) } ?: return null
        worker.runOnce(projectionName, active.generation, Uuid.V7.nextId())

        val building = transactions.execute {
            generationQueries.building(projectionName)
        }
        val buildingResult = building?.let {
            worker.runOnce(projectionName, it.generation, Uuid.V7.nextId())
        }
        return ProjectionCycle(
            activeGeneration = active.generation,
            buildingGeneration = building?.generation,
            buildingResult = buildingResult
        )
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

    private companion object: KLogging() {
        val SWITCH_LEASE: Duration = 30.seconds()
    }

    private val transactions = TransactionTemplate(transactionManager)

    fun switchIfCaughtUp(projectionName: String, cycle: ProjectionCycle): Boolean {
        val generation = cycle.buildingGeneration
        val result = cycle.buildingResult
        val switchReady = result?.let { it.acquired && it.lag == 0L } == true
        if (generation == null || !switchReady) return false

        return transactions.execute {
            val now = clock.instant()
            val lease = checkpoints.acquireLease(
                projectionName = projectionName,
                generation = generation,
                ownerToken = Uuid.V7.nextId(),
                now = now,
                duration = SWITCH_LEASE,
            ) ?: return@execute false

            rebuilder.catchUpAndSwitch(
                lease = lease,
                expectedActiveGeneration = cycle.activeGeneration,
                observedHighWatermark = eventStore.latestGlobalPosition(),
                now = now
            )
        }
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

    private companion object: KLogging() {
        const val BILLING_PROJECTION = "billing"
    }

    @Scheduled(
        fixedDelayString = "\${workshop.metering-events.projection.fixed-delay:1s}",
        initialDelayString = "\${workshop.metering-events.projection.initial-delay:1s}",
    )
    fun runOnce() {
        val cycle = runner.runOnce(BILLING_PROJECTION) ?: return
        switcher.switchIfCaughtUp(BILLING_PROJECTION, cycle)
    }
}

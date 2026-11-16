package io.bluetape4k.workshop.commerce.metering.eventsourcing.worker

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.DomainEventJsonCodec
import io.bluetape4k.workshop.commerce.metering.eventsourcing.config.EventSourcingMetrics
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionFailureRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.NewProjectionFailure
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionCoordinator
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionEventContext
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionHandlers
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionLease
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.*

data class ProjectionBatchResult(val acquired: Boolean, val applied: Int, val checkpoint: Long, val lag: Long = 0)

@Component
class ProjectionWorker(
    transactionManager: PlatformTransactionManager,
    private val processor: ProjectionBatchProcessor,
    private val metrics: EventSourcingMetrics,
) {

    private companion object: KLogging() {
        const val DEFAULT_BATCH_SIZE = 100
        const val MAX_BATCH_SIZE = 1_000
        const val PROJECTION_SUCCESS = "success"
        const val PROJECTION_FAILURE = "failure"
    }

    private val transactions = TransactionTemplate(transactionManager)

    fun runOnce(
        projectionName: String,
        generation: Int,
        ownerToken: UUID,
        limit: Int = DEFAULT_BATCH_SIZE,
    ): ProjectionBatchResult {
        limit.requireInRange(1, MAX_BATCH_SIZE, "limit")

        val lease = transactions.execute {
            processor.acquire(projectionName, generation, ownerToken)
        } ?: return ProjectionBatchResult(false, 0, 0)

        return runCatching {
            transactions.execute { processor.applyBatch(lease, limit) }
        }.onSuccess { result ->
            metrics.recordProjection(PROJECTION_SUCCESS, result.lag)
        }.getOrElse { failure ->
            val eventType = transactions.execute { processor.quarantine(lease, failure) }
            metrics.recordProjection(PROJECTION_FAILURE, 0)
            metrics.recordQuarantine(eventType)
            throw failure
        }
    }


}

@Component
class ProjectionBatchProcessor(
    private val eventStore: EventStoreRepository,
    private val checkpoints: ProjectionCheckpointRepository,
    private val failures: ProjectionFailureRepository,
    private val applier: ProjectionEventApplier,
    private val clock: Clock,
) {

    private companion object: KLogging() {
        const val FAILURE_LOOKAHEAD = 1
        val LEASE_DURATION: Duration = 30.seconds()
    }

    fun acquire(
        projectionName: String,
        generation: Int,
        ownerToken: UUID,
    ): ProjectionLease? =
        checkpoints.acquireLease(
            projectionName = projectionName,
            generation = generation,
            ownerToken = ownerToken,
            now = clock.instant(),
            duration = LEASE_DURATION,
        )

    fun applyBatch(lease: ProjectionLease, limit: Int): ProjectionBatchResult {
        val generation = checkpoints.requireOwnership(lease)
        val observedHead = eventStore.latestGlobalPosition()
        val events = eventStore.loadAfterGlobalPosition(generation.checkpoint, limit)

        events.forEach { persisted ->
            applier.apply(lease, persisted)
        }
        val checkpoint = events.lastOrNull()?.globalPosition ?: generation.checkpoint
        val highWatermark = maxOf(generation.highWatermark, observedHead, checkpoint)

        checkpoints.raiseHighWatermark(lease, highWatermark, clock.instant())
        checkpoints.releaseLease(lease, clock.instant())

        return ProjectionBatchResult(
            acquired = true,
            applied = events.size,
            checkpoint = checkpoint,
            lag = (highWatermark - checkpoint).coerceAtLeast(0),
        )
    }

    fun quarantine(lease: ProjectionLease, failure: Throwable): String {
        val generation = checkpoints.requireOwnership(lease)
        val persisted = eventStore.loadAfterGlobalPosition(generation.checkpoint, FAILURE_LOOKAHEAD)
            .firstOrNull() ?: throw failure
        val digest = failureDigest(failure)

        failures.record(
            NewProjectionFailure(
                projectionName = lease.projectionName,
                generation = lease.generation,
                eventId = persisted.eventId,
                eventType = persisted.eventType,
                globalPosition = persisted.globalPosition,
                errorDigest = digest,
                failedAt = clock.instant(),
            ),
        )
        checkpoints.markFailed(
            lease = lease,
            failedPosition = persisted.globalPosition,
            failureDigest = digest,
            now = clock.instant()
        )

        return persisted.eventType
    }

    private fun failureDigest(failure: Throwable): String {
        val material = "${failure::class.qualifiedName}:${failure.message.orEmpty()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toUtf8Bytes())
            .joinToString("") { byte ->
                "%02x".format(Locale.ROOT, byte)
            }
    }
}

@Component
class ProjectionEventApplier(
    private val coordinator: ProjectionCoordinator,
    private val handlers: ProjectionHandlers,
    private val codec: DomainEventJsonCodec,
) {

    companion object: KLogging()

    fun apply(lease: ProjectionLease, persisted: PersistedEvent) {
        val decoded = codec.registry.decode(
            eventType = persisted.eventType,
            schemaVersion = persisted.schemaVersion,
            payload = persisted.payload,
        ) as DomainEvent

        val context = ProjectionEventContext(
            projectionName = lease.projectionName,
            generation = lease.generation,
            tenantId = persisted.stream.tenantId,
            eventId = persisted.eventId,
            globalPosition = persisted.globalPosition,
            occurredAt = persisted.occurredAt,
        )

        coordinator.apply(lease, persisted.eventId, persisted.globalPosition) {
            handlers.handle(context, decoded)
        }
    }
}

package io.bluetape4k.workshop.commerce.metering.eventsourcing.worker

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
import java.util.Locale
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.text.Charsets.UTF_8

data class ProjectionBatchResult(val acquired: Boolean, val applied: Int, val checkpoint: Long, val lag: Long = 0)

@Component
class ProjectionWorker(
    transactionManager: PlatformTransactionManager,
    private val processor: ProjectionBatchProcessor,
    private val metrics: EventSourcingMetrics,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun runOnce(
        projectionName: String,
        generation: Int,
        ownerToken: UUID,
        limit: Int = DEFAULT_BATCH_SIZE,
    ): ProjectionBatchResult {
        require(limit in 1..MAX_BATCH_SIZE) { "projection_batch_size_invalid" }
        val lease = transactions.execute {
            processor.acquire(projectionName, generation, ownerToken)
        } ?: return ProjectionBatchResult(false, 0, 0)

        return runCatching {
            checkNotNull(transactions.execute { processor.applyBatch(lease, limit) })
        }.onSuccess { result ->
            metrics.recordProjection(PROJECTION_SUCCESS, result.lag)
        }.getOrElse { failure ->
            val eventType = checkNotNull(transactions.execute { processor.quarantine(lease, failure) })
            metrics.recordProjection(PROJECTION_FAILURE, 0)
            metrics.recordQuarantine(eventType)
            throw failure
        }
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 100
        const val MAX_BATCH_SIZE = 1_000
        const val PROJECTION_SUCCESS = "success"
        const val PROJECTION_FAILURE = "failure"
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
    fun acquire(
        projectionName: String,
        generation: Int,
        ownerToken: UUID,
    ): ProjectionLease? = checkpoints.acquireLease(
        projectionName,
        generation,
        ownerToken,
        clock.instant(),
        LEASE_DURATION,
    )

    fun applyBatch(lease: ProjectionLease, limit: Int): ProjectionBatchResult {
        val generation = checkpoints.requireOwnership(lease)
        val observedHead = eventStore.latestGlobalPosition()
        val events = eventStore.loadAfterGlobalPosition(generation.checkpoint, limit)
        events.forEach { persisted -> applier.apply(lease, persisted) }
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
        val persisted = eventStore.loadAfterGlobalPosition(generation.checkpoint, FAILURE_LOOKAHEAD).firstOrNull()
            ?: throw failure
        val digest = failureDigest(failure)
        failures.record(
            NewProjectionFailure(
                lease.projectionName,
                lease.generation,
                persisted.eventId,
                persisted.eventType,
                persisted.globalPosition,
                digest,
                clock.instant(),
            ),
        )
        checkpoints.markFailed(lease, persisted.globalPosition, digest, clock.instant())
        return persisted.eventType
    }

    private fun failureDigest(failure: Throwable): String {
        val material = "${failure::class.qualifiedName}:${failure.message.orEmpty()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private companion object {
        const val FAILURE_LOOKAHEAD = 1
        val LEASE_DURATION: Duration = Duration.ofSeconds(30)
    }
}

@Component
class ProjectionEventApplier(
    private val coordinator: ProjectionCoordinator,
    private val handlers: ProjectionHandlers,
    private val codec: DomainEventJsonCodec,
) {
    fun apply(lease: ProjectionLease, persisted: PersistedEvent) {
        val decoded = codec.registry.decode(
            persisted.eventType,
            persisted.schemaVersion,
            persisted.payload,
        ) as DomainEvent
        val context = ProjectionEventContext(
            lease.projectionName,
            lease.generation,
            persisted.stream.tenantId,
            persisted.eventId,
            persisted.globalPosition,
            persisted.occurredAt,
        )
        coordinator.apply(lease, persisted.eventId, persisted.globalPosition) {
            handlers.handle(context, decoded)
        }
    }
}

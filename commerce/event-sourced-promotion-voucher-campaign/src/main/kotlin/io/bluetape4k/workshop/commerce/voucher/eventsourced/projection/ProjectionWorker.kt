package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.StreamReference
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val MIN_POLL_BACKOFF_MILLIS = 100L
private const val MAX_POLL_BACKOFF_MILLIS = 2_000L
private val MIN_POLL_BACKOFF: Duration = Duration.ofMillis(MIN_POLL_BACKOFF_MILLIS)
private val MAX_POLL_BACKOFF: Duration = Duration.ofMillis(MAX_POLL_BACKOFF_MILLIS)

internal data class CommittedProjectionBatch(
    val events: List<EventEnvelope>,
    val committedHead: Long,
) {
    init {
        events.size.requireLe(MAX_PROJECTION_BATCH_EVENTS, "events.size")
        committedHead.requireZeroOrPositiveNumber("committedHead")
    }
}

internal interface ProjectionEventReader {
    fun loadAfter(globalPosition: Long): CommittedProjectionBatch
}

/** Reads one committed keyset page while the caller owns the projection transaction. */
internal class ExposedProjectionEventReader : ProjectionEventReader {
    override fun loadAfter(globalPosition: Long): CommittedProjectionBatch {
        TransactionManager.current()
        globalPosition.requireZeroOrPositiveNumber("globalPosition")
        val events =
            EventLog
                .selectAll()
                .where { EventLog.globalPosition greater globalPosition }
                .orderBy(EventLog.globalPosition to SortOrder.ASC)
                .limit(MAX_PROJECTION_BATCH_EVENTS)
                .map(::toProjectionEnvelope)
                .takeWithinByteCap()
        val committedHead =
            EventLog
                .selectAll()
                .orderBy(EventLog.globalPosition to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(EventLog.globalPosition)
                ?: 0L
        return CommittedProjectionBatch(events, committedHead)
    }
}

internal fun interface ProjectionEventHandler {
    fun verify(event: EventEnvelope)
}

internal object AcceptingProjectionEventHandler : ProjectionEventHandler {
    override fun verify(event: EventEnvelope) = Unit
}

internal class ProjectionPoisonException(
    val reasonClass: String,
) : IllegalArgumentException("projection event cannot be handled: $reasonClass") {
    init {
        reasonClass.requireNotBlank("reasonClass")
    }
}

internal sealed interface ProjectionPollResult {
    data class Applied(
        val appliedEventCount: Int,
        val duplicateEventCount: Int,
        val lag: Long,
    ) : ProjectionPollResult

    data object Idle : ProjectionPollResult

    data class Degraded(
        val eventId: UUID,
        val reasonClass: String,
        val attempts: Int,
        val retryable: Boolean,
        val operatorAction: ProjectionOperatorAction,
    ) : ProjectionPollResult
}

internal enum class ProjectionOperatorAction {
    RETRY_POISON_EVENT,
}

internal interface ProjectionTransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}

internal class ExposedProjectionTransactionRunner(
    private val database: Database,
    permits: EventSourcedDatabasePermitGate,
) : ProjectionTransactionRunner {
    private val transactions =
        EventSourcedPermitTransactionRunner(database, permits, EventSourcedDatabaseLane.PROJECTION)

    override fun <T> inTransaction(block: () -> T): T = transactions.inTransaction(block)
}

/** One bounded poll; callers own scheduling and cooperative cancellation. */
internal class ProjectionWorker(
    private val repository: ProjectionRepository,
    private val reader: ProjectionEventReader,
    private val handler: ProjectionEventHandler = AcceptingProjectionEventHandler,
) {

    fun poll(
        key: ProjectionKey,
        lease: ProjectionLease,
        now: Instant,
    ): ProjectionPollResult {
        val checkpoint = repository.checkpoint(key)?.position ?: 0L
        val batch = reader.loadAfter(checkpoint)
        if (batch.events.isEmpty()) {
            return ProjectionPollResult.Idle
        }
        return handleBatch(key, lease, batch, now)
    }

    private fun handleBatch(
        key: ProjectionKey,
        lease: ProjectionLease,
        batch: CommittedProjectionBatch,
        now: Instant,
    ): ProjectionPollResult {
        batch.events.forEach { event ->
            try {
                handler.verify(event)
            } catch (exception: ProjectionPoisonException) {
                val poison = repository.poison(key, lease, event, exception.reasonClass, now)
                return poison.toDegradedResult(exception.reasonClass)
            }
        }
        return repository.applyBatch(key, lease, batch.events, now).toPollResult(batch.committedHead)
    }
}

internal interface ProjectionDelay {
    fun schedule(delay: Duration, action: () -> Unit): ProjectionScheduledTask
}

internal interface ProjectionScheduledTask {
    fun cancel()
}

internal class ProjectionPollingLoop(
    private val transactions: ProjectionTransactionRunner,
    private val leases: ProjectionLeaseRepository,
    private val worker: ProjectionWorker,
    private val delay: ProjectionDelay,
    private val clock: () -> Instant,
) {
    fun start(
        key: ProjectionKey,
        ownerDigest: String,
    ): ProjectionLoopHandle {
        val handle = ProjectionLoopHandle()
        scheduleNext(handle, key, ownerDigest, null, MIN_POLL_BACKOFF)
        return handle
    }

    private fun scheduleNext(
        handle: ProjectionLoopHandle,
        key: ProjectionKey,
        ownerDigest: String,
        lease: ProjectionLease?,
        backoff: Duration,
    ) {
        if (handle.cancelled) return
        handle.task =
            delay.schedule(backoff) {
                val outcome = poll(key, ownerDigest, lease)
                if (outcome.continuePolling) {
                    scheduleNext(handle, key, ownerDigest, outcome.lease, outcome.nextBackoff)
                }
            }
    }

    private fun poll(
        key: ProjectionKey,
        ownerDigest: String,
        existingLease: ProjectionLease?,
    ): PollOutcome =
        transactions.inTransaction {
            val now = clock()
            val activeLease = refreshLease(key, ownerDigest, existingLease, now)
            if (activeLease == null) {
                PollOutcome(null, nextBackoff = MAX_POLL_BACKOFF)
            } else {
                worker.poll(key, activeLease, now).toOutcome(activeLease)
            }
        }

    private fun refreshLease(
        key: ProjectionKey,
        ownerDigest: String,
        existingLease: ProjectionLease?,
        now: Instant,
    ): ProjectionLease? =
        existingLease
            ?.let { lease ->
                if (lease.isRenewalDue(now)) {
                    leases.renewLease(key.projection, key.generation, lease, now)
                } else {
                    lease
                }
            }
            ?: leases.acquire(key.projection, key.generation, ownerDigest, now)
}

internal class ProjectionLoopHandle {
    @Volatile
    internal var cancelled: Boolean = false

    @Volatile
    internal var task: ProjectionScheduledTask? = null

    fun cancel() {
        cancelled = true
        task?.cancel()
    }
}

private data class PollOutcome(
    val lease: ProjectionLease?,
    val nextBackoff: Duration,
    val continuePolling: Boolean = true,
)

private fun ProjectionApplyResult.toPollResult(committedHead: Long): ProjectionPollResult.Applied =
    ProjectionPollResult.Applied(
        appliedEventCount = appliedEventCount,
        duplicateEventCount = duplicateEventCount,
        lag = (committedHead - checkpoint.position).coerceAtLeast(0),
    )

private fun ProjectionPoisonRecord.toDegradedResult(reasonClass: String): ProjectionPollResult.Degraded =
    ProjectionPollResult.Degraded(
        eventId = eventId,
        reasonClass = reasonClass,
        attempts = attempts,
        retryable = attempts < MAX_PROJECTION_POISON_ATTEMPTS,
        operatorAction = ProjectionOperatorAction.RETRY_POISON_EVENT,
    )

private fun ProjectionPollResult.toOutcome(lease: ProjectionLease): PollOutcome =
    when (this) {
        is ProjectionPollResult.Applied -> PollOutcome(lease, MIN_POLL_BACKOFF)
        ProjectionPollResult.Idle -> PollOutcome(lease, MAX_POLL_BACKOFF)
        is ProjectionPollResult.Degraded -> PollOutcome(lease, MAX_POLL_BACKOFF, retryable)
    }

private fun List<EventEnvelope>.takeWithinByteCap(): List<EventEnvelope> {
    var accumulated = 0
    return takeWhile { event ->
        accumulated += event.payload.canonicalJson.toByteArray(StandardCharsets.UTF_8).size
        accumulated <= MAX_PROJECTION_BATCH_BYTES
    }
}

internal fun toProjectionEnvelope(row: org.jetbrains.exposed.v1.core.ResultRow): EventEnvelope =
    EventEnvelope(
        eventId = row[EventLog.id].value,
        tenantId = TenantId(row[EventLog.tenantId]),
        stream = StreamReference(row[EventLog.streamType], row[EventLog.streamId], row[EventLog.streamVersion]),
        globalPosition = row[EventLog.globalPosition],
        eventType = row[EventLog.eventType],
        schemaVersion = row[EventLog.schemaVersion],
        occurredAt = row[EventLog.occurredAt],
        recordedAt = row[EventLog.recordedAt],
        correlationId = row[EventLog.correlationId],
        causationId = row[EventLog.causationId],
        actorSurrogate = row[EventLog.actorSurrogate],
        payload = EventPayload(row[EventLog.payload]),
        actorHmacKeyVersion = row[EventLog.actorHmacKeyVersion],
    )

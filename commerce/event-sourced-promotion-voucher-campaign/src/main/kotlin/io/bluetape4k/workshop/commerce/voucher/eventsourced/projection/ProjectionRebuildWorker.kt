package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Instant

internal sealed interface ProjectionRebuildPollResult {
    data class Applied(
        val appliedEventCount: Int,
        val position: Long,
        val lag: Long,
    ) : ProjectionRebuildPollResult

    data object Idle : ProjectionRebuildPollResult

    data object StaleWorker : ProjectionRebuildPollResult

    data class Degraded(
        val reasonClass: String,
    ) : ProjectionRebuildPollResult
}

/**
 * bounded page를 non-active generation으로 replay합니다. generation row lock은 candidate read-model mutation과
 * cursor advance 동안 유지되므로, fencing token이나 cancellation revision이 바뀐 뒤 stale batch가 commit된 채 남지 않습니다.
 */
internal class ProjectionRebuildWorker(
    private val rebuilds: ProjectionRebuildRepository,
    private val projections: ProjectionRepository,
    private val reader: ProjectionEventReader,
    private val handler: ProjectionEventHandler = AcceptingProjectionEventHandler,
) {

    fun poll(
        key: ProjectionKey,
        lease: ProjectionLease,
        now: Instant,
    ): ProjectionRebuildPollResult {
        TransactionManager.current()
        return findGeneration(key)
            ?.let { observed ->
                lockBuildingGeneration(key, observed.fencingToken, observed.cancellationRevision)
                    ?.let { candidate -> pollCandidate(key, lease, candidate, now) }
            }
            ?: ProjectionRebuildPollResult.StaleWorker
    }

    private fun pollCandidate(
        key: ProjectionKey,
        lease: ProjectionLease,
        candidate: ProjectionGeneration,
        now: Instant,
    ): ProjectionRebuildPollResult {
        TransactionManager.current()
        val events = reader.loadAfter(candidate.currentPosition).events.takeUntil(candidate.targetPosition)
        if (events.isEmpty()) return ProjectionRebuildPollResult.Idle
        return verifyEvents(key, lease, events, now) ?: applyCandidate(key, lease, candidate, events, now)
    }

    private fun verifyEvents(
        key: ProjectionKey,
        lease: ProjectionLease,
        events: List<EventEnvelope>,
        now: Instant,
    ): ProjectionRebuildPollResult.Degraded? {
        events.forEach { event ->
            try {
                handler.verify(event)
            } catch (exception: ProjectionPoisonException) {
                projections.poison(key, lease, event, exception.reasonClass, now)
                log.warn {
                    "voucher_rebuild_projection_poisoned projection=${key.projection} generation=${key.generation}"
                }
                return ProjectionRebuildPollResult.Degraded(exception.reasonClass)
            }
        }
        return null
    }

    private fun applyCandidate(
        key: ProjectionKey,
        lease: ProjectionLease,
        candidate: ProjectionGeneration,
        events: List<EventEnvelope>,
        now: Instant,
    ): ProjectionRebuildPollResult.Applied {
        val applied = projections.applyBatch(key, lease, events, now)
        val position = events.last().globalPosition
        check(
            rebuilds.advance(
                key = key,
                cursor =
                    ProjectionRebuildCursor(
                        fencingToken = candidate.fencingToken,
                        cancellationRevision = candidate.cancellationRevision,
                        expectedPosition = candidate.currentPosition,
                        position = position,
                    ),
                now = now,
            ),
        ) { "candidate generation changed while its row lock was held" }
        log.debug { "voucher_rebuild_projection_applied projection=${key.projection} generation=${key.generation}" }
        return ProjectionRebuildPollResult.Applied(
            appliedEventCount = applied.appliedEventCount,
            position = position,
            lag = (candidate.targetPosition - position).coerceAtLeast(0),
        )
    }

    companion object : KLogging()
}

private fun List<EventEnvelope>.takeUntil(targetPosition: Long): List<EventEnvelope> =
    takeWhile { event -> event.globalPosition <= targetPosition }

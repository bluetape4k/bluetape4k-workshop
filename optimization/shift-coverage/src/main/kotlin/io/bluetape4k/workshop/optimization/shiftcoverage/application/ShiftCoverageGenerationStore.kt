package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SnapshotDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class ShiftCoverageGenerationState { REQUESTED, RUNNING, SUCCEEDED, STALE, CANCELLED, FAILED }

data class ShiftCoverageGenerationRecord(
    val generationId: GenerationId,
    val planId: PlanId,
    val aggregateRevision: Long,
    val snapshotDigest: SnapshotDigest,
    val state: ShiftCoverageGenerationState,
    val requestedAt: Instant,
    val completedAt: Instant? = null,
    val failureReason: String? = null,
) {
    val retryable: Boolean get() = state == ShiftCoverageGenerationState.FAILED || state == ShiftCoverageGenerationState.STALE
}

/** generation row의 idempotency와 stale terminal state를 보존하는 deterministic store입니다. */
class ShiftCoverageGenerationStore(
    private val records: ConcurrentHashMap<GenerationId, ShiftCoverageGenerationRecord> = ConcurrentHashMap(),
    private val latestByPlan: ConcurrentHashMap<PlanId, GenerationId> = ConcurrentHashMap(),
) {

    fun request(
        planId: PlanId,
        aggregateRevision: Long,
        snapshotDigest: SnapshotDigest,
        generationId: GenerationId,
        requestedAt: Instant,
    ): ShiftCoverageGenerationRecord {
        require(aggregateRevision >= 0L) { "aggregate revision must be non-negative" }
        records[generationId]?.let { existing ->
            if (existing.planId == planId && existing.aggregateRevision == aggregateRevision && existing.snapshotDigest == snapshotDigest) {
                return existing
            }
            throw IllegalStateException("generation id is already bound to another snapshot")
        }
        val previousId = latestByPlan.put(planId, generationId)
        previousId?.let { previous ->
            records.computeIfPresent(previous) { _, current ->
                if (current.state == ShiftCoverageGenerationState.REQUESTED || current.state == ShiftCoverageGenerationState.RUNNING) {
                    current.copy(state = ShiftCoverageGenerationState.STALE, completedAt = requestedAt, failureReason = "superseded")
                } else current
            }
        }
        val requested = ShiftCoverageGenerationRecord(
            generationId = generationId,
            planId = planId,
            aggregateRevision = aggregateRevision,
            snapshotDigest = snapshotDigest,
            state = ShiftCoverageGenerationState.REQUESTED,
            requestedAt = requestedAt,
        )
        val existing = records.putIfAbsent(generationId, requested)
        return existing ?: requested
    }

    fun start(generationId: GenerationId): ShiftCoverageGenerationRecord = transition(generationId) { current ->
        check(current.state == ShiftCoverageGenerationState.REQUESTED) { "generation is not requestable" }
        current.copy(state = ShiftCoverageGenerationState.RUNNING)
    }

    fun succeed(generationId: GenerationId, completedAt: Instant): ShiftCoverageGenerationRecord = transition(generationId) { current ->
        check(current.state == ShiftCoverageGenerationState.RUNNING) { "generation is not running" }
        current.copy(state = ShiftCoverageGenerationState.SUCCEEDED, completedAt = completedAt, failureReason = null)
    }

    fun fail(generationId: GenerationId, reason: String, completedAt: Instant): ShiftCoverageGenerationRecord = transition(generationId) { current ->
        require(reason.isNotBlank()) { "generation failure reason must not be blank" }
        check(current.state == ShiftCoverageGenerationState.RUNNING) { "generation is not running" }
        current.copy(state = ShiftCoverageGenerationState.FAILED, completedAt = completedAt, failureReason = reason.take(240))
    }

    fun cancel(generationId: GenerationId, completedAt: Instant): ShiftCoverageGenerationRecord = transition(generationId) { current ->
        check(current.state == ShiftCoverageGenerationState.REQUESTED || current.state == ShiftCoverageGenerationState.RUNNING) {
            "generation is not cancellable"
        }
        current.copy(state = ShiftCoverageGenerationState.CANCELLED, completedAt = completedAt)
    }

    fun markStale(generationId: GenerationId, completedAt: Instant, reason: String = "superseded by event"): ShiftCoverageGenerationRecord =
        transition(generationId) { current ->
            if (current.state == ShiftCoverageGenerationState.SUCCEEDED || current.state == ShiftCoverageGenerationState.CANCELLED) {
                current
            } else {
                current.copy(
                    state = ShiftCoverageGenerationState.STALE,
                    completedAt = completedAt,
                    failureReason = reason.take(240),
                )
            }
        }

    fun recoverAfterRestart(recoveredAt: Instant): List<ShiftCoverageGenerationRecord> {
        records.replaceAll { _, current ->
            if (current.state == ShiftCoverageGenerationState.RUNNING) {
                current.copy(
                    state = ShiftCoverageGenerationState.FAILED,
                    completedAt = recoveredAt,
                    failureReason = "process restarted",
                )
            } else current
        }
        return records.values.sortedBy { it.generationId.value }
    }

    fun find(generationId: GenerationId): ShiftCoverageGenerationRecord? = records[generationId]

    fun latest(planId: PlanId): ShiftCoverageGenerationRecord? = latestByPlan[planId]?.let(records::get)

    private fun transition(
        generationId: GenerationId,
        update: (ShiftCoverageGenerationRecord) -> ShiftCoverageGenerationRecord,
    ): ShiftCoverageGenerationRecord {
        lateinit var updated: ShiftCoverageGenerationRecord
        records.compute(generationId) { _, current ->
            val present = checkNotNull(current) { "generation does not exist" }
            updated = update(present)
            updated
        }
        return updated
    }
}

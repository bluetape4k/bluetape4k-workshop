package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SnapshotDigest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ShiftCoverageGenerationStoreTest {

    companion object: KLogging()

    private val store = ShiftCoverageGenerationStore()
    private val planId = PlanId("plan-generation")
    private val digest = SnapshotDigest("a".repeat(64))

    @Test
    fun `same generation request is idempotent and terminal result survives lookup`() {
        val requested = store.request(
            planId = planId,
            aggregateRevision = 3L,
            snapshotDigest = digest,
            generationId = GenerationId("generation-3"),
            requestedAt = Instant.parse("2026-08-24T09:00:00Z")
        )
        val replay = store.request(
            planId = planId,
            aggregateRevision = 3L,
            snapshotDigest = digest,
            generationId = GenerationId("generation-3"),
            requestedAt = Instant.parse("2026-08-24T09:01:00Z")
        )
        requested shouldBeEqualTo replay

        store.start(requested.generationId)

        val succeeded = store.succeed(requested.generationId, Instant.parse("2026-08-24T09:02:00Z"))
        log.debug { "succeeded=$succeeded" }
        succeeded.state shouldBeEqualTo ShiftCoverageGenerationState.SUCCEEDED

        store.find(requested.generationId)?.state shouldBeEqualTo ShiftCoverageGenerationState.SUCCEEDED
    }

    @Test
    fun `newer request stales older generation and failure is retryable`() {
        val old = store.request(
            planId = planId,
            aggregateRevision = 1L,
            snapshotDigest = digest,
            generationId = GenerationId("generation-1"),
            requestedAt = Instant.EPOCH
        )
        store.start(old.generationId)

        val newer = store.request(
            planId = planId,
            aggregateRevision = 2L,
            snapshotDigest = SnapshotDigest("b".repeat(64)),
            generationId = GenerationId("generation-2"),
            requestedAt = Instant.EPOCH
        )

        store.find(old.generationId)?.state shouldBeEqualTo ShiftCoverageGenerationState.STALE
        store.start(newer.generationId)
        store.fail(newer.generationId, "provider timeout", Instant.parse("2026-08-24T09:03:00Z"))
        store.find(newer.generationId)?.retryable.shouldBeTrue()
    }

    @Test
    fun `restart recovers running generation into retryable failure`() {
        val records = ConcurrentHashMap<GenerationId, ShiftCoverageGenerationRecord>()
        val latest = ConcurrentHashMap<PlanId, GenerationId>()
        val first = ShiftCoverageGenerationStore(records, latest)

        val generation = first.request(
            planId = planId,
            aggregateRevision = 4L,
            snapshotDigest = digest,
            generationId = GenerationId("generation-restart"),
            requestedAt = Instant.EPOCH
        )
        first.start(generation.generationId)

        val restarted = ShiftCoverageGenerationStore(records, latest)
        restarted.recoverAfterRestart(Instant.parse("2026-08-24T09:04:00Z"))

        restarted.find(generation.generationId)?.state shouldBeEqualTo ShiftCoverageGenerationState.FAILED
        restarted.find(generation.generationId)?.retryable.shouldBeTrue()
    }

    @Test
    fun `restart sweep only fails in-flight generations and is idempotent`() {
        val records = ConcurrentHashMap<GenerationId, ShiftCoverageGenerationRecord>()
        val latest = ConcurrentHashMap<PlanId, GenerationId>()
        val first = ShiftCoverageGenerationStore(records, latest)
        val requestedPlan = PlanId("plan-requested")

        val requested = first.request(
            planId = requestedPlan,
            aggregateRevision = 5L,
            snapshotDigest = digest,
            generationId = GenerationId("generation-requested"),
            requestedAt = Instant.EPOCH
        )
        val running = first.request(
            planId = PlanId("plan-running"),
            aggregateRevision = 6L,
            snapshotDigest = digest,
            generationId = GenerationId("generation-running"),
            requestedAt = Instant.EPOCH
        )
        first.start(running.generationId)
        val succeeded = first.request(
            planId = PlanId("plan-succeeded"),
            aggregateRevision = 7L,
            snapshotDigest = digest,
            generationId = GenerationId("generation-succeeded"),
            requestedAt = Instant.EPOCH
        )
        first.start(succeeded.generationId)
        first.succeed(succeeded.generationId, Instant.parse("2026-08-24T09:05:00Z"))

        val cancelled = first.request(
            planId = PlanId("plan-cancelled"),
            aggregateRevision = 8L,
            snapshotDigest = digest,
            generationId = GenerationId("generation-cancelled"),
            requestedAt = Instant.EPOCH
        )
        first.cancel(cancelled.generationId, Instant.parse("2026-08-24T09:06:00Z"))

        val restarted = ShiftCoverageGenerationStore(records, latest)
        val recoveredAt = Instant.parse("2026-08-24T09:07:00Z")
        val recovered = restarted.recoverAfterRestart(recoveredAt)
        val secondPass = restarted.recoverAfterRestart(recoveredAt + 1.seconds())

        recovered.map { it.generationId.value } shouldBeEqualTo recovered.map { it.generationId.value }.sorted()

        restarted.find(requested.generationId)?.state shouldBeEqualTo ShiftCoverageGenerationState.REQUESTED
        restarted.find(running.generationId)?.state shouldBeEqualTo ShiftCoverageGenerationState.FAILED
        restarted.find(running.generationId)?.completedAt shouldBeEqualTo recoveredAt
        restarted.find(succeeded.generationId)?.state shouldBeEqualTo ShiftCoverageGenerationState.SUCCEEDED
        restarted.find(cancelled.generationId)?.state shouldBeEqualTo ShiftCoverageGenerationState.CANCELLED

        secondPass.map { it.state } shouldBeEqualTo recovered.map { it.state }
        restarted.find(running.generationId)?.completedAt shouldBeEqualTo recoveredAt
    }
}

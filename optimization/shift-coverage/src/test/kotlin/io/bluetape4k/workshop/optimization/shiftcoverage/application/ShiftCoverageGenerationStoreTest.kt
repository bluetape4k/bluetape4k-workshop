package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SnapshotDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Test

class ShiftCoverageGenerationStoreTest {
    private val store = ShiftCoverageGenerationStore()
    private val planId = PlanId("plan-generation")
    private val digest = SnapshotDigest("a".repeat(64))

    @Test
    fun `same generation request is idempotent and terminal result survives lookup`() {
        val requested = store.request(planId, 3L, digest, GenerationId("generation-3"), Instant.parse("2026-08-24T09:00:00Z"))
        val replay = store.request(planId, 3L, digest, GenerationId("generation-3"), Instant.parse("2026-08-24T09:01:00Z"))
        requested shouldBeEqualTo replay

        store.start(requested.generationId)
        val succeeded = store.succeed(requested.generationId, Instant.parse("2026-08-24T09:02:00Z"))
        succeeded.state shouldBeEqualTo ShiftCoverageGenerationState.SUCCEEDED
        store.find(requested.generationId)?.state shouldBeEqualTo ShiftCoverageGenerationState.SUCCEEDED
    }

    @Test
    fun `newer request stales older generation and failure is retryable`() {
        val old = store.request(planId, 1L, digest, GenerationId("generation-1"), Instant.EPOCH)
        store.start(old.generationId)
        val newer = store.request(planId, 2L, SnapshotDigest("b".repeat(64)), GenerationId("generation-2"), Instant.EPOCH)

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
        val generation = first.request(planId, 4L, digest, GenerationId("generation-restart"), Instant.EPOCH)
        first.start(generation.generationId)

        val restarted = ShiftCoverageGenerationStore(records, latest)
        restarted.recoverAfterRestart(Instant.parse("2026-08-24T09:04:00Z"))

        restarted.find(generation.generationId)?.state shouldBeEqualTo ShiftCoverageGenerationState.FAILED
        restarted.find(generation.generationId)?.retryable.shouldBeTrue()
    }
}

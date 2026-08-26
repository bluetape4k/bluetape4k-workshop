package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageProvider
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.CoverageScore
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageEventType
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlanProposal
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SnapshotDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

class ShiftCoverageEventServiceTest {
    @Test
    fun `first source event stales plan and generation while duplicate is no-op`() {
        val planId = PlanId("plan-event")
        val generationId = GenerationId("generation-event")
        val plans = ShiftCoveragePlanStore().also {
            it.save(
                StoredShiftCoveragePlan(
                    ShiftCoveragePlanProposal(
                        planId = planId,
                        generationId = generationId,
                        revision = 3L,
                        siteId = SiteId("site-event"),
                        assignments = emptyList(),
                        unassigned = emptyList(),
                        score = CoverageScore(),
                        candidateEvaluations = 0,
                        snapshotDigest = SnapshotDigest("a".repeat(64)),
                    ),
                ),
            )
        }
        val generations = ShiftCoverageGenerationStore().also {
            it.request(planId, 3L, SnapshotDigest("a".repeat(64)), generationId, Instant.EPOCH)
            it.start(generationId)
        }
        val service = ShiftCoverageEventService(
            inbox = ShiftCoverageInboxService(),
            plans = plans,
            generations = generations,
            clock = Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC),
        )
        val event = ShiftCoverageDomainEvent(
            type = ShiftCoverageEventType.AVAILABILITY_CHANGED,
            provider = ShiftCoverageProvider.FAKE,
            eventId = EventId("event-1"),
            digest = "b".repeat(64),
            providerRevision = 4L,
            planId = planId,
            planRevision = 3L,
            generationId = generationId,
        )

        service.receive(event).apply {
            planStaled.shouldBeTrue()
            generationStaled.shouldBeTrue()
        }
        plans.find(planId, 3L)?.state shouldBeEqualTo ShiftCoveragePlanState.STALE
        generations.find(generationId)?.state shouldBeEqualTo ShiftCoverageGenerationState.STALE

        service.receive(event).apply {
            planStaled shouldBeEqualTo false
            generationStaled shouldBeEqualTo false
        }
    }
}

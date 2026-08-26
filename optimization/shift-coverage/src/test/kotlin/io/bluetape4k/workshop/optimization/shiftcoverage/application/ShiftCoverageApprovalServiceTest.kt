package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlannedShift
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlanProposal
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageRepository
import org.junit.jupiter.api.Test

class ShiftCoverageApprovalServiceTest {
    @Test
    fun `approval materializes only a draft revision and stale approval is no-write`() {
        val repository = ShiftCoverageRepository()
        val plans = ShiftCoveragePlanStore()
        val service = ShiftCoverageApprovalService(plans, repository)
        val proposal = ShiftCoveragePlanProposal(
            planId = PlanId("plan-a"), generationId = GenerationId("generation-a"), revision = 3L,
            siteId = SiteId("site-a"), assignments = listOf(PlannedShift(ShiftId("shift-a"), WorkerId("worker-a"), AssignmentId("assignment-a"), false)),
            unassigned = emptyList(), score = io.bluetape4k.workshop.optimization.shiftcoverage.domain.CoverageScore(1), candidateEvaluations = 1,
        )
        plans.save(StoredShiftCoveragePlan(proposal))

        service.approve(PlanId("plan-a"), 3L).shouldBeTrue()
        repository.findAssignment(AssignmentId("assignment-a"))?.workerId shouldBeEqualTo WorkerId("worker-a")
        service.approve(PlanId("plan-a"), 3L).shouldBeFalse()
        repository.findAssignment(AssignmentId("assignment-a"))?.revision shouldBeEqualTo 0L
    }
}

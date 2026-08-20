package io.bluetape4k.workshop.optimization.fieldservice.planner

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.workshop.optimization.fieldservice.domain.AvailabilityWindow
import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Skill
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test

class PlannerComplexityContractTest {
    @Test
    fun `max envelope stays within candidate and matrix lookup bounds`() {
        val coordinateIds = (0 until FieldServiceLimits.MAX_COORDINATES).map { CoordinateId("c-$it") }
        val matrix = TravelTimeMatrix(
            revision = 1,
            coordinateIds = coordinateIds.toSet(),
            edges = buildMap {
                coordinateIds.forEach { from ->
                    coordinateIds.forEach { to ->
                        put(CoordinatePair(from, to), if (from == to) 0L else 1L)
                    }
                }
            },
        )
        val start = Instant.parse("2026-08-20T00:00:00Z")
        val end = start.plus(Duration.ofDays(1))
        val skill = Skill("skill")
        val workers = (0 until FieldServiceLimits.MAX_WORKERS).map { index ->
            Worker(
                workerId = WorkerId("worker-$index"),
                name = "Synthetic worker-$index",
                skills = setOf(skill),
                availability = listOf(AvailabilityWindow(start, end)),
                homeCoordinateId = coordinateIds[index],
            )
        }
        val visits = (0 until FieldServiceLimits.MAX_VISITS).map { index ->
            Visit(
                visitId = VisitId("visit-$index"),
                coordinateId = coordinateIds[index % coordinateIds.size],
                requiredSkill = skill,
                windowStart = start,
                windowEnd = end,
                serviceDuration = Duration.ZERO,
            )
        }

        val result = DeterministicFieldServicePlanner().planWithMetrics(
            PlannerInput(workers, visits, matrix, DatasetId("dataset"), PlanId("plan"), planRevision = 1, requestGeneration = 1),
        )

        result.metrics.candidateEvaluations shouldBeLessOrEqualTo FieldServiceLimits.MAX_VISITS * FieldServiceLimits.MAX_WORKERS
        result.metrics.matrixLookups shouldBeLessOrEqualTo FieldServiceLimits.MAX_VISITS * FieldServiceLimits.MAX_WORKERS + FieldServiceLimits.MAX_SPARSE_EDGES
        result.metrics.externalCalls shouldBeEqualTo 0
        result.metrics.invariants shouldBeEqualTo listOf("O(V*W+E)")
    }
}

package io.bluetape4k.workshop.optimization.fieldservice

import io.bluetape4k.workshop.optimization.fieldservice.domain.AvailabilityWindow
import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Skill
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import io.bluetape4k.workshop.optimization.fieldservice.planner.CoordinatePair
import io.bluetape4k.workshop.optimization.fieldservice.planner.DeterministicFieldServicePlanner
import io.bluetape4k.workshop.optimization.fieldservice.planner.PlannerInput
import io.bluetape4k.workshop.optimization.fieldservice.planner.TravelTimeMatrix
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 외부 benchmark plugin 없이 max-envelope invariant report를 만드는 probe입니다. */
object FieldServiceBenchmarkProbe {
    private val start = Instant.parse("2026-08-20T09:00:00Z")
    private val end = Instant.parse("2026-08-20T18:00:00Z")

    fun run(output: Path = Path.of("build/reports/field-service/benchmark.json")): Path {
        val input = fixture()
        val planner = DeterministicFieldServicePlanner()
        repeat(2) { planner.planWithMetrics(input) }
        val runs = (1..5).map { planner.planWithMetrics(input) }
        val metrics = runs.map { it.metrics }
        val invariantsPass = metrics.all {
            it.candidateEvaluations <= input.visits.size * input.workers.size &&
                it.externalCalls == 0 && it.invariants == listOf("O(V*W+E)")
        }
        Files.createDirectories(output.parent)
        Files.writeString(
            output,
            """{
              "schemaVersion": 1,
              "runId": "${UUID.randomUUID()}",
              "fixture": {"workers": 100, "visits": 500, "matrixCells": 10000},
              "warmup": 2,
              "repetitions": 5,
              "queryCount": ${metrics.sumOf { it.candidateEvaluations + it.matrixLookups }},
              "lockWaitMs": null,
              "queueRejected": null,
              "timeout": null,
              "cancellation": null,
              "requestCount": ${runs.size},
              "notModifiedRatio": null,
              "unavailableMetrics": ["lockWaitMs", "queueRejected", "timeout", "cancellation", "notModifiedRatio"],
              "inputBytes": ${input.visits.size * 128L + input.workers.size * 128L},
              "responseBytes": ${runs.sumOf { it.proposal.score.assignedCount * 64L }},
              "invariants": {"candidateEvaluationsMax": ${metrics.maxOf { it.candidateEvaluations }}, "externalCalls": ${metrics.sumOf { it.externalCalls }}, "complexity": "O(V*W+E)"},
              "status": "${if (invariantsPass) "PASS" else "FAIL"}"
            }
            """.trimIndent(),
        )
        check(invariantsPass) { "field-service benchmark invariant failed" }
        return output
    }

    private fun fixture(): PlannerInput {
        val coordinates = (0 until 100).map { CoordinateId("coordinate-$it") }.toSet()
        val edges = buildMap {
            coordinates.forEach { from -> coordinates.forEach { to -> put(CoordinatePair(from, to), 60L) } }
        }
        val workers = (0 until 100).map { index ->
            Worker(
                workerId = WorkerId("worker-$index"),
                name = "Synthetic worker $index",
                skills = (0 until 20).mapTo(linkedSetOf()) { Skill("skill-$it") },
                availability = (0 until 20).map { AvailabilityWindow(start.plus(Duration.ofMinutes(it.toLong() * 20)), end) },
                homeCoordinateId = coordinates.elementAt(index),
            )
        }
        val visits = (0 until 500).map { index ->
            Visit(
                visitId = VisitId("visit-$index"),
                coordinateId = coordinates.elementAt(index % coordinates.size),
                requiredSkill = Skill("skill-${index % 20}"),
                windowStart = start,
                windowEnd = end,
                serviceDuration = Duration.ofMinutes(1),
            )
        }
        return PlannerInput(
            workers = workers,
            visits = visits,
            matrix = TravelTimeMatrix(1L, coordinates, edges),
            datasetId = DatasetId("benchmark-dataset"),
            planId = io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId("benchmark-plan"),
        )
    }
}

package io.bluetape4k.workshop.optimization.planning.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.optimization.planning.adapter.fake.DeterministicPlanningEngine
import org.junit.jupiter.api.Test
import java.util.*

class PlanningEngineContractTest {

    companion object: KLogging()

    @Test
    fun `deterministic engine returns the same normalized result for the same request`() {
        val request = PlanningSubmission(
            requestId = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b"),
            datasetId = DatasetId("dataset-2026-07-18"),
            aggregate = AggregateVersion(AggregateId("schedule-42"), version = 7),
            parentRevision = PlanningRevision(3),
        )
        val engine: PlanningEngine = DeterministicPlanningEngine()

        val first = engine.submit(request)
        val second = engine.submit(request)

        log.debug { "first=$first" }
        second shouldBeEqualTo first
        first.status shouldBeEqualTo PlanningStatus.SUBMITTED

        engine.status(first.providerRequestId)?.status shouldBeEqualTo PlanningStatus.SUCCEEDED
    }

    @Test
    fun `contract values reject negative revisions and oversized explanations`() {
        assertFailsWith<IllegalArgumentException> {
            PlanningRevision(-1)
        }

        assertFailsWith<IllegalArgumentException> {
            PlanningResult(
                requestId = UUID.randomUUID(),
                providerRequestId = ProviderRequestId("provider-42"),
                revision = PlanningRevision(1),
                status = PlanningStatus.SUCCEEDED,
                scoreSummary = "0hard",
                constraintExplanations = listOf("x".repeat(241)),
            )
        }
    }
}

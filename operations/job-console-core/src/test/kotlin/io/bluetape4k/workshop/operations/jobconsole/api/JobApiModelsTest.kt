package io.bluetape4k.workshop.operations.jobconsole.api

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class JobApiModelsTest {

    private val mapper = Jackson.defaultJsonMapper

    @Test
    fun `snapshot JSON exposes stable queue and ETA fields without caller identity`() {
        val snapshot =
            JobSnapshot(
                jobId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab"),
                jobType = JobType.DOCUMENT_EXPORT,
                state = JobState.QUEUED,
                progress = 0,
                checkpoint = null,
                queue =
                    QueueProjection(
                        position = 3,
                        jobsAhead = 2,
                        estimatedStartRange = TimeRange(Instant.parse("2026-07-21T00:00:10Z"), Instant.parse("2026-07-21T00:00:20Z")),
                        estimatedCompletionRange = TimeRange(Instant.parse("2026-07-21T00:00:30Z"), Instant.parse("2026-07-21T00:00:50Z")),
                        confidence = EtaConfidence.MEDIUM,
                        sampleSize = 12,
                        queueVersion = 7,
                        updatedAt = Instant.parse("2026-07-21T00:00:00Z"),
                    ),
                version = 1,
                updatedAt = Instant.parse("2026-07-21T00:00:00Z"),
            )

        val tree = mapper.readTree(mapper.writeValueAsString(snapshot))

        tree["jobId"].asString() shouldBeEqualTo snapshot.jobId.toString()
        tree["queue"]["jobsAhead"].asInt() shouldBeEqualTo 2
        tree["queue"]["confidence"].asString() shouldBeEqualTo "medium"
        tree.has("tenantId") shouldBeEqualTo false
        tree.has("submitterId") shouldBeEqualTo false
        tree.has("idempotencyKey") shouldBeEqualTo false
    }

    @Test
    fun `problem response carries a stable code without internal exception detail`() {
        val problem =
            JobProblem(
                status = 409,
                code = JobProblemCode.IDEMPOTENCY_KEY_REUSED,
                title = "Idempotency key reused",
                requestId = "request-1",
            )

        val tree = mapper.readTree(mapper.writeValueAsString(problem))

        tree["code"].asString() shouldBeEqualTo "idempotency_key_reused"
        tree.has("stackTrace") shouldBeEqualTo false
        tree.has("exception") shouldBeEqualTo false
    }

    @Test
    fun `submit request is a closed deterministic workload contract`() {
        val request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, workUnits = 10, failureMode = FailureMode.NONE)

        request.workUnits shouldBeEqualTo 10
        request.failureMode shouldBeEqualTo FailureMode.NONE
    }
}

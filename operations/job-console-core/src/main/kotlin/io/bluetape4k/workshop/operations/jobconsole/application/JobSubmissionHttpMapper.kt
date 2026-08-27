package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.operations.jobconsole.api.JobProblem
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionHttpResponse
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionOutcome
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode

/** Maps the core submission result to the single wire contract consumed by both adapters. */
object JobSubmissionHttpMapper {
    private val mapper = Jackson.defaultJsonMapper

    fun map(
        outcome: JobSubmissionOutcome,
        requestId: String = Uuid.V7.nextId().toString(),
    ): JobSubmissionHttpResponse =
        when (outcome) {
            is JobSubmissionOutcome.OwnerCompleted -> snapshotResponse(outcome.snapshot, outcome.responseHeaders, replayed = false)
            is JobSubmissionOutcome.Replayed -> snapshotResponse(outcome.snapshot, outcome.responseHeaders, replayed = true)
            JobSubmissionOutcome.Conflict -> problem(JobProblemCode.IDEMPOTENCY_KEY_REUSED, 409, "Conflict", requestId)
            JobSubmissionOutcome.InFlightTimeout ->
                problem(JobProblemCode.IDEMPOTENCY_IN_FLIGHT, 409, "Conflict", requestId, retryAfterSeconds = 1)
            JobSubmissionOutcome.WaiterOverflow ->
                problem(JobProblemCode.IDEMPOTENCY_WAITERS_EXCEEDED, 429, "Too Many Requests", requestId, retryAfterSeconds = 2)
            JobSubmissionOutcome.Abandoned ->
                problem(JobProblemCode.DEPENDENCY_UNAVAILABLE, 503, "Service Unavailable", requestId)
        }

    fun problem(
        code: JobProblemCode,
        status: Int,
        title: String,
        requestId: String = Uuid.V7.nextId().toString(),
        retryAfterSeconds: Long? = null,
    ): JobSubmissionHttpResponse {
        val body = mapper.writeValueAsBytes(JobProblem(status, code, title, requestId, retryAfterSeconds))
        val headers = retryAfterSeconds?.let { mapOf("Retry-After" to listOf(it.toString())) } ?: emptyMap()
        return JobSubmissionHttpResponse(
            status = status,
            body = body,
            contentType = "application/problem+json",
            headers = headers,
            replayed = false,
        )
    }

    private fun snapshotResponse(
        snapshot: io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot,
        responseHeaders: Map<String, List<String>>,
        replayed: Boolean,
    ): JobSubmissionHttpResponse =
        JobSubmissionHttpResponse(
            status = 202,
            body = mapper.writeValueAsBytes(snapshot),
            contentType = "application/json",
            headers = responseHeaders + ("Idempotency-Replayed" to listOf(replayed.toString())),
            replayed = replayed,
        )
}

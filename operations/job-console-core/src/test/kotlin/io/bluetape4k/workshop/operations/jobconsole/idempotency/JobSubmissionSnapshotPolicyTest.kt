package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import org.junit.jupiter.api.Test

class JobSubmissionSnapshotPolicyTest {

    @Test
    fun `empty response header set is the only default replay header contract`() {
        val prepared = prepared(body = "accepted".toByteArray())
        val validated = JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy()).validate(prepared)

        validated.responseStatus shouldBeEqualTo 202
        validated.responseContentType shouldBeEqualTo "application/json"
        validated.responseHeaders shouldBeEqualTo emptyMap()
        validated.responseBody shouldBeEqualTo "accepted".toByteArray()
    }

    @Test
    fun `sensitive reserved demo and arbitrary headers are rejected`() {
        val headers = listOf(
            mapOf("authorization" to listOf("Bearer secret")),
            mapOf("x-auth-token" to listOf("secret")),
            mapOf("client-secret" to listOf("secret")),
            mapOf("X-Demo-Id" to listOf("demo")),
            mapOf("x-safe" to listOf("value")),
        )

        headers.forEach { responseHeaders ->
            assertFailsWith<IllegalArgumentException> {
                JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy()).validate(
                    prepared(responseHeaders = responseHeaders),
                )
            }
        }
    }

    @Test
    fun `status content type body count and control character bounds fail before persistence`() {
        val policy = JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy(maxReplayBytes = 4))

        assertFailsWith<IllegalArgumentException> {
            JobSubmissionIdempotencyPolicy(statementTimeout = java.time.Duration.ofNanos(1))
        }
        assertFailsWith<IllegalArgumentException> { policy.validate(prepared(responseStatus = 99)) }
        assertFailsWith<IllegalArgumentException> { policy.validate(prepared(body = "12345".toByteArray())) }
        assertFailsWith<IllegalArgumentException> { policy.validate(prepared(contentType = "text/plain")) }
        assertFailsWith<IllegalArgumentException> {
            policy.validate(prepared(responseHeaders = mapOf("x-demo" to listOf("line\nfeed"))))
        }
        assertFailsWith<IllegalArgumentException> {
            policy.validate(prepared(responseHeaders = (1..9).associate { "x-safe-$it" to listOf("value") }))
        }
    }

    @Test
    fun `production snapshots are 202 json while synthetic fixtures may use bounded statuses`() {
        val production = JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy())
        assertFailsWith<IllegalArgumentException> { production.validate(prepared(responseStatus = 201)) }
        assertFailsWith<IllegalArgumentException> {
            production.validate(prepared(responseStatus = 422, contentType = "application/problem+json"))
        }

        val synthetic = JobSubmissionSnapshotPolicy.syntheticForTests(JobSubmissionIdempotencyPolicy())
        synthetic.validate(prepared(responseStatus = 201))
        synthetic.validate(prepared(responseStatus = 422, contentType = "application/problem+json"))
        assertFailsWith<IllegalArgumentException> { synthetic.validate(prepared(responseStatus = 200)) }
    }

    @Test
    fun `header value control and size bounds are checked before replay allowlist`() {
        val policy = JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy())

        val control = assertFailsWith<IllegalArgumentException> {
            policy.validate(prepared(responseHeaders = mapOf("x-safe" to listOf("line\nfeed"))))
        }
        check(control.message == "response header value contains a control character")
        val count = assertFailsWith<IllegalArgumentException> {
            policy.validate(prepared(responseHeaders = mapOf("x-safe" to (1..5).map { "value" })))
        }
        check(count.message == "response header value count exceeds replay limit")
        val value = assertFailsWith<IllegalArgumentException> {
            policy.validate(prepared(responseHeaders = mapOf("x-safe" to listOf("x".repeat(4 * 1024 + 1)))))
        }
        check(value.message == "response header value exceeds replay limit")
        val aggregatePolicy = JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy(maxAggregateHeaderBytes = 8))
        val aggregate = assertFailsWith<IllegalArgumentException> {
            aggregatePolicy.validate(prepared(responseHeaders = mapOf("x-safe" to listOf("value"))))
        }
        check(aggregate.message == "response headers exceed aggregate replay limit")
    }

    private fun prepared(
        responseStatus: Int = 202,
        body: ByteArray = "ok".toByteArray(),
        contentType: String = "application/json",
        responseHeaders: Map<String, List<String>> = emptyMap(),
    ): PreparedJobSubmission =
        PreparedJobSubmission(
            request = SubmitJobRequest(io.bluetape4k.workshop.operations.jobconsole.api.JobType.DOCUMENT_EXPORT, 1),
            responseStatus = responseStatus,
            responseBody = body,
            responseContentType = contentType,
            responseHeaders = responseHeaders,
        )
}

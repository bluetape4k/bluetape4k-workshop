package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class JobConsoleSpringSubmissionHttpTest {
    @Test
    fun `scope and key are validated before bounded JSON parsing`() {
        val request = request("{}", key = "bad,key")

        assertFailsWith<JobSubmissionInvalidRequestException> {
            JobConsoleSpringSubmissionHttp.idempotencyKey(request)
        }
    }

    @Test
    fun `valid request uses strict JSON and UTF-8 content type`() {
        val request = request(
            body = """{"jobType":"document_export","workUnits":3,"failureMode":"none"}""",
            key = "spring-key",
        )

        val parsed = JobConsoleSpringSubmissionHttp.readSubmitRequest(request)
        parsed.jobType shouldBeEqualTo JobType.DOCUMENT_EXPORT
        parsed.workUnits shouldBeEqualTo 3
        parsed.failureMode shouldBeEqualTo FailureMode.NONE
    }

    @Test
    fun `duplicate unknown and trailing JSON are rejected`() {
        listOf(
            """{"jobType":"document_export","jobType":"report_generation","workUnits":3}""",
            """{"jobType":"document_export","workUnits":3,"unexpected":true}""",
            """{"jobType":"document_export","workUnits":3} {}""",
        ).forEach { body ->
            assertFailsWith<JobSubmissionInvalidRequestException> { JobConsoleSpringSubmissionHttp.readSubmitRequest(request(body, "spring-key")) }
        }
    }

    @Test
    fun `body over 64 KiB returns the dedicated size failure`() {
        val body = "{" + "\"jobType\":\"document_export\",\"workUnits\":3,\"failureMode\":\"none\",\"padding\":\"" +
            "x".repeat(MAX_JOB_SUBMISSION_BODY_BYTES) + "\"}"

        assertFailsWith<JobSubmissionRequestTooLargeException> {
            JobConsoleSpringSubmissionHttp.readSubmitRequest(request(body, "spring-key"))
        }
    }

    @Test
    fun `missing caller scope is denied before body access`() {
        val request = request("not-json", "spring-key")
        request.removeHeader("X-Demo-Tenant")

        assertFailsWith<JobSubmissionScopeDeniedException> {
            JobConsoleSpringSubmissionHttp.scope(request)
        }
    }

    private fun request(body: String, key: String): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            addHeader("X-Demo-Tenant", "tenant-a")
            addHeader("X-Demo-Submitter", "submitter-a")
            addHeader("Idempotency-Key", key)
            contentType = "application/json; charset=UTF-8"
            setContent(body.toByteArray(Charsets.UTF_8))
        }
}

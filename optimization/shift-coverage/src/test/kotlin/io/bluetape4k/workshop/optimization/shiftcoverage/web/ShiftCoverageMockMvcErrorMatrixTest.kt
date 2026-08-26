package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageDemoService
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageInboxService
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageOutboxStore
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageLimits
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders

import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ShiftCoverageMockMvcErrorMatrixTest {
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(ShiftCoverageController(ShiftCoverageDemoService()))
            .setControllerAdvice(ShiftCoverageExceptionHandler())
            .build()
    }

    @Test
    fun `missing idempotency header is stable redacted bad request`() {
        mvc.perform(
            post("/api/shift-coverage/replans")
                .header("X-Demo-Operator", "manager-demo")
                .header("X-Demo-Role", "manager"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
            .andExpect(jsonPath("$.nextAction").value("FIX_REQUEST"))
            .andExpect(jsonPath("$.requestId").exists())
            .andExpect(jsonPath("$.exception").doesNotExist())
    }

    @Test
    fun `hostile origin is rejected before query`() {
        mvc.perform(
            get("/api/shift-coverage/plans")
                .header("Origin", "http://localhost.evil")
                .header("X-Demo-Operator", "manager-demo")
                .header("X-Demo-Role", "manager"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ORIGIN_FORBIDDEN"))
            .andExpect(jsonPath("$.nextAction").value("USE_SAME_ORIGIN"))
    }

    @Test
    fun `worker cannot submit another subject in swap command`() {
        mvc.perform(
            post("/api/shift-coverage/swaps")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceWorkerId\":\"worker-b\",\"targetWorkerId\":\"worker-a\"}")
                .header("X-Demo-Operator", "worker-a-demo")
                .header("X-Demo-Role", "worker")
                .header("Idempotency-Key", "swap-subject-1"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("DEMO_SUBJECT_MISMATCH"))
    }

    @Test
    fun `non-loopback remote address is rejected before query`() {
        val controller = ShiftCoverageController(ShiftCoverageDemoService())
        val request = org.springframework.mock.web.MockHttpServletRequest().apply {
            remoteAddr = "192.0.2.10"
            addHeader("X-Demo-Operator", "manager-demo")
            addHeader("X-Demo-Role", "manager")
        }

        assertFailsWith<ShiftCoverageHttpException> { controller.plans(request) }
            .code shouldBeEqualTo "LOOPBACK_REQUIRED"
    }

    @Test
    fun `local origin is accepted while hostile origin is rejected`() {
        mvc.perform(
            get("/api/shift-coverage/plans")
                .header("Origin", "http://localhost:8080")
                .header("X-Demo-Operator", "manager-demo")
                .header("X-Demo-Role", "manager"),
        )
            .andExpect(status().isOk)

        mvc.perform(
            get("/api/shift-coverage/plans")
                .header("Origin", "https://localhost")
                .header("X-Demo-Operator", "manager-demo")
                .header("X-Demo-Role", "manager"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ORIGIN_FORBIDDEN"))
    }

    @Test
    fun `callback malformed and oversized bodies map to stable redacted errors`() {
        val callbackMvc = MockMvcBuilders.standaloneSetup(
            ShiftCoverageCallbackController(ShiftCoverageInboxService()),
        ).setControllerAdvice(ShiftCoverageExceptionHandler()).build()

        callbackMvc.perform(
            post("/api/shift-coverage/callbacks/FAKE")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"unknown\"}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
            .andExpect(jsonPath("$.nextAction").value("FIX_REQUEST"))
            .andExpect(jsonPath("$.exception").doesNotExist())

        callbackMvc.perform(
            post("/api/shift-coverage/callbacks/FAKE")
                .content(ByteArray(ShiftCoverageLimits.MAX_BODY_BYTES + 1) { 'x'.code.toByte() }),
        )
            .andExpect(status().`is`(413))
            .andExpect(jsonPath("$.code").value("RESPONSE_TOO_LARGE"))
            .andExpect(jsonPath("$.nextAction").value("SHRINK_INPUT"))
    }

    @Test
    fun `retryable replan rejection exposes bounded retry contract`() {
        val request = org.springframework.mock.web.MockHttpServletRequest().apply {
            addHeader("X-Request-Id", "request-retry")
        }

        val response = ShiftCoverageExceptionHandler().handle(
            ShiftCoverageHttpException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "REPLAN_REJECTED", true),
            request,
        )

        response.statusCode.value() shouldBeEqualTo 429
        response.headers.getFirst("Retry-After") shouldBeEqualTo "1"
        response.body?.retryable shouldBeEqualTo true
        response.body?.retryAfter shouldBeEqualTo 1L
        response.body?.nextAction shouldBeEqualTo "RETRY_AFTER"
    }

    @Test
    fun `operator hostile origin is rejected before inbox or outbox mutation`() {
        val operator = ShiftCoverageOperatorController(ShiftCoverageInboxService(), ShiftCoverageOutboxStore())
        val request = org.springframework.mock.web.MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
            addHeader("Origin", "http://localhost.evil")
        }

        val failure = io.bluetape4k.assertions.assertFailsWith<ShiftCoverageHttpException> {
            operator.requeue(request, "FAKE", "event-1", "manager-demo", "manager", "operator reason")
        }
        failure.code shouldBeEqualTo "ORIGIN_FORBIDDEN"
    }
}

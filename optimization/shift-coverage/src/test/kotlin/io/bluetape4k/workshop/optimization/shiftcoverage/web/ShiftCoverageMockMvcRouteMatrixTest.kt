package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageDemoService
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageInboxService
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageOutboxStore
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageProvider
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import java.security.MessageDigest
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ShiftCoverageMockMvcRouteMatrixTest {
    private lateinit var mvc: MockMvc
    private lateinit var inbox: ShiftCoverageInboxService

    @BeforeEach
    fun setUp() {
        val service = ShiftCoverageDemoService()
        inbox = ShiftCoverageInboxService()
        mvc = MockMvcBuilders.standaloneSetup(
            ShiftCoverageController(service),
            ShiftCoverageConsoleController(),
            ShiftCoverageCallbackController(inbox),
            ShiftCoverageOperatorController(inbox, ShiftCoverageOutboxStore()),
        ).setControllerAdvice(ShiftCoverageExceptionHandler()).build()
    }

    @Test
    fun `console directory route redirects to the safe static entrypoint`() {
        mvc.perform(get("/shift-coverage/"))
            .andExpect(status().isFound)
            .andExpect(header().string("Location", "/shift-coverage/index.html"))
    }

    @Test
    fun `manager replan query approve and stale approval follow the golden matrix`() {
        mvc.perform(
            post("/api/shift-coverage/replans")
                .manager("route-manager-1", "route-request-1"),
        )
            .andExpect(status().isAccepted)
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.revision").value(1))
            .andExpect(jsonPath("$.requestId").value("route-request-1"))

        mvc.perform(get("/api/shift-coverage/plans").manager())
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
            .andExpect(jsonPath("$[0].revision").value(1))
            .andExpect(jsonPath("$[0].siteId").value("site-demo"))
            .andExpect(jsonPath("$[0].assignments").value(1))

        mvc.perform(get("/api/shift-coverage/plans").worker("worker-a-demo"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].assignments").value(1))

        mvc.perform(
            post("/api/shift-coverage/plans/1/approve")
                .manager("approve-1", null),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approved").value(true))

        mvc.perform(
            post("/api/shift-coverage/replans")
                .manager("route-manager-2", "route-request-2"),
        ).andExpect(status().isAccepted)

        mvc.perform(
            post("/api/shift-coverage/plans/1/approve")
                .manager("approve-stale-1", null),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
            .andExpect(jsonPath("$.nextAction").value("REFRESH_PLAN"))
    }

    @Test
    fun `worker swap request and manager acceptance expose only command state`() {
        val request = mvc.perform(
            post("/api/shift-coverage/swaps")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceWorkerId\":\"worker-a\",\"targetWorkerId\":\"worker-b\"}")
                .worker("worker-a-demo")
                .header("Idempotency-Key", "swap-route-1"),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andReturn()
        val requestId = request.response.contentAsString.substringAfter("\"requestId\":\"").substringBefore('\"')
        requestId.isNotBlank() shouldBeEqualTo true

        mvc.perform(
            post("/api/shift-coverage/swaps/$requestId/accept")
                .manager("accept-route-1", null),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.requestId").value(requestId))

        mvc.perform(
            post("/api/shift-coverage/swaps")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceWorkerId\":\"worker-b\",\"targetWorkerId\":\"worker-a\"}")
                .header("X-Demo-Operator", "manager-demo")
                .header("X-Demo-Role", "manager")
                .header("Idempotency-Key", "swap-route-forbidden"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("DEMO_ROLE_FORBIDDEN"))
            .andExpect(jsonPath("$.nextAction").value("USE_ALLOWED_ROLE"))
    }

    @Test
    fun `callback signature and operator headers fail closed without writes`() {
        val body = "{\"event\":\"availability.changed\"}".toByteArray()
        val eventId = "route-callback-invalid"
        val requestId = "route-callback-request"
        mvc.perform(
            post("/api/shift-coverage/callbacks/FAKE")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Shift-Coverage-Event-Id", eventId)
                .header("X-Request-Id", requestId)
                .header("X-Shift-Coverage-Issued-At", "2026-08-24T09:00:00Z")
                .header("X-Shift-Coverage-Dataset-Id", "dataset-demo")
                .header("X-Shift-Coverage-Generation-Id", "generation-demo")
                .header("X-Shift-Coverage-Plan-Id", "plan-demo")
                .header("X-Shift-Coverage-Site-Id", "site-demo")
                .header("X-Shift-Coverage-Digest", body.sha256()),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("CALLBACK_SIGNATURE_INVALID"))
            .andExpect(jsonPath("$.nextAction").value("FIX_SIGNATURE"))
            .andExpect(jsonPath("$.requestId").value(requestId))
        inbox.find(ShiftCoverageProvider.FAKE, EventId(eventId)) shouldBeEqualTo null

        mvc.perform(
            post("/api/shift-coverage/inbox/UNKNOWN/event-1/requeue")
                .manager("operator-1", null)
                .header("X-Operator-Reason", "operator reason"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
            .andExpect(jsonPath("$.nextAction").value("FIX_REQUEST"))

        mvc.perform(
            post("/api/shift-coverage/inbox/FAKE/event-1/requeue")
                .header("X-Demo-Operator", "worker-a-demo")
                .header("X-Demo-Role", "worker")
                .header("X-Operator-Reason", "operator reason"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("DEMO_ROLE_FORBIDDEN"))
    }

    @Test
    fun `allowed local origin stays same origin while hostile variants are denied`() {
        mvc.perform(
            get("/api/shift-coverage/swaps")
                .manager()
                .header("Origin", "http://localhost"),
        )
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))

        listOf("https://localhost", "http://localhost/path", "http://localhost?query=1", "http://localhost.evil")
            .forEach { origin ->
                mvc.perform(
                    get("/api/shift-coverage/swaps")
                        .manager()
                        .header("Origin", origin),
                )
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("ORIGIN_FORBIDDEN"))
                    .andExpect(jsonPath("$.nextAction").value("USE_SAME_ORIGIN"))
            }
    }

    private fun MockHttpServletRequestBuilder.manager(idempotencyKey: String? = null, requestId: String? = null) = apply {
        header("X-Demo-Operator", "manager-demo")
        header("X-Demo-Role", "manager")
        idempotencyKey?.let { header("Idempotency-Key", it) }
        requestId?.let { header("X-Request-Id", it) }
    }

    private fun MockHttpServletRequestBuilder.worker(operator: String) = apply {
        header("X-Demo-Operator", operator)
        header("X-Demo-Role", "worker")
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

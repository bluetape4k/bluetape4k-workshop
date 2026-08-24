package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageDemoService
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
}

package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.workshop.commerce.ticket.operations.api.OperationsCommands
import io.bluetape4k.workshop.commerce.ticket.operations.api.ReconcileSummary
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock

internal class TicketInputBoundaryIntegrationTest {
    @Test
    fun `operator run rejects an oversized batch and short reason`() {
        val operations = OperationsCommands { ReconcileSummary(0, 0, 0) }
        val mvc = MockMvcBuilders.standaloneSetup(OperatorTicketController(operations, Clock.systemUTC()))
            .setControllerAdvice(ApiExceptionHandler())
            .build()

        mvc.post("/api/v1/operator/reconciliation-runs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"limit":51,"reason":"short"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_request") }
        }
    }
}

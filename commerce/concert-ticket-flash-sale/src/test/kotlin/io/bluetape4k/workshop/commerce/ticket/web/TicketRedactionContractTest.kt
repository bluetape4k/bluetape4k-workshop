package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseQueries
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

internal class TicketRedactionContractTest {
    @Test
    fun `problem response never reflects a raw principal canary`() {
        val canary = "raw-user-canary@example.invalid"
        val mvc = MockMvcBuilders.standaloneSetup(
            CustomerTicketController(NoopPurchaseCommands, PurchaseQueries { _, _ -> null }, PrincipalSubjectResolver()),
        ).setControllerAdvice(ApiExceptionHandler()).build()

        val response = mvc.get("/api/v1/purchase-attempts/${UUID.randomUUID()}") {
            principal = UsernamePasswordAuthenticationToken.authenticated(canary, null, emptyList())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_request") }
        }.andReturn().response.contentAsString

        response.contains(canary).shouldBeFalse()
    }
}

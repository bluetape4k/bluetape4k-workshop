package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

internal class TicketRedactionContractTest {

    companion object: KLogging()

    @Test
    fun `problem response never reflects a raw principal canary`() {
        val canary = "raw-user-canary@example.invalid"
        val mvc = MockMvcBuilders
            .standaloneSetup(
                CustomerTicketController(NoopPurchaseCommands, { _, _ -> null }, PrincipalSubjectResolver()),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

        val response = mvc
            .get("/api/v1/purchase-attempts/${Uuid.V7.nextId()}") {
                principal = UsernamePasswordAuthenticationToken
                    .authenticated(canary, null, emptyList())
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid_request") }
            }
            .andReturn()
            .response
            .contentAsString

        log.debug { "response: $response" }
        response shouldNotContain canary
    }
}

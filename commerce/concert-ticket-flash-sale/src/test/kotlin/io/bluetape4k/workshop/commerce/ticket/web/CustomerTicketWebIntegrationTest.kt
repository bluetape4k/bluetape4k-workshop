package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyTicketOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseCommands
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseQueries
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseSnapshot
import io.bluetape4k.workshop.commerce.ticket.purchase.api.StartPurchase
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

internal class CustomerTicketWebIntegrationTest {
    @Test
    fun `other buyer cannot discover an attempt`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val attempt = UUID.randomUUID()
        val snapshot = PurchaseSnapshot(attempt, PurchaseState.INVENTORY_HELD, 0, Instant.EPOCH)
        val queries = PurchaseQueries { id, buyer -> snapshot.takeIf { id == attempt && buyer == owner } }
        val mvc = MockMvcBuilders.standaloneSetup(
            CustomerTicketController(NoopPurchaseCommands, queries, PrincipalSubjectResolver()),
        ).setControllerAdvice(ApiExceptionHandler()).build()

        mvc.get("/api/v1/purchase-attempts/$attempt") {
            principal = UsernamePasswordAuthenticationToken.authenticated(other.toString(), null, emptyList())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("purchase_not_found") }
            jsonPath("$.retryable") { value(false) }
        }
    }

    @Test
    fun `owner response is no store and contains only recovery projection`() {
        val owner = UUID.randomUUID()
        val attempt = UUID.randomUUID()
        val snapshot = PurchaseSnapshot(attempt, PurchaseState.APPROVED, 3, Instant.EPOCH)
        val mvc = MockMvcBuilders.standaloneSetup(
            CustomerTicketController(NoopPurchaseCommands, PurchaseQueries { _, _ -> snapshot }, PrincipalSubjectResolver()),
        ).setControllerAdvice(ApiExceptionHandler()).build()

        mvc.get("/api/v1/purchase-attempts/$attempt") {
            principal = UsernamePasswordAuthenticationToken.authenticated(owner.toString(), null, emptyList())
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.attemptId") { value(attempt.toString()) }
            jsonPath("$.state") { value("APPROVED") }
            jsonPath("$.buyerSubjectId") { doesNotExist() }
            jsonPath("$.authorizationOperationId") { doesNotExist() }
        }
    }
}

internal object NoopPurchaseCommands : PurchaseCommands {
    override fun start(command: StartPurchase): PurchaseSnapshot = error("not used")
    override fun cancel(command: CancelPurchase): PurchaseSnapshot = error("not used")
    override fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot = error("not used")
}

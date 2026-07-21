package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseCommands
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseQueries
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseSnapshot
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseNotFound
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Owner-safe purchase recovery endpoints; foreign IDs deliberately return the same 404 as absent IDs. */
@RestController
@RequestMapping("/api/v1/purchase-attempts")
class CustomerTicketController(
    private val commands: PurchaseCommands,
    private val queries: PurchaseQueries,
    private val buyers: AuthenticatedBuyerResolver,
) {
    @GetMapping("/{attemptId}")
    fun get(@PathVariable attemptId: UUID, authentication: Authentication): ResponseEntity<PurchaseSnapshot> =
        ownerSnapshot(attemptId, authentication).noStore()

    @PostMapping("/{attemptId}/cancellation")
    fun cancel(@PathVariable attemptId: UUID, authentication: Authentication): ResponseEntity<PurchaseSnapshot> {
        val buyerId = buyers.resolve(authentication)
        if (queries.owned(attemptId, buyerId) == null) throw PurchaseNotFound()
        return ResponseEntity.accepted()
            .cacheControl(CacheControl.noStore())
            .body(commands.cancel(CancelPurchase(attemptId, buyerId)))
    }

    private fun ownerSnapshot(attemptId: UUID, authentication: Authentication): PurchaseSnapshot =
        queries.owned(attemptId, buyers.resolve(authentication)) ?: throw PurchaseNotFound()

    private fun PurchaseSnapshot.noStore(): ResponseEntity<PurchaseSnapshot> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this)
}

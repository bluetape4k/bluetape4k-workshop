package io.bluetape4k.workshop.messaging.fallback.api

import io.bluetape4k.workshop.messaging.fallback.domain.PlaceOrderUseCase
import io.bluetape4k.workshop.messaging.fallback.domain.TransactionalOrderWriter
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * REST endpoints for the Kafka-first outbox fallback order flow.
 */
@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val placeOrderUseCase: PlaceOrderUseCase,
    private val transactionalOrderWriter: TransactionalOrderWriter,
) {

    @PostMapping
    fun placeOrder(@Valid @RequestBody request: OrderRequest): ResponseEntity<OrderResponse> {
        val response = placeOrderUseCase.placeOrder(request)
        return ResponseEntity
            .created(URI.create("/api/orders/${response.id}"))
            .body(response)
    }

    @GetMapping("/{id}")
    fun getOrder(@PathVariable id: Long): OrderResponse =
        OrderResponse.from(
            record = transactionalOrderWriter.getOrder(id),
            publicationStatus = OrderPublicationStatus.UNKNOWN,
        )

    @GetMapping
    fun listOrders(): List<OrderResponse> =
        transactionalOrderWriter.findOrders()
            .map { order ->
                OrderResponse.from(
                    record = order,
                    publicationStatus = OrderPublicationStatus.UNKNOWN,
                )
            }
}

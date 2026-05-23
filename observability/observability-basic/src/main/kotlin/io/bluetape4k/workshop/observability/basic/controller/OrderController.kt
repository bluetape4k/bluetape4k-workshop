package io.bluetape4k.workshop.observability.basic.controller

import io.bluetape4k.workshop.observability.basic.model.Order
import io.bluetape4k.workshop.observability.basic.service.OrderService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller exposing order endpoints.
 *
 * ## Behavior / Contract
 * - `GET /orders/{id}` returns 200 with the order body, or 404 when not found.
 * - The HTTP server span (`http.server.requests`) is created automatically by Spring Boot.
 */
@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderService: OrderService,
) {
    /**
     * Retrieves an order by its ID.
     *
     * Returns 200 OK with the order, or 404 Not Found when the order does not exist.
     */
    @GetMapping("/{id}")
    suspend fun getOrder(@PathVariable id: Long): ResponseEntity<Order> {
        val order = orderService.getOrder(id)
        return if (order != null) ResponseEntity.ok(order)
        else ResponseEntity.notFound().build()
    }
}

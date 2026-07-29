package io.bluetape4k.workshop.observability.basic.controller

import io.bluetape4k.workshop.observability.basic.model.Order
import io.bluetape4k.workshop.observability.basic.service.OrderService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * order endpoint 를 노출하는 REST controller 입니다.
 *
 * ## Behavior / Contract
 * - `GET /orders/{id}` 는 order body 와 함께 200 을 반환하거나 찾지 못하면 404 를 반환합니다.
 * - HTTP server span(`http.server.requests`)은 Spring Boot 가 자동 생성합니다.
 */
@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderService: OrderService,
) {
    /**
     * ID 로 order 를 조회합니다.
     *
     * order 가 존재하면 200 OK 와 함께 반환하고, 존재하지 않으면 404 Not Found 를 반환합니다.
     */
    @GetMapping("/{id}")
    suspend fun getOrder(@PathVariable id: Long): ResponseEntity<Order> {
        val order = orderService.getOrder(id)
        return if (order != null) ResponseEntity.ok(order)
        else ResponseEntity.notFound().build()
    }
}

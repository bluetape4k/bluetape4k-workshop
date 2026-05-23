package io.bluetape4k.workshop.exposed.webflux.r2dbc.order.controller

import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.service.OrderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/orders")
class OrderController(private val orderService: OrderService) {

    @GetMapping
    suspend fun findAll(): List<OrderDTO> = orderService.findAllOrders()

    @GetMapping("/{id}")
    suspend fun findById(@PathVariable id: Long): OrderDTO = orderService.findOrderById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun placeOrder(@Valid @RequestBody req: PlaceOrderRequest): OrderDTO =
        orderService.placeOrder(req)

    @PostMapping("/{id}/cancel")
    suspend fun cancelOrder(@PathVariable id: Long): OrderDTO = orderService.cancelOrder(id)
}

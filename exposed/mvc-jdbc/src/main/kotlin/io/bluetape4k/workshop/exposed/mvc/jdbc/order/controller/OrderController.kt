package io.bluetape4k.workshop.exposed.mvc.jdbc.order.controller

import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderLineDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.service.OrderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(private val orderService: OrderService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun placeOrder(@Valid @RequestBody req: PlaceOrderRequest): OrderDTO =
        orderService.placeOrder(req)

    @GetMapping("/{id}")
    fun findById(@PathVariable id: Long): OrderDTO = orderService.findById(id)

    @GetMapping("/{id}/lines")
    fun findLines(@PathVariable id: Long): List<OrderLineDTO> =
        orderService.findWithLines(id).lines

    @GetMapping("/{id}/total")
    fun getTotal(@PathVariable id: Long): BigDecimal = orderService.calculateTotal(id)

    @PatchMapping("/{id}/cancel")
    fun cancelOrder(@PathVariable id: Long): OrderDTO = orderService.cancelOrder(id)
}

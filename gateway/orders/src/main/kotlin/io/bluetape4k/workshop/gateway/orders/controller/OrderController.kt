package io.bluetape4k.workshop.gateway.orders.controller

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.gateway.orders.model.Order
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@CrossOrigin
@RequestMapping("/api/v1/orders")
class OrderController {

    companion object: KLogging()

    private val uuidGenerator = TimebasedUuid.Reordered

    @GetMapping("", "/")
    suspend fun getAll(): List<Order> {
        return listOf(
            Order(uuidGenerator.nextIdAsString(), 100.0.toBigDecimal(), "Winter"),
            Order(uuidGenerator.nextIdAsString(), 50.0.toBigDecimal(), "Spring"),
        )
    }
}

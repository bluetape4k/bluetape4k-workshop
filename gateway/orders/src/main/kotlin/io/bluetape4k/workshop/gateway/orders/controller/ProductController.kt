package io.bluetape4k.workshop.gateway.orders.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.gateway.orders.model.Product
import io.bluetape4k.workshop.gateway.orders.service.OrderCatalogService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/products")
class ProductController(
    private val orderCatalogService: OrderCatalogService,
) {

    companion object: KLoggingChannel()

    @GetMapping
    suspend fun getAll(): List<Product> {
        return orderCatalogService.getProducts()
    }
}

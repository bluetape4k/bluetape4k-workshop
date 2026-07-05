package io.bluetape4k.workshop.gateway.orders.service

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.gateway.orders.model.Order
import io.bluetape4k.workshop.gateway.orders.model.Product
import org.springframework.stereotype.Service

@Service
class OrderCatalogService {

    private val uuidGenerator = Uuid.V7

    suspend fun getOrders(): List<Order> {
        return listOf(
            Order(uuidGenerator.nextIdAsString(), 100.0.toBigDecimal(), "Winter"),
            Order(uuidGenerator.nextIdAsString(), 50.0.toBigDecimal(), "Spring"),
        )
    }

    suspend fun getProducts(): List<Product> {
        return listOf(
            Product(uuidGenerator.nextIdAsString(), "Mac Book Pro", 230.toBigDecimal()),
            Product(uuidGenerator.nextIdAsString(), "iPhone", 190.toBigDecimal()),
        )
    }
}

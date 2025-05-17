package io.bluetape4k.workshop.gateway.orders.controller

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.gateway.orders.model.Product
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/products")
@CrossOrigin
class ProductController: CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {

    companion object: KLoggingChannel()

    private val uuidGenerator = TimebasedUuid.Reordered

    @GetMapping
    suspend fun getAll(): List<Product> {
        return listOf(
            Product(uuidGenerator.nextIdAsString(), "Mac Book Pro", 230.toBigDecimal()),
            Product(uuidGenerator.nextIdAsString(), "iPhone", 190.toBigDecimal()),
        )
    }

    @PreDestroy
    private fun destroy() {
        coroutineContext.cancel()
    }
}

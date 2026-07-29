package io.bluetape4k.workshop.cache.benchmark.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

/**
 * benchmark 실행을 위해 in-memory H2 DB 를 예제 product 로 초기화합니다.
 */
@Configuration
class DataInitConfig {
    companion object : KLoggingChannel() {
        const val PRODUCT_COUNT = 1000
        val CATEGORIES = listOf("Electronics", "Books", "Clothing", "Food", "Sports")
    }

    @Bean
    fun dataInitRunner(productRepository: ProductRepository): ApplicationRunner = ApplicationRunner {
        if (productRepository.count() > 0L) return@ApplicationRunner

        val products = (1..PRODUCT_COUNT).map { i ->
            Product(
                name = "Product-$i",
                category = CATEGORIES[i % CATEGORIES.size],
                price = BigDecimal("${10 + (i % 100)}.99"),
                stock = 100 + i,
            )
        }
        productRepository.saveAll(products)
        log.info { "Initialized $PRODUCT_COUNT products" }
    }
}

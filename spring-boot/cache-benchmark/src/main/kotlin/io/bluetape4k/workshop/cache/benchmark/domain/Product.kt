package io.bluetape4k.workshop.cache.benchmark.domain

import io.bluetape4k.logging.coroutines.KLoggingChannel
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal

/**
 * Product entity used as the benchmark subject for all 7 cache profiles.
 *
 * Implements [Serializable] so it can be stored in distributed caches (Redis, Redisson).
 */
@Entity
@Table(
    name = "products",
    indexes = [
        Index(name = "idx_products_category", columnList = "category"),
        Index(name = "idx_products_name", columnList = "name"),
    ]
)
data class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L,

    @Column(nullable = false, length = 200)
    val name: String = "",

    @Column(nullable = false, length = 50)
    val category: String = "",

    @Column(nullable = false, precision = 12, scale = 2)
    val price: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false)
    val stock: Int = 0,
): Serializable {
    companion object: KLoggingChannel() {
        private const val serialVersionUID: Long = 1L
    }
}

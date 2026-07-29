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
 * 7개 cache profile 모두에서 benchmark 대상이 되는 Product entity 입니다.
 *
 * distributed cache(Redis, Redisson)에 저장할 수 있도록 [Serializable] 을 구현합니다.
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
) : Serializable {
    companion object : KLoggingChannel() {
        private const val serialVersionUID: Long = 1L
    }
}

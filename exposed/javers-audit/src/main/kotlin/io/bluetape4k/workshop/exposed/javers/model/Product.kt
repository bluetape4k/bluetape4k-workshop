package io.bluetape4k.workshop.exposed.javers.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.javers.core.metamodel.annotation.Id
import org.javers.core.metamodel.annotation.TypeName
import java.io.Serializable
import java.math.BigDecimal

/**
 * A product entity tracked by JaVers for change-history auditing.
 *
 * ## Behavior / Contract
 * - [id] is the JaVers entity key; snapshots are keyed by this value.
 * - [price] must be non-negative.
 * - All instances are immutable value objects; create a new [Product] for updates.
 *
 * ```kotlin
 * val product = Product(id = 1L, name = "Widget", price = BigDecimal("9.99"), category = "Tools")
 * val updated = Product(id = product.id, name = product.name, price = BigDecimal("12.99"), category = product.category)
 * ```
 */
@TypeName("Product")
@ConsistentCopyVisibility
data class Product private constructor(
    @Id val id: Long,
    val name: String,
    val price: BigDecimal,
    val category: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * Creates a validated product value for Exposed persistence and JaVers auditing.
         */
        operator fun invoke(
            id: Long,
            name: String,
            price: BigDecimal,
            category: String,
        ): Product {
            id.requirePositiveNumber("id")
            name.requireNotBlank("name")
            category.requireNotBlank("category")
            require(price.signum() >= 0) { "price must be zero or positive" }
            return Product(
                id = id,
                name = name,
                price = price,
                category = category,
            )
        }
    }
}

package io.bluetape4k.workshop.exposed.javers.model

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
 * - All instances are immutable value objects; use [copy] for updates.
 *
 * ```kotlin
 * val product = Product(id = 1L, name = "Widget", price = BigDecimal("9.99"), category = "Tools")
 * val updated = product.copy(price = BigDecimal("12.99"))
 * ```
 */
@TypeName("Product")
data class Product(
    @Id val id: Long,
    val name: String,
    val price: BigDecimal,
    val category: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

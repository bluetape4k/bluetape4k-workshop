package io.bluetape4k.workshop.exposed.javers.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.javers.core.metamodel.annotation.Id
import org.javers.core.metamodel.annotation.TypeName
import java.io.Serializable
import java.math.BigDecimal

/**
 * 변경 이력 감사를 위해 JaVers가 추적하는 product entity이다.
 *
 * ## 동작 / 계약
 * - [id]는 JaVers entity key이며 snapshot은 이 값으로 식별된다.
 * - [price]는 음수일 수 없다.
 * - 모든 인스턴스는 불변 value object이므로 update에는 새 [Product]를 만든다.
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
         * Exposed 영속화와 JaVers 감사를 위한 검증된 product 값을 만든다.
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

package io.bluetape4k.workshop.exposed.javers.persistence

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.javers.core.metamodel.annotation.Id
import org.javers.core.metamodel.annotation.TypeName
import java.io.Serializable
import java.math.BigDecimal

/**
 * JaVers가 감사하는 불변 order aggregate이다.
 *
 * ## 동작 / 계약
 * - [id]는 JaVers entity key이자 Exposed table primary key이다.
 * - [status]는 audit-history 예제에서 사용하는 lifecycle 상태를 기록한다.
 * - [totalAmount]는 floating-point 반올림을 피하기 위해 decimal 값으로 저장한다.
 *
 * ```kotlin
 * val order = Order("order-1", "customer-1", OrderStatus.PLACED, BigDecimal("19.99"))
 * val paid = order.copy(status = OrderStatus.PAID)
 * ```
 */
@TypeName("Order")
data class Order(
    @Id val id: String,
    val customerId: String,
    val status: OrderStatus,
    val totalAmount: BigDecimal,
): Serializable {
    init {
        id.requireNotBlank("id")
        customerId.requireNotBlank("customerId")
        totalAmount.requireZeroOrPositiveNumber("totalAmount")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * persistence audit 워크숍에서 사용하는 order lifecycle 상태이다.
 */
enum class OrderStatus {
    PLACED,
    PAID,
}

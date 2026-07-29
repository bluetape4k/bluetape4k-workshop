package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import io.bluetape4k.codec.Base58
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.javers.core.metamodel.annotation.Id
import org.javers.core.metamodel.annotation.TypeName
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant

/**
 * 안정적인 order aggregate 식별자입니다.
 *
 * ## 동작 / 계약
 * - [value] 는 비어 있지 않아야 합니다.
 * - 이 식별자는 PostgreSQL row, Spring Modulith event, JaVers snapshot 에 사용됩니다.
 */
data class OrderId(val value: String) : Serializable {
    init {
        value.requireNotBlank("value")
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * 예제와 테스트용 무작위 order 식별자를 새로 생성합니다.
         */
        fun newId(): OrderId = OrderId("order-${Base58.randomString(8)}")
    }
}

/**
 * 안정적인 customer 식별자입니다.
 *
 * ## 동작 / 계약
 * - [value] 는 비어 있지 않아야 합니다.
 * - 이 workshop 은 이 값을 synthetic identifier 로만 다루며 PII 로 취급하지 않습니다.
 */
data class CustomerId(val value: String) : Serializable {
    init {
        value.requireNotBlank("value")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * order line 에서 사용하는 금액입니다.
 *
 * ## 동작 / 계약
 * - [amount] 는 0 이상이어야 합니다.
 * - [currency] 는 비어 있지 않아야 합니다.
 */
data class Money(
    val amount: BigDecimal,
    val currency: String = "USD",
) : Serializable {
    init {
        amount.requireZeroOrPositiveNumber("amount")
        currency.requireNotBlank("currency")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 불변 order line 입니다.
 *
 * ## 동작 / 계약
 * - [sku] 는 비어 있지 않아야 합니다.
 * - [quantity] 는 양수여야 합니다.
 * - command 로 생성되는 order line 의 [unitPrice] 는 양수여야 합니다.
 */
data class OrderLine(
    val sku: String,
    val quantity: Int,
    val unitPrice: Money,
) : Serializable {
    init {
        sku.requireNotBlank("sku")
        quantity.requirePositiveNumber("quantity")
        unitPrice.amount.requirePositiveNumber("unitPrice.amount")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * workshop aggregate 에서 사용하는 order lifecycle 상태입니다.
 */
enum class OrderStatus {
    PLACED,
    APPROVED,
    CANCELLED,
}

/**
 * 새 주문을 생성하는 command 입니다.
 *
 * ## 동작 / 계약
 * - [customerId] 는 synthetic customer 를 식별합니다.
 * - [lines] 는 유효한 [OrderLine] 을 하나 이상 포함해야 합니다.
 */
data class PlaceOrderCommand(
    val customerId: CustomerId,
    val lines: List<OrderLine>,
) : Serializable {
    init {
        lines.requireNotEmpty("lines")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 기존 주문을 승인하는 command 입니다.
 *
 * ## 동작 / 계약
 * - [orderId] 는 승인 대상 aggregate 와 일치해야 합니다.
 * - [approvedBy] 는 audit metadata 에 사용하는 안전한 synthetic actor id 입니다.
 */
data class ApproveOrderCommand(
    val orderId: OrderId,
    val approvedBy: String,
) : Serializable {
    init {
        approvedBy.requireNotBlank("approvedBy")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Spring Modulith 와 JaVers audit metadata 가 공유하는 최소 event 계약입니다.
 */
interface DomainEvent : Serializable {
    val aggregateId: String
    val occurredOn: Instant
}

/**
 * 주문이 생성될 때 발생하는 event 입니다.
 */
data class OrderPlaced(
    override val aggregateId: String,
    override val occurredOn: Instant = Instant.now(),
) : DomainEvent {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 주문이 승인될 때 발생하는 event 입니다.
 */
data class OrderApproved(
    override val aggregateId: String,
    val approvedBy: String,
    override val occurredOn: Instant = Instant.now(),
) : DomainEvent {
    init {
        approvedBy.requireNotBlank("approvedBy")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * JaVers 로 audit 되고 JPA 로 저장되는 불변 order aggregate 입니다.
 *
 * ## 동작 / 계약
 * - command method 는 새 aggregate instance 를 반환하며 현재 instance 를 변경하지 않습니다.
 * - [events] 는 이 instance 를 만든 command 가 새로 발생시킨 event 만 포함합니다.
 * - event 는 전체 aggregate payload 가 아니라 식별자와 안전한 metadata 만 담습니다.
 */
@TypeName("DddOrder")
data class Order(
    @Id val id: OrderId,
    val customerId: CustomerId,
    val lines: List<OrderLine>,
    val totalAmount: BigDecimal = lines.totalAmount(),
    val lineCount: Int = lines.size,
    val status: OrderStatus,
    val version: Long = 0,
    val events: List<DomainEvent> = emptyList(),
) : Serializable {
    init {
        lines.requireNotEmpty("lines")
    }

    /**
     * 이 주문을 승인하고 [OrderApproved] 를 발생시킵니다.
     */
    fun approve(command: ApproveOrderCommand): Order {
        require(command.orderId == id) { "approve command order id must match aggregate id." }
        check(status == OrderStatus.PLACED) { "only placed orders can be approved." }

        return copy(
            status = OrderStatus.APPROVED,
            events = listOf(
                OrderApproved(
                    aggregateId = id.value,
                    approvedBy = command.approvedBy,
                ),
            ),
        )
    }

    /**
     * 주변 infrastructure 가 처리한 뒤 pending domain event 를 지웁니다.
     */
    fun withoutEvents(): Order = copy(events = emptyList())

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * 새 주문을 생성하고 [OrderPlaced] 를 발생시킵니다.
         */
        fun place(command: PlaceOrderCommand): Order {
            val id = OrderId.newId()
            return Order(
                id = id,
                customerId = command.customerId,
                lines = command.lines.toList(),
                status = OrderStatus.PLACED,
                events = listOf(OrderPlaced(aggregateId = id.value)),
            )
        }

        /**
         * domain event 를 발생시키지 않고 저장된 aggregate 를 복원합니다.
         */
        fun restore(
            id: OrderId,
            customerId: CustomerId,
            lines: List<OrderLine>,
            status: OrderStatus,
            version: Long,
        ): Order =
            Order(
                id = id,
                customerId = customerId,
                lines = lines.toList(),
                status = status,
                version = version,
            )
    }
}

private fun List<OrderLine>.totalAmount(): BigDecimal =
    fold(BigDecimal.ZERO) { total, line ->
        total + line.unitPrice.amount.multiply(BigDecimal(line.quantity))
    }

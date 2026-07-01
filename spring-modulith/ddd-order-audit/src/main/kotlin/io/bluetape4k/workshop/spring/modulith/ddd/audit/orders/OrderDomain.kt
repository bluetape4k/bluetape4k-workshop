package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import org.javers.core.metamodel.annotation.Id
import org.javers.core.metamodel.annotation.TypeName
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Stable order aggregate identifier.
 *
 * ## Behavior / Contract
 * - [value] must be non-blank.
 * - The identifier is used by PostgreSQL rows, Spring Modulith events, and JaVers snapshots.
 */
data class OrderId(val value: String): Serializable {
    init {
        require(value.isNotBlank()) { "order id must not be blank." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * Creates a new random order identifier for examples and tests.
         */
        fun newId(): OrderId = OrderId("order-${UUID.randomUUID()}")
    }
}

/**
 * Stable customer identifier.
 *
 * ## Behavior / Contract
 * - [value] must be non-blank.
 * - The workshop treats it as a synthetic identifier and never as PII.
 */
data class CustomerId(val value: String): Serializable {
    init {
        require(value.isNotBlank()) { "customer id must not be blank." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Monetary amount used by order lines.
 *
 * ## Behavior / Contract
 * - [amount] must be zero or positive.
 * - [currency] must be non-blank.
 */
data class Money(
    val amount: BigDecimal,
    val currency: String = "USD",
): Serializable {
    init {
        require(amount >= BigDecimal.ZERO) { "money amount must not be negative." }
        require(currency.isNotBlank()) { "currency must not be blank." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Immutable order line.
 *
 * ## Behavior / Contract
 * - [sku] must be non-blank.
 * - [quantity] must be positive.
 * - [unitPrice] must be positive for command-created order lines.
 */
data class OrderLine(
    val sku: String,
    val quantity: Int,
    val unitPrice: Money,
): Serializable {
    init {
        require(sku.isNotBlank()) { "sku must not be blank." }
        require(quantity > 0) { "quantity must be positive." }
        require(unitPrice.amount > BigDecimal.ZERO) { "unit price must be positive." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Order lifecycle states used by the workshop aggregate.
 */
enum class OrderStatus {
    PLACED,
    APPROVED,
    CANCELLED,
}

/**
 * Command that places a new order.
 *
 * ## Behavior / Contract
 * - [customerId] identifies the synthetic customer.
 * - [lines] must contain at least one valid [OrderLine].
 */
data class PlaceOrderCommand(
    val customerId: CustomerId,
    val lines: List<OrderLine>,
): Serializable {
    init {
        require(lines.isNotEmpty()) { "order lines must not be empty." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Command that approves an existing order.
 *
 * ## Behavior / Contract
 * - [orderId] must match the aggregate being approved.
 * - [approvedBy] is a safe synthetic actor id for audit metadata.
 */
data class ApproveOrderCommand(
    val orderId: OrderId,
    val approvedBy: String,
): Serializable {
    init {
        require(approvedBy.isNotBlank()) { "approvedBy must not be blank." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Minimal event contract shared by Spring Modulith and JaVers audit metadata.
 */
interface DomainEvent: Serializable {
    val aggregateId: String
    val occurredOn: Instant
}

/**
 * Event emitted when an order is placed.
 */
data class OrderPlaced(
    override val aggregateId: String,
    override val occurredOn: Instant = Instant.now(),
): DomainEvent {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Event emitted when an order is approved.
 */
data class OrderApproved(
    override val aggregateId: String,
    val approvedBy: String,
    override val occurredOn: Instant = Instant.now(),
): DomainEvent {
    init {
        require(approvedBy.isNotBlank()) { "approvedBy must not be blank." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Immutable order aggregate audited by JaVers and persisted through JPA.
 *
 * ## Behavior / Contract
 * - Command methods return new aggregate instances and never mutate the current one.
 * - [events] contains only the new events emitted by the command that produced this instance.
 * - Events carry identifiers and safe metadata, not full aggregate payloads.
 */
@TypeName("DddOrder")
data class Order(
    @Id val id: OrderId,
    val customerId: CustomerId,
    val lines: List<OrderLine>,
    val status: OrderStatus,
    val version: Long = 0,
    val events: List<DomainEvent> = emptyList(),
): Serializable {
    init {
        require(lines.isNotEmpty()) { "order lines must not be empty." }
    }

    /**
     * Approves this order and emits [OrderApproved].
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
     * Clears pending domain events after the surrounding infrastructure has handled them.
     */
    fun withoutEvents(): Order = copy(events = emptyList())

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * Creates a new placed order and emits [OrderPlaced].
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
         * Restores a persisted aggregate without emitting domain events.
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

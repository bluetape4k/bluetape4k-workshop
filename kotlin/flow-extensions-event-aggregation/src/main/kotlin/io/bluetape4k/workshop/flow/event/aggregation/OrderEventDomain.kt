package io.bluetape4k.workshop.flow.event.aggregation

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant
import java.util.Objects

/**
 * in-memory aggregation 예제가 소비하는 order lifecycle event 입니다.
 *
 * 구현체는 data class 가 아니라 검증된 일반 class 입니다. 생성된 `copy(...)` 메서드가 trimming 이나 safe rendering 을 우회하지 못하게 하기 위함입니다.
 */
sealed interface OrderEvent: Serializable {
    val orderId: String
    val occurredAt: Instant
    val eventType: String
}

/**
 * 새 order 가 event stream 에 진입했음을 나타냅니다.
 */
class OrderCreated private constructor(
    override val orderId: String,
    val customerId: String,
    override val occurredAt: Instant,
): OrderEvent {

    override val eventType: String = "OrderCreated"

    override fun toString(): String =
        "OrderCreated(orderId=$orderId, customerId=<redacted>, occurredAt=$occurredAt)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is OrderCreated &&
            orderId == other.orderId &&
            customerId == other.customerId &&
            occurredAt == other.occurredAt

    override fun hashCode(): Int = Objects.hash(orderId, customerId, occurredAt)

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(orderId: String, customerId: String, occurredAt: Instant): OrderCreated =
            OrderCreated(
                orderId = normalizeToken(orderId, "orderId"),
                customerId = normalizeToken(customerId, "customerId"),
                occurredAt = occurredAt,
            )
    }
}

/**
 * order 에 line item 이 추가되었음을 나타냅니다.
 */
class LineAdded private constructor(
    override val orderId: String,
    val sku: String,
    val quantity: Int,
    override val occurredAt: Instant,
): OrderEvent {

    override val eventType: String = "LineAdded"

    override fun toString(): String =
        "LineAdded(orderId=$orderId, sku=$sku, quantity=$quantity, occurredAt=$occurredAt)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is LineAdded &&
            orderId == other.orderId &&
            sku == other.sku &&
            quantity == other.quantity &&
            occurredAt == other.occurredAt

    override fun hashCode(): Int = Objects.hash(orderId, sku, quantity, occurredAt)

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(orderId: String, sku: String, quantity: Int, occurredAt: Instant): LineAdded {
            quantity.requirePositiveNumber("quantity")
            return LineAdded(
                orderId = normalizeToken(orderId, "orderId"),
                sku = normalizeToken(sku, "sku"),
                quantity = quantity,
                occurredAt = occurredAt,
            )
        }
    }
}

/**
 * order 결제가 승인되었음을 나타냅니다.
 */
class PaymentAuthorized private constructor(
    override val orderId: String,
    val amountCents: Long,
    override val occurredAt: Instant,
): OrderEvent {

    override val eventType: String = "PaymentAuthorized"

    override fun toString(): String =
        "PaymentAuthorized(orderId=$orderId, amountCents=$amountCents, occurredAt=$occurredAt)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is PaymentAuthorized &&
            orderId == other.orderId &&
            amountCents == other.amountCents &&
            occurredAt == other.occurredAt

    override fun hashCode(): Int = Objects.hash(orderId, amountCents, occurredAt)

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(orderId: String, amountCents: Long, occurredAt: Instant): PaymentAuthorized {
            amountCents.requirePositiveNumber("amountCents")
            return PaymentAuthorized(
                orderId = normalizeToken(orderId, "orderId"),
                amountCents = amountCents,
                occurredAt = occurredAt,
            )
        }
    }
}

/**
 * order fulfillment 가 시작되었음을 나타냅니다.
 */
class ShipmentStarted private constructor(
    override val orderId: String,
    val carrier: String,
    val trackingNumber: String,
    override val occurredAt: Instant,
): OrderEvent {

    override val eventType: String = "ShipmentStarted"

    override fun toString(): String =
        "ShipmentStarted(orderId=$orderId, carrier=$carrier, trackingNumber=<redacted>, occurredAt=$occurredAt)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ShipmentStarted &&
            orderId == other.orderId &&
            carrier == other.carrier &&
            trackingNumber == other.trackingNumber &&
            occurredAt == other.occurredAt

    override fun hashCode(): Int = Objects.hash(orderId, carrier, trackingNumber, occurredAt)

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            orderId: String,
            carrier: String,
            trackingNumber: String,
            occurredAt: Instant,
        ): ShipmentStarted =
            ShipmentStarted(
                orderId = normalizeToken(orderId, "orderId"),
                carrier = normalizeToken(carrier, "carrier"),
                trackingNumber = normalizeToken(trackingNumber, "trackingNumber", maxLength = 128),
                occurredAt = occurredAt,
            )
    }
}

/**
 * order cancellation 이 projection stream 에 진입했음을 나타냅니다.
 */
class OrderCancelled private constructor(
    override val orderId: String,
    val reason: String,
    override val occurredAt: Instant,
): OrderEvent {

    override val eventType: String = "OrderCancelled"

    override fun toString(): String =
        "OrderCancelled(orderId=$orderId, reason=<redacted>, occurredAt=$occurredAt)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is OrderCancelled &&
            orderId == other.orderId &&
            reason == other.reason &&
            occurredAt == other.occurredAt

    override fun hashCode(): Int = Objects.hash(orderId, reason, occurredAt)

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(orderId: String, reason: String, occurredAt: Instant): OrderCancelled =
            OrderCancelled(
                orderId = normalizeToken(orderId, "orderId"),
                reason = normalizeText(reason, "reason", maxLength = 128),
                occurredAt = occurredAt,
            )
    }
}

/**
 * projection 된 order lifecycle status 입니다.
 */
enum class OrderStatus {
    NEW,
    CREATED,
    PAID,
    SHIPPED,
    CANCELLED,
}

/**
 * 하나의 order 에 대한 현재 read model 입니다.
 */
data class OrderState(
    val orderId: String,
    val status: OrderStatus,
    val lineCount: Int,
    val itemQuantity: Int,
    val authorizedAmountCents: Long,
    val lastEventAt: Instant?,
    val version: Int,
): Serializable {

    init {
        normalizeToken(orderId, "orderId")
    }

    fun apply(event: OrderEvent): OrderState {
        require(orderId == event.orderId) { "event orderId[${event.orderId}] must match state orderId[$orderId]." }
        val terminal = status == OrderStatus.CANCELLED

        val next = when (event) {
            is OrderCreated      -> if (status == OrderStatus.NEW) copy(status = OrderStatus.CREATED) else this
            is LineAdded         -> if (terminal) {
                this
            } else {
                copy(
                    status = if (status == OrderStatus.NEW) OrderStatus.CREATED else status,
                    lineCount = lineCount + 1,
                    itemQuantity = itemQuantity + event.quantity,
                )
            }
            is PaymentAuthorized -> if (terminal) {
                this
            } else {
                copy(
                    status = if (status == OrderStatus.SHIPPED) OrderStatus.SHIPPED else OrderStatus.PAID,
                    authorizedAmountCents = event.amountCents,
                )
            }
            is ShipmentStarted   -> if (terminal) this else copy(status = OrderStatus.SHIPPED)
            is OrderCancelled    -> copy(status = OrderStatus.CANCELLED)
        }

        return next.copy(
            lastEventAt = event.occurredAt,
            version = version + 1,
        )
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        fun empty(orderId: String): OrderState =
            OrderState(
                orderId = normalizeToken(orderId, "orderId"),
                status = OrderStatus.NEW,
                lineCount = 0,
                itemQuantity = 0,
                authorizedAmountCents = 0,
                lastEventAt = null,
                version = 0,
            )
    }
}

/**
 * Immutable projection snapshot keyed by order id.
 */
data class OrderReadModel(
    val orders: Map<String, OrderState>,
): Serializable {

    fun apply(event: OrderEvent): OrderReadModel {
        val current = orders[event.orderId] ?: OrderState.empty(event.orderId)
        val nextOrders = orders.toMutableMap()
        nextOrders[event.orderId] = current.apply(event)
        return OrderReadModel(nextOrders.toMap())
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        fun empty(): OrderReadModel = OrderReadModel(emptyMap())
    }
}

/**
 * Compact summary for a bounded event batch or rolling window.
 */
data class OrderActivitySummary(
    val eventCount: Int,
    val orderIds: Set<String>,
    val latestStatuses: Map<String, OrderStatus>,
    val lineCount: Int,
    val itemQuantity: Int,
    val authorizedAmountCents: Long,
    val windowStartedAt: Instant,
    val windowEndedAt: Instant,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(events: List<OrderEvent>): OrderActivitySummary {
            events.requireNotEmpty("events")
            val model = events.fold(OrderReadModel.empty()) { acc, event -> acc.apply(event) }
            val states = model.orders.values
            return OrderActivitySummary(
                eventCount = events.size,
                orderIds = events.mapTo(linkedSetOf()) { it.orderId },
                latestStatuses = model.orders.toSortedMap().mapValues { it.value.status },
                lineCount = states.sumOf { it.lineCount },
                itemQuantity = states.sumOf { it.itemQuantity },
                authorizedAmountCents = states.sumOf { it.authorizedAmountCents },
                windowStartedAt = events.minOf { it.occurredAt },
                windowEndedAt = events.maxOf { it.occurredAt },
            )
        }
    }
}

/**
 * One adjacent run of equal lifecycle status after `bufferUntilChanged`.
 */
data class OrderStatusRun(
    val orderId: String,
    val status: OrderStatus,
    val stateCount: Int,
    val firstVersion: Int,
    val lastVersion: Int,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val finalState: OrderState,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(states: List<OrderState>): OrderStatusRun {
            states.requireNotEmpty("states")
            val first = states.first()
            val last = states.last()
            return OrderStatusRun(
                orderId = last.orderId,
                status = last.status,
                stateCount = states.size,
                firstVersion = first.version,
                lastVersion = last.version,
                startedAt = first.lastEventAt,
                endedAt = last.lastEventAt,
                finalState = last,
            )
        }
    }
}

/**
 * Lifecycle transition emitted after adjacent unchanged statuses are collapsed.
 */
data class OrderTransition(
    val orderId: String,
    val previousStatus: OrderStatus,
    val currentStatus: OrderStatus,
    val occurredAt: Instant?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(previous: OrderState, current: OrderState): OrderTransition =
            OrderTransition(
                orderId = current.orderId,
                previousStatus = previous.status,
                currentStatus = current.status,
                occurredAt = current.lastEventAt,
            )
    }
}

/**
 * Sanitized audit entry for tests and debug logging.
 */
data class OrderAuditEntry(
    val sequence: Int,
    val eventType: String,
    val orderId: String,
    val status: OrderStatus,
    val quantity: Int,
    val amountCents: Long,
    val occurredAt: Instant,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(sequence: Int, event: OrderEvent): OrderAuditEntry {
            sequence.requirePositiveNumber("sequence")
            return OrderAuditEntry(
                sequence = sequence,
                eventType = event.eventType,
                orderId = event.orderId,
                status = event.impliedStatus(),
                quantity = if (event is LineAdded) event.quantity else 0,
                amountCents = if (event is PaymentAuthorized) event.amountCents else 0,
                occurredAt = event.occurredAt,
            )
        }
    }
}

private val tokenPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

private fun normalizeToken(value: String, fieldName: String, maxLength: Int = 64): String {
    val normalized = value.trim()
    normalized.requireNotBlank(fieldName)
    normalized.length.requireInRange(1, maxLength, "$fieldName.length")
    require(normalized.none(Char::isISOControl)) { "$fieldName must not contain control characters." }
    require(normalized.matches(tokenPattern)) {
        "$fieldName must be a printable ASCII token."
    }
    return normalized
}

private fun normalizeText(value: String, fieldName: String, maxLength: Int): String {
    val normalized = value.trim()
    normalized.requireNotBlank(fieldName)
    normalized.length.requireInRange(1, maxLength, "$fieldName.length")
    require(normalized.none(Char::isISOControl)) { "$fieldName must not contain control characters." }
    return normalized
}

private fun OrderEvent.impliedStatus(): OrderStatus =
    when (this) {
        is OrderCreated      -> OrderStatus.CREATED
        is LineAdded         -> OrderStatus.CREATED
        is PaymentAuthorized -> OrderStatus.PAID
        is ShipmentStarted   -> OrderStatus.SHIPPED
        is OrderCancelled    -> OrderStatus.CANCELLED
    }

package io.bluetape4k.workshop.exposed.javers.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table that stores the current order row.
 *
 * ## Behavior / Contract
 * - JaVers stores historical snapshots separately in Redis.
 * - This table stores only the current materialized state.
 * - [totalAmount] uses DECIMAL(19,4) for deterministic monetary examples.
 *
 * ```kotlin
 * transaction {
 *     SchemaUtils.create(OrderTable)
 * }
 * ```
 */
object OrderTable: Table("orders") {
    val id = varchar("id", 64)
    val customerId = varchar("customer_id", 64)
    val status = enumerationByName("status", 16, OrderStatus::class)
    val totalAmount = decimal("total_amount", precision = 19, scale = 4)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Maps an Exposed [ResultRow] to the workshop [Order] aggregate.
 */
fun ResultRow.toOrder(): Order =
    Order(
        id = this[OrderTable.id],
        customerId = this[OrderTable.customerId],
        status = this[OrderTable.status],
        totalAmount = this[OrderTable.totalAmount],
    )

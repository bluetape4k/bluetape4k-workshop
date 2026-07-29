package io.bluetape4k.workshop.exposed.javers.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * 현재 order row를 저장하는 Exposed table이다.
 *
 * ## 동작 / 계약
 * - JaVers는 historical snapshot을 Redis에 별도로 저장한다.
 * - 이 table은 현재 materialized 상태만 저장한다.
 * - 결정적인 금액 예제를 위해 [totalAmount]는 DECIMAL(19,4)를 사용한다.
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
 * Exposed [ResultRow]를 워크숍 [Order] aggregate로 매핑한다.
 */
fun ResultRow.toOrder(): Order =
    Order(
        id = this[OrderTable.id],
        customerId = this[OrderTable.customerId],
        status = this[OrderTable.status],
        totalAmount = this[OrderTable.totalAmount],
    )

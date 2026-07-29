package io.bluetape4k.workshop.messaging.fallback.domain

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * order domain row 를 위한 Exposed table 입니다.
 */
object OrderTable : LongIdTable("orders") {
    val customerId = varchar("customer_id", 80)
    val product = varchar("product", 120)
    val quantity = integer("quantity")
    val status = enumerationByName("status", 20, OrderStatus::class).default(OrderStatus.PENDING)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
}

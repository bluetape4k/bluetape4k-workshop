package io.bluetape4k.workshop.messaging.outbox.domain

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * [Order] entity 를 위한 Exposed table 입니다.
 *
 * ## Schema
 * - `id` — auto-increment Long primary key 입니다.
 * - `customer_id` — placing customer 의 identifier 입니다.
 * - `product` — product name 입니다.
 * - `quantity` — ordered quantity 입니다. 양수여야 합니다.
 * - `status` — 현재 [OrderStatus] 입니다. 기본값은 [OrderStatus.PENDING] 입니다.
 * - `created_at` — row creation wall-clock time 입니다.
 * - `updated_at` — 마지막 update wall-clock time 입니다.
 */
object OrderTable : LongIdTable("orders") {
    val customerId = varchar("customer_id", 100)
    val product = varchar("product", 255)
    val quantity = integer("quantity")
    val status = enumerationByName("status", 20, OrderStatus::class).default(OrderStatus.PENDING)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
}

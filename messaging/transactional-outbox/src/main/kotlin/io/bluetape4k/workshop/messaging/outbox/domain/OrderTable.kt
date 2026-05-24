package io.bluetape4k.workshop.messaging.outbox.domain

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Exposed table for [Order] entities.
 *
 * ## Schema
 * - `id`          — auto-increment Long primary key
 * - `customer_id` — identifier of the placing customer
 * - `product`     — product name
 * - `quantity`    — ordered quantity (positive)
 * - `status`      — current [OrderStatus], defaults to [OrderStatus.PENDING]
 * - `created_at`  — wall-clock time of row creation
 * - `updated_at`  — wall-clock time of last update
 */
object OrderTable : LongIdTable("orders") {
    val customerId = varchar("customer_id", 100)
    val product = varchar("product", 255)
    val quantity = integer("quantity")
    val status = enumerationByName("status", 20, OrderStatus::class).default(OrderStatus.PENDING)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
}

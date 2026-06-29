package io.bluetape4k.workshop.messaging.fallback.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Internal transactional writer for order domain rows.
 *
 * This class intentionally writes only [OrderTable]. Publication fallback rows
 * are handled after the domain transaction by higher-level orchestration.
 */
@Service
class TransactionalOrderWriter {

    companion object : KLogging()

    @Transactional
    fun saveOrder(customerId: String, product: String, quantity: Int): OrderRecord {
        validate(customerId, product, quantity)

        val orderId = OrderTable.insertAndGetId {
            it[OrderTable.customerId] = customerId
            it[OrderTable.product] = product
            it[OrderTable.quantity] = quantity
            it[OrderTable.status] = OrderStatus.PENDING
        }

        log.debug { "Saved order id=${orderId.value} without fallback publication row." }

        return getOrder(orderId.value)
    }

    @Transactional(readOnly = true)
    fun getOrder(orderId: Long): OrderRecord {
        val row = OrderTable.selectAll()
            .where { OrderTable.id eq orderId }
            .singleOrNull()
            ?: throw NoSuchElementException("Order $orderId not found")

        return toRecord(row)
    }

    @Transactional(readOnly = true)
    fun findOrders(): List<OrderRecord> =
        OrderTable.selectAll().map { row -> toRecord(row) }

    private fun toRecord(row: ResultRow): OrderRecord =
        OrderRecord(
            id = row[OrderTable.id].value,
            customerId = row[OrderTable.customerId],
            product = row[OrderTable.product],
            quantity = row[OrderTable.quantity],
            status = row[OrderTable.status],
            createdAt = row[OrderTable.createdAt],
            updatedAt = row[OrderTable.updatedAt],
        )

    private fun validate(customerId: String, product: String, quantity: Int) {
        customerId.requireNotBlank("customerId")
        product.requireNotBlank("product")
        quantity.requirePositiveNumber("quantity")
        require(customerId.length <= 80) { "customerId must be 80 characters or less" }
        require(product.length <= 120) { "product must be 120 characters or less" }
        require(quantity <= 1000) { "quantity must be 1000 or less" }
        require(customerId.none(Char::isISOControl)) { "customerId must not contain control characters" }
        require(product.none(Char::isISOControl)) { "product must not contain control characters" }
    }
}

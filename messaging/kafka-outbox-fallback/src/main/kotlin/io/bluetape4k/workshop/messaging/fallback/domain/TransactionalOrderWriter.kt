package io.bluetape4k.workshop.messaging.fallback.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * order domain row 를 위한 internal transactional writer 입니다.
 *
 * 이 class 는 의도적으로 [OrderTable] 만 씁니다. publication fallback row 는 domain transaction 이후 higher-level orchestration 이 처리합니다.
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
        customerId.length.requireInRange(1, 80, "customerId.length")
        product.length.requireInRange(1, 120, "product.length")
        quantity.requireInRange(1, 1000, "quantity")
        customerId.count(Char::isISOControl).requireInRange(0, 0, "customerId.controlCharacters")
        product.count(Char::isISOControl).requireInRange(0, 0, "product.controlCharacters")
    }
}

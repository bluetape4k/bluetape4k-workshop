package io.bluetape4k.workshop.exposed.mvc.vt.order.repository

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.OrderLineDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.mapper.toOrderLineDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderLineTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository
import java.util.concurrent.ExecutorService

@Repository
class OrderLineRepository(
    private val db: Database,
    private val executor: ExecutorService,
) {
    companion object : KLogging()

    fun findByOrderId(orderId: Long): VirtualFuture<List<OrderLineDTO>> = virtualFuture(executor) {
        transaction(db) {
            OrderLineTable.selectAll()
                .where { OrderLineTable.orderId eq orderId }
                .map { it.toOrderLineDTO() }
        }
    }
}

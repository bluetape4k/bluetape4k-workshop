package io.bluetape4k.workshop.exposed.mvc.vt.order.repository

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.mapper.toOrderDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository
import java.util.concurrent.ExecutorService

@Repository
class OrderRepository(
    private val db: Database,
    private val executor: ExecutorService,
) {
    companion object : KLogging()

    fun findAll(): VirtualFuture<List<OrderDTO>> = virtualFuture(executor) {
        transaction(db) {
            OrderTable.selectAll().map { it.toOrderDTO() }
        }
    }

    fun findById(id: Long): VirtualFuture<OrderDTO?> = virtualFuture(executor) {
        transaction(db) {
            OrderTable.selectAll()
                .where { OrderTable.id eq id }
                .singleOrNull()
                ?.toOrderDTO()
        }
    }
}

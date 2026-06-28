package io.bluetape4k.workshop.exposed.javers.persistence

import io.bluetape4k.javers.latestSnapshotOrNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import org.javers.core.Javers
import org.javers.core.diff.Diff
import org.javers.core.metamodel.`object`.CdoSnapshot
import org.javers.repository.jql.QueryBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Service that writes JaVers snapshots to Redis and the current order row to Exposed.
 *
 * ## Behavior / Contract
 * - [place] commits the initial [Order] snapshot before upserting the current row.
 * - [markPaid] commits an updated snapshot before materializing the paid state.
 * - [getHistory] reads Redis-backed JaVers snapshots oldest-first.
 * - [delete] writes a terminal snapshot before removing the current row.
 *
 * ```kotlin
 * val service = RedisOrderAuditFactory.create("orders", redisson)
 * service.place("alice", Order("order-1", "customer-1", OrderStatus.PLACED, BigDecimal("19.99")))
 * val history = service.getHistory("order-1")
 * ```
 */
class OrderAuditService(
    private val javers: Javers,
) {
    companion object: KLogging()

    /**
     * Commits [order] to JaVers and upserts the current Exposed row.
     */
    fun place(author: String, order: Order) {
        author.requireNotBlank("author")
        javers.commit(author, order)
        transaction {
            OrderTable.upsert {
                it[id] = order.id
                it[customerId] = order.customerId
                it[status] = order.status
                it[totalAmount] = order.totalAmount
            }
        }
        log.debug { "Placed and audited order id=${order.id} by $author" }
    }

    /**
     * Marks the current order as [OrderStatus.PAID] and records an update snapshot.
     */
    fun markPaid(author: String, orderId: String): Order {
        author.requireNotBlank("author")
        orderId.requireNotBlank("orderId")

        val current = findCurrent(orderId)
            ?: throw IllegalArgumentException("Order was not found. orderId=$orderId")
        val paid = current.copy(status = OrderStatus.PAID)

        javers.commit(author, paid)
        transaction {
            OrderTable.upsert {
                it[id] = paid.id
                it[customerId] = paid.customerId
                it[status] = paid.status
                it[totalAmount] = paid.totalAmount
            }
        }
        log.debug { "Marked order id=$orderId as paid by $author" }
        return paid
    }

    /**
     * Finds the current materialized order row, or `null` when it was never placed or was deleted.
     */
    fun findCurrent(orderId: String): Order? {
        orderId.requireNotBlank("orderId")
        return transaction {
            OrderTable.selectAll()
                .where { OrderTable.id eq orderId }
                .singleOrNull()
                ?.toOrder()
        }
    }

    /**
     * Returns all JaVers snapshots for [orderId], oldest-first.
     */
    fun getHistory(orderId: String): List<CdoSnapshot> {
        orderId.requireNotBlank("orderId")
        val query = QueryBuilder.byInstanceId(orderId, Order::class.java)
            .build()
        return javers.findSnapshots(query)
            .sortedBy { it.commitMetadata.commitDate }
    }

    /**
     * Returns the latest JaVers snapshot for [orderId], or `null` if no commits exist.
     */
    fun getLatestSnapshot(orderId: String): CdoSnapshot? {
        orderId.requireNotBlank("orderId")
        return javers.latestSnapshotOrNull<Order>(orderId)
    }

    /**
     * Computes a JaVers [Diff] without persisting either order instance.
     */
    fun diff(oldOrder: Order, newOrder: Order): Diff =
        javers.compare(oldOrder, newOrder)

    /**
     * Records a terminal JaVers snapshot and removes the current row.
     */
    fun delete(author: String, orderId: String) {
        author.requireNotBlank("author")
        orderId.requireNotBlank("orderId")

        val current = findCurrent(orderId)
            ?: throw IllegalArgumentException("Order was not found. orderId=$orderId")

        javers.commitShallowDelete(author, current)
        transaction {
            OrderTable.deleteWhere { OrderTable.id eq orderId }
        }
        log.debug { "Deleted and audited order id=$orderId by $author" }
    }
}

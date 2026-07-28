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
 * JaVers snapshot은 Redis에 쓰고 현재 order row는 Exposed에 쓰는 서비스이다.
 *
 * ## 동작 / 계약
 * - [place]는 현재 row를 upsert하기 전에 초기 [Order] snapshot을 commit한다.
 * - [markPaid]는 paid 상태를 materialize하기 전에 갱신 snapshot을 commit한다.
 * - [getHistory]는 Redis-backed JaVers snapshot을 오래된 순서로 읽는다.
 * - [delete]는 현재 row를 제거하기 전에 terminal snapshot을 쓴다.
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
     * [order]를 JaVers에 commit하고 현재 Exposed row를 upsert한다.
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
     * 현재 order를 [OrderStatus.PAID]로 표시하고 update snapshot을 기록한다.
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
     * 현재 materialized order row를 찾는다. 아직 place되지 않았거나 삭제된 경우 `null`을 반환한다.
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
     * [orderId]의 모든 JaVers snapshot을 오래된 순서로 반환한다.
     */
    fun getHistory(orderId: String): List<CdoSnapshot> {
        orderId.requireNotBlank("orderId")
        val query = QueryBuilder.byInstanceId(orderId, Order::class.java)
            .build()
        return javers.findSnapshots(query)
            .sortedBy { it.commitMetadata.commitDate }
    }

    /**
     * [orderId]의 최신 JaVers snapshot을 반환하고, commit이 없으면 `null`을 반환한다.
     */
    fun getLatestSnapshot(orderId: String): CdoSnapshot? {
        orderId.requireNotBlank("orderId")
        return javers.latestSnapshotOrNull<Order>(orderId)
    }

    /**
     * 두 order 인스턴스 중 어느 것도 영속화하지 않고 JaVers [Diff]를 계산한다.
     */
    fun diff(oldOrder: Order, newOrder: Order): Diff =
        javers.compare(oldOrder, newOrder)

    /**
     * terminal JaVers snapshot을 기록하고 현재 row를 제거한다.
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

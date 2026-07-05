package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import io.bluetape4k.support.requireNotBlank
import org.javers.core.Javers
import org.javers.core.JaversBuilder
import org.javers.core.diff.Diff
import org.javers.core.metamodel.`object`.CdoSnapshot
import org.javers.repository.jql.QueryBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.Serializable
import java.math.BigDecimal

/**
 * JaVers configuration for the workshop in-memory audit repository.
 */
@Configuration(proxyBeanMethods = false)
class OrderAuditConfiguration {

    /**
     * Creates the in-memory JaVers instance used by this workshop module.
     */
    @Bean
    fun orderJavers(): Javers =
        JaversBuilder.javers().build()
}

/**
 * Learner-facing audit DTO with safe, synthetic order fields only.
 */
data class OrderAuditEntry(
    val orderId: String,
    val status: OrderStatus,
    val totalAmount: BigDecimal,
    val lineCount: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * JaVers audit boundary for order aggregate snapshots and diffs.
 *
 * ## Behavior / Contract
 * - [commitAfterTransaction] writes to JaVers only after the surrounding transaction commits.
 * - [getHistory] returns snapshots for one aggregate id, oldest-first.
 * - [getAuditTrail] exposes safe synthetic fields for README examples.
 */
@Service
class OrderAuditService(
    private val javers: Javers,
) {

    /**
     * Commits [order] to JaVers after the current transaction commits.
     */
    fun commitAfterTransaction(
        author: String,
        order: Order,
        properties: Map<String, String> = emptyMap(),
    ) {
        author.requireNotBlank("author")
        val orderToAudit = order.withoutEvents()

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        javers.commit(author, orderToAudit, properties)
                    }
                },
            )
        } else {
            javers.commit(author, orderToAudit, properties)
        }
    }

    /**
     * Returns all JaVers snapshots for [orderId], oldest-first.
     */
    fun getHistory(orderId: OrderId): List<CdoSnapshot> {
        val query = QueryBuilder.byInstanceId(orderId, Order::class.java)
            .build()
        return javers.findSnapshots(query)
            .sortedBy { it.commitMetadata.commitDate }
    }

    /**
     * Computes a JaVers diff between two aggregate instances.
     */
    fun diff(oldOrder: Order, newOrder: Order): Diff =
        javers.compare(oldOrder.withoutEvents(), newOrder.withoutEvents())

    /**
     * Returns safe, synthetic audit entries for documentation examples.
     */
    fun getAuditTrail(orderId: OrderId): List<OrderAuditEntry> =
        getHistory(orderId).map { snapshot ->
            val id = snapshot.getPropertyValue("id") as OrderId
            val status = snapshot.getPropertyValue("status") as OrderStatus
            val lines = snapshot.getPropertyValue("lines").asOrderLines()
            val totalAmount = snapshot.getPropertyValue("totalAmount") as? BigDecimal
                ?: lines.totalAmount()
            val lineCount = snapshot.getPropertyValue("lineCount") as? Int
                ?: lines.size

            OrderAuditEntry(
                orderId = id.value,
                status = status,
                totalAmount = totalAmount,
                lineCount = lineCount,
            )
        }

    private fun Any?.asOrderLines(): List<OrderLine> =
        when (this) {
            is List<*> -> filterIsInstance<OrderLine>()
            else -> emptyList()
        }

    private fun List<OrderLine>.totalAmount(): BigDecimal =
        fold(BigDecimal.ZERO) { total, line ->
            total + line.unitPrice.amount.multiply(BigDecimal(line.quantity))
        }
}

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
 * workshop 의 in-memory audit repository 를 위한 JaVers 설정입니다.
 */
@Configuration(proxyBeanMethods = false)
class OrderAuditConfiguration {

    /**
     * 이 workshop module 에서 사용하는 in-memory JaVers instance 를 생성합니다.
     */
    @Bean
    fun orderJavers(): Javers =
        JaversBuilder.javers().build()
}

/**
 * 안전한 synthetic order field 만 담는 학습자용 audit DTO 입니다.
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
 * order aggregate snapshot 과 diff 를 담당하는 JaVers audit 경계입니다.
 *
 * ## 동작 / 계약
 * - [commitAfterTransaction] 은 주변 transaction 이 commit 된 뒤에만 JaVers 에 기록합니다.
 * - [getHistory] 는 단일 aggregate id 의 snapshot 을 오래된 순서로 반환합니다.
 * - [getAuditTrail] 은 README 예제에 사용할 안전한 synthetic field 만 노출합니다.
 */
@Service
class OrderAuditService(
    private val javers: Javers,
) {

    /**
     * 현재 transaction 이 commit 된 뒤 [order] 를 JaVers 에 commit 합니다.
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
     * [orderId] 의 모든 JaVers snapshot 을 오래된 순서로 반환합니다.
     */
    fun getHistory(orderId: OrderId): List<CdoSnapshot> {
        val query = QueryBuilder.byInstanceId(orderId, Order::class.java)
            .build()
        return javers.findSnapshots(query)
            .sortedBy { it.commitMetadata.commitDate }
    }

    /**
     * 두 aggregate instance 사이의 JaVers diff 를 계산합니다.
     */
    fun diff(oldOrder: Order, newOrder: Order): Diff =
        javers.compare(oldOrder.withoutEvents(), newOrder.withoutEvents())

    /**
     * 문서 예제에 사용할 안전한 synthetic audit entry 를 반환합니다.
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

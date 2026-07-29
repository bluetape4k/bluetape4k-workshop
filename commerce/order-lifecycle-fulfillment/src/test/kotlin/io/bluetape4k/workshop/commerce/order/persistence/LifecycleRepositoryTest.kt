package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import org.junit.jupiter.api.Test
import java.util.UUID

internal class LifecycleRepositoryTest {
    private val orders = OrderRepository()
    private val payments = PaymentAttemptRepository()
    private val audits = LifecycleAuditRepository()

    @Test
    fun `aggregate repositories keep revisions independent in PostgreSQL`() {
        val orderId = UUID.fromString("019c6e50-0180-7b3f-a021-65f8f58bdb30")
        val paymentId = UUID.fromString("019c6e50-0180-7b3f-a021-65f8f58bdb31")

        withTables(TestDB.POSTGRESQL, OrderTable, PaymentAttemptTable, LifecycleAuditTable) {
            orders.save(
                OrderRecord(
                    orderId,
                    "tenant-a",
                    "customer-ref",
                    OrderStatus.SUBMITTED,
                    providerMode = ProviderMode.SUCCESS
                )
            )
            payments.save(PaymentAttemptRecord(paymentId, orderId, PaymentStatus.CREATED))

            orders.transition(orderId, 0, OrderStatus.SUBMITTED, OrderStatus.ACCEPTED).shouldBeTrue()
            payments.transition(paymentId, 0, PaymentStatus.CREATED, PaymentStatus.AUTHORIZING).shouldBeTrue()
            payments.transition(paymentId, 1, PaymentStatus.AUTHORIZING, PaymentStatus.SUCCEEDED).shouldBeTrue()

            orders.findById(orderId).revision shouldBeEqualTo 1L
            payments.findById(paymentId).revision shouldBeEqualTo 2L

            audits.append(
                LifecycleAuditRecord(
                    eventId = UUID.fromString("019c6e50-0180-7b3f-a021-65f8f58bdb32"),
                    orderId = orderId,
                    aggregateType = AggregateType.PAYMENT_ATTEMPT,
                    aggregateId = paymentId,
                    revision = 2,
                    fromStatus = PaymentStatus.AUTHORIZING.name,
                    toStatus = PaymentStatus.SUCCEEDED.name,
                    reasonCode = null,
                    actorType = "PROVIDER"
                )
            )
            audits.findByOrderId(orderId).single().revision shouldBeEqualTo 2L
        }
    }
}

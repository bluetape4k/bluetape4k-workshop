package io.bluetape4k.workshop.spring.modulith.events.b.transactions

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.events.util.IntegrationTest
import org.amshove.kluent.shouldBeTrue
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertFailsWith

@IntegrationTest
class OrderEventPublicationTests(
    @Autowired private val orders: OrderManagement,
    @Autowired private val listener: OrderListeners.ConfigurableEventListener,
    @Autowired private val txListener: OrderListeners.ConfigurableTransactionalListener,
) {
    companion object: KLogging()

    @Test
    fun `주문 완료 시 주문이 저장된다`() {
        val order = Order()
        orders.completeOrder(order)
        order.isPersisted.shouldBeTrue()
    }

    @Test
    fun `Event Listener에서 예외 발생 시 Transaction은 중단된다`() {
        listener.fail = true

        assertFailsWith<RuntimeException> {
            orders.completeOrder(Order())
        }

        listener.fail = false
    }

    @Test
    fun `예외 발생 시 Transactional Event Listener 는 호출되지 않는다`() {
        assertFailsWith<RuntimeException> {
            orders.failToCompleteOrder(Order())
        }
    }

    @Test
    fun `Transaction Event Listener 작업 실패 시에도 호출자에게는 예외가 전파되지 않는다`() {
        txListener.fail = true

        // Transaction Listener에서 예외가 발생해도, 호출자에게는 예외가 전파되지 않는다.
        orders.completeOrder(Order())

        txListener.fail = false
    }
}

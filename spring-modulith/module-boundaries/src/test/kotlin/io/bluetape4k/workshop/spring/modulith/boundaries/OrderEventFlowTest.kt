package io.bluetape4k.workshop.spring.modulith.boundaries

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.spring.modulith.boundaries.notification.NotificationOutbox
import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.OrderRequest
import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.OrderingService
import io.bluetape4k.workshop.spring.modulith.boundaries.payment.PaymentLedger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [ModuleBoundariesApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderEventFlowTest @Autowired constructor(
    private val orderingService: OrderingService,
    private val paymentLedger: PaymentLedger,
    private val notificationOutbox: NotificationOutbox,
) {

    @BeforeEach
    fun resetModuleState() {
        paymentLedger.reset()
        notificationOutbox.reset()
    }

    @Test
    fun `placing an order publishes an event to payment and notification modules`() {
        val receipt = orderingService.placeOrder(
            OrderRequest(sku = "course-ddd", quantity = 2, customerId = "customer-42")
        )

        receipt.sku shouldBeEqualTo "course-ddd"
        receipt.quantity shouldBeEqualTo 2
        receipt.totalCents shouldBeEqualTo 258_00

        val payment = paymentLedger.find(receipt.orderId).shouldNotBeNull()
        payment.customerId shouldBeEqualTo "customer-42"
        payment.amountCents shouldBeEqualTo 258_00

        val notification = notificationOutbox.find(receipt.orderId).shouldNotBeNull()
        notification.customerId shouldBeEqualTo "customer-42"
        notification.message shouldContain receipt.orderId

        paymentLedger.all() shouldHaveSize 1
        notificationOutbox.all() shouldHaveSize 1
    }

    @Test
    fun `placing an order rejects unknown catalog item`() {
        val error = assertFailsWith<IllegalArgumentException> {
            orderingService.placeOrder(
                OrderRequest(sku = "missing-course", quantity = 1, customerId = "customer-42")
            )
        }

        error.message shouldContain "catalog item not found"
        paymentLedger.all() shouldHaveSize 0
        notificationOutbox.all() shouldHaveSize 0
    }

    @Test
    fun `placing an order rejects non-positive quantity`() {
        val error = assertFailsWith<IllegalArgumentException> {
            orderingService.placeOrder(
                OrderRequest(sku = "course-ddd", quantity = 0, customerId = "customer-42")
            )
        }

        error.message shouldContain "quantity must be positive"
        paymentLedger.all() shouldHaveSize 0
        notificationOutbox.all() shouldHaveSize 0
    }
}

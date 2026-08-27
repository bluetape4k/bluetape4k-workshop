package io.bluetape4k.workshop.commerce.order.domain

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test

internal class LifecyclePoliciesTest {
    @Test
    fun `payment success does not complete fulfillment`() {
        PaymentPolicy.transition(PaymentStatus.AUTHORIZING, PaymentStatus.SUCCEEDED) shouldBeEqualTo
            PaymentStatus.SUCCEEDED
        OrderPolicy.transition(OrderStatus.ACCEPTED, OrderStatus.FULFILLMENT_IN_PROGRESS) shouldBeEqualTo
            OrderStatus.FULFILLMENT_IN_PROGRESS
        assertFailsWith<InvalidTransition> {
            FulfillmentPolicy.transition(FulfillmentStatus.REQUESTED, FulfillmentStatus.DELIVERED)
        }
    }

    @Test
    fun `terminal states reject stale transitions`() {
        assertFailsWith<InvalidTransition> {
            PaymentPolicy.transition(PaymentStatus.SUCCEEDED, PaymentStatus.AUTHORIZING)
        }
        assertFailsWith<InvalidTransition> {
            RefundPolicy.transition(RefundStatus.SUCCEEDED, RefundStatus.PENDING_PROVIDER)
        }
        assertFailsWith<InvalidTransition> {
            CancellationPolicy.transition(CancellationStatus.APPROVED, CancellationStatus.REQUESTED)
        }
    }

    @Test
    fun `fulfillment supports independent shipping progression`() {
        FulfillmentPolicy.transition(FulfillmentStatus.REQUESTED, FulfillmentStatus.ALLOCATED) shouldBeEqualTo
            FulfillmentStatus.ALLOCATED
        FulfillmentPolicy.transition(FulfillmentStatus.ALLOCATED, FulfillmentStatus.PICKING) shouldBeEqualTo
            FulfillmentStatus.PICKING
        FulfillmentPolicy.transition(FulfillmentStatus.PICKING, FulfillmentStatus.SHIPPED) shouldBeEqualTo
            FulfillmentStatus.SHIPPED
        FulfillmentPolicy.transition(FulfillmentStatus.SHIPPED, FulfillmentStatus.DELIVERED) shouldBeEqualTo
            FulfillmentStatus.DELIVERED
    }

    @Test
    fun `line cancellation remains independent from refund processing`() {
        CancellationPolicy.transition(CancellationStatus.REQUESTED, CancellationStatus.APPROVED) shouldBeEqualTo
            CancellationStatus.APPROVED
        RefundPolicy.transition(RefundStatus.REQUESTED, RefundStatus.PENDING_PROVIDER) shouldBeEqualTo
            RefundStatus.PENDING_PROVIDER
    }
}

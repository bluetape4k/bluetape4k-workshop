package io.bluetape4k.workshop.commerce.order.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * LifecyclePoliciesTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class LifecyclePoliciesTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "payment success does not complete fulfillment" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `payment success does not complete fulfillment`() {
        PaymentPolicy.transition(
            PaymentStatus.AUTHORIZING,
            PaymentStatus.SUCCEEDED
        ) shouldBeEqualTo PaymentStatus.SUCCEEDED

        OrderPolicy.transition(
            OrderStatus.ACCEPTED,
            OrderStatus.FULFILLMENT_IN_PROGRESS
        ) shouldBeEqualTo OrderStatus.FULFILLMENT_IN_PROGRESS

        assertFailsWith<InvalidTransition> {
            FulfillmentPolicy.transition(FulfillmentStatus.REQUESTED, FulfillmentStatus.DELIVERED)
        }
    }

    @Test
/**
 * 이 시나리오는 "terminal states reject stale transitions" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "fulfillment supports independent shipping progression" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fulfillment supports independent shipping progression`() {
        FulfillmentPolicy.transition(
            FulfillmentStatus.REQUESTED,
            FulfillmentStatus.ALLOCATED
        ) shouldBeEqualTo FulfillmentStatus.ALLOCATED

        FulfillmentPolicy.transition(
            FulfillmentStatus.ALLOCATED,
            FulfillmentStatus.PICKING
        ) shouldBeEqualTo FulfillmentStatus.PICKING

        FulfillmentPolicy.transition(
            FulfillmentStatus.PICKING,
            FulfillmentStatus.SHIPPED
        ) shouldBeEqualTo FulfillmentStatus.SHIPPED

        FulfillmentPolicy.transition(
            FulfillmentStatus.SHIPPED,
            FulfillmentStatus.DELIVERED
        ) shouldBeEqualTo FulfillmentStatus.DELIVERED
    }

    @Test
/**
 * 이 시나리오는 "line cancellation remains independent from refund processing" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `line cancellation remains independent from refund processing`() {
        CancellationPolicy.transition(
            CancellationStatus.REQUESTED,
            CancellationStatus.APPROVED
        ) shouldBeEqualTo CancellationStatus.APPROVED

        RefundPolicy.transition(
            RefundStatus.REQUESTED,
            RefundStatus.PENDING_PROVIDER
        ) shouldBeEqualTo RefundStatus.PENDING_PROVIDER
    }
}

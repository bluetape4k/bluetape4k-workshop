package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import org.junit.jupiter.api.Test
import java.util.*

/**
 * LifecycleRepositoryTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class LifecycleRepositoryTest {

    companion object: KLogging()

/**
 * orders은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val orders = OrderRepository()
/**
 * payments은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val payments = PaymentAttemptRepository()
/**
 * audits은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val audits = LifecycleAuditRepository()

    @Test
/**
 * 이 시나리오는 "aggregate repositories keep revisions independent in PostgreSQL" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
                .also { log.debug { "PaymentAttemptRecord=$it" } }

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
            ).also { log.debug { "LifecycleAuditRecord=$it" } }
            
            audits.findByOrderId(orderId).single().revision shouldBeEqualTo 2L
        }
    }
}

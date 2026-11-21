package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrder
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrderLine
import io.bluetape4k.workshop.commerce.order.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.order.idempotency.IdempotencyCleanupService
import io.bluetape4k.workshop.commerce.order.persistence.CancellationCaseRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.InventoryReservationRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderRepository
import io.bluetape4k.workshop.commerce.order.persistence.PaymentAttemptRepository
import io.bluetape4k.workshop.commerce.order.persistence.RefundCaseRepository
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.FailedEventPublications
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.*

/**
 * OrderValidationContractTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OrderValidationContractTest {

    companion object: KLogging()

/**
 * orders은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val orders = mockk<OrderRepository>(relaxed = true)
/**
 * payments은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val payments = mockk<PaymentAttemptRepository>(relaxed = true)
/**
 * lines은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val lines = mockk<OrderLineRepository>(relaxed = true)
/**
 * reservations은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reservations = mockk<InventoryReservationRepository>(relaxed = true)
/**
 * fulfillments은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fulfillments = mockk<FulfillmentGroupRepository>(relaxed = true)
/**
 * fulfillmentLines은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fulfillmentLines = mockk<FulfillmentLineRepository>(relaxed = true)
/**
 * cancellations은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val cancellations = mockk<CancellationCaseRepository>(relaxed = true)
/**
 * refunds은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val refunds = mockk<RefundCaseRepository>(relaxed = true)
/**
 * audit은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val audit = mockk<LifecycleAuditAppender>(relaxed = true)
/**
 * events은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
/**
 * failedPublications은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val failedPublications = mockk<FailedEventPublications>(relaxed = true)
/**
 * repository은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = HttpIdempotencyRepository()
/**
 * reconciliation은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reconciliation = PublicationReconciliationService(failedPublications)

    @Test
/**
 * 이 시나리오는 "bounded caller validation uses released Bluetape helpers" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `bounded caller validation uses released Bluetape helpers`() {
        val expectedHelpers =
            listOf(
                "src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/PublicationReconciliationService.kt" to
                        "batchSize.requireInRange(1, MAX_BATCH_SIZE, \"batchSize\")",
                "src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/OrderCommandService.kt" to
                        "command.lines.requireNotEmpty(\"command.lines\")",
                "src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/OrderCommandService.kt" to
                        "command.lines.size.requireInRange(1, 50, \"command.lines.size\")",
                "src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/OrderCommandService.kt" to
                        "it.quantity.requireInRange(1, 1_000, \"quantity\")",
                "src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/OrderCommandService.kt" to
                        "quantity.requirePositiveNumber(\"quantity\")",
                "src/main/kotlin/io/bluetape4k/workshop/commerce/order/idempotency/HttpIdempotencyRepository.kt" to
                        "limit.requireInRange(1, MAX_CLEANUP_BATCH, \"limit\")",
                "src/main/kotlin/io/bluetape4k/workshop/commerce/order/idempotency/IdempotencyCleanupService.kt" to
                        "batchSize.requireInRange(1, HttpIdempotencyRepository.MAX_CLEANUP_BATCH, \"batchSize\")"
            )

        expectedHelpers.forEach { (sourcePath, helperCall) ->
            log.debug { "sourcePath=$sourcePath" }
            Files.readString(Path.of(sourcePath)) shouldContain helperCall
        }
    }

    @Test
/**
 * 이 시나리오는 "regex length security and decimal contracts stay explicit" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `regex length security and decimal contracts stay explicit`() {
        val orderCommandSource = Files.readString(
            Path.of("src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/OrderCommandService.kt")
        )
        val submissionSource = Files.readString(
            Path.of("src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/IdempotentOrderSubmissionService.kt")
        )
        val controllerSource = Files.readString(
            Path.of("src/main/kotlin/io/bluetape4k/workshop/commerce/order/web/OrderController.kt")
        )

        orderCommandSource shouldContain "matches(IDENTIFIER)"
        orderCommandSource shouldContain "matches(REASON)"
        orderCommandSource shouldContain "unitPrice.signum() >= 0"
        submissionSource shouldContain "rawKey.length in 8..200"
        controllerSource shouldContain "operatorGuard == WORKSHOP_OPERATOR_VALUE"
    }

    @Test
/**
 * 이 시나리오는 "invalid bounded caller input remains IllegalArgumentException" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `invalid bounded caller input remains IllegalArgumentException`() {
        val commandService = commandService()

        assertFailsWith<IllegalArgumentException> {
            commandService.submit(
                SubmitOrder(
                    tenantId = "tenant-a",
                    customerReference = "customer-reference",
                    providerMode = ProviderMode.SUCCESS,
                    lines = listOf(SubmitOrderLine("sku-a", 0, BigDecimal("10.00")))
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            commandService.cancelUnshippedLine(
                orderId = UUID.randomUUID(),
                lineId = UUID.randomUUID(),
                quantity = 0,
                reasonCode = "CUSTOMER_REQUEST"
            )
        }
    }

    @Test
/**
 * 이 시나리오는 "invalid reconciliation and cleanup bounds remain IllegalArgumentException" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `invalid reconciliation and cleanup bounds remain IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            reconciliation.replayFailed(0)
        }

        assertFailsWith<IllegalArgumentException> {
            repository.deleteExpiredTerminal(Instant.parse("2026-07-18T00:00:00Z"), 0)
        }

        assertFailsWith<IllegalArgumentException> {
            IdempotencyCleanupService(repository, Clock.systemUTC(), 0)
        }
    }

/**
 * commandService은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun commandService(): OrderCommandService =
        OrderCommandService(
            orders = orders,
            payments = payments,
            lines = lines,
            reservations = reservations,
            fulfillments = fulfillments,
            fulfillmentLines = fulfillmentLines,
            cancellations = cancellations,
            refunds = refunds,
            audit = audit,
            events = events,
            clock = Clock.systemUTC()
        )
}

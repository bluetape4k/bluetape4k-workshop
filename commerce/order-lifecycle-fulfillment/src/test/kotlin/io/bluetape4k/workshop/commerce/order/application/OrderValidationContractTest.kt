package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
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
import java.util.UUID

internal class OrderValidationContractTest {
    private val orders = mockk<OrderRepository>(relaxed = true)
    private val payments = mockk<PaymentAttemptRepository>(relaxed = true)
    private val lines = mockk<OrderLineRepository>(relaxed = true)
    private val reservations = mockk<InventoryReservationRepository>(relaxed = true)
    private val fulfillments = mockk<FulfillmentGroupRepository>(relaxed = true)
    private val fulfillmentLines = mockk<FulfillmentLineRepository>(relaxed = true)
    private val cancellations = mockk<CancellationCaseRepository>(relaxed = true)
    private val refunds = mockk<RefundCaseRepository>(relaxed = true)
    private val audit = mockk<LifecycleAuditAppender>(relaxed = true)
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
    private val failedPublications = mockk<FailedEventPublications>(relaxed = true)
    private val repository = HttpIdempotencyRepository()
    private val reconciliation = PublicationReconciliationService(failedPublications)

    @Test
    fun `bounded caller validation uses released Bluetape helpers`() {
        val expectedHelpers =
            listOf(
                "src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/PublicationReconciliationService.kt" to
                    "batchSize.requireInRange(1, MAX_BATCH_SIZE, \"batchSize\")",
                "src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/OrderCommandService.kt" to
                    "command.lines.requireNotEmpty(\"lines\")",
                "src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/OrderCommandService.kt" to
                    "command.lines.size.requireInRange(1, 50, \"lines.size\")",
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
            Files.readString(Path.of(sourcePath)).contains(helperCall) shouldBeEqualTo true
        }
    }

    @Test
    fun `regex length security and decimal contracts stay explicit`() {
        val orderCommandSource =
            Files.readString(
                Path.of(
                    "src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/OrderCommandService.kt"
                )
            )
        val submissionSource =
            Files.readString(
                Path.of(
                    "src/main/kotlin/io/bluetape4k/workshop/commerce/order/application/IdempotentOrderSubmissionService.kt"
                )
            )
        val controllerSource =
            Files.readString(
                Path.of(
                    "src/main/kotlin/io/bluetape4k/workshop/commerce/order/web/OrderController.kt"
                )
            )

        orderCommandSource.contains("matches(IDENTIFIER)") shouldBeEqualTo true
        orderCommandSource.contains("matches(REASON)") shouldBeEqualTo true
        orderCommandSource.contains("unitPrice.signum() >= 0") shouldBeEqualTo true
        submissionSource.contains("rawKey.length in 8..200") shouldBeEqualTo true
        controllerSource.contains("operatorGuard == WORKSHOP_OPERATOR_VALUE") shouldBeEqualTo true
    }

    @Test
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
    fun `invalid reconciliation and cleanup bounds remain IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { reconciliation.replayFailed(0) }

        assertFailsWith<IllegalArgumentException> {
            repository.deleteExpiredTerminal(Instant.parse("2026-07-18T00:00:00Z"), 0)
        }

        assertFailsWith<IllegalArgumentException> {
            IdempotencyCleanupService(repository, Clock.systemUTC(), 0)
        }
    }

    private fun commandService() =
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

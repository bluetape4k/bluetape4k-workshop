package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.spring.modulith.ddd.audit.AbstractDddOrderAuditTest
import io.bluetape4k.workshop.spring.modulith.ddd.audit.fulfillment.FulfillmentReservationRepository
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.javers.core.diff.changetype.ValueChange
import org.javers.core.metamodel.`object`.SnapshotType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration

@Import(OrderAuditServiceTest.RollbackAuditProbeConfig::class)
class OrderAuditServiceTest(
    private val commandService: OrderCommandService,
    private val orderRepository: OrderJpaRepository,
    private val reservationRepository: FulfillmentReservationRepository,
    private val auditService: OrderAuditService,
    private val rollbackAuditProbe: RollbackAuditProbe,
    private val eventPublicationRepository: EventPublicationRepository,
): AbstractDddOrderAuditTest() {

    @AfterEach
    fun cleanup() {
        reservationRepository.deleteAll()
        orderRepository.deleteAll()

        val publicationIds = EventPublication.Status.entries
            .flatMap { status -> eventPublicationRepository.findByStatus(status).map { it.identifier } }
        if (publicationIds.isNotEmpty()) {
            eventPublicationRepository.deletePublications(publicationIds)
        }
    }

    @Test
    fun `placing order records one JaVers snapshot after commit`() {
        val placed = commandService.place(validPlaceOrderCommand())

        await atMost Duration.ofSeconds(5) untilAsserted {
            val history = auditService.getHistory(placed.id)
            history.size shouldBeEqualTo 1
            history.single().type shouldBeEqualTo SnapshotType.INITIAL
        }
    }

    @Test
    fun `approving order records second snapshot and useful status diff`() {
        val placed = commandService.place(validPlaceOrderCommand())
        val approved = commandService.approve(ApproveOrderCommand(placed.id, approvedBy = "ops-user"))

        await atMost Duration.ofSeconds(5) untilAsserted {
            val history = auditService.getHistory(approved.id)
            history.size shouldBeEqualTo 2
            history.last().type shouldBeEqualTo SnapshotType.UPDATE
        }

        val statusChange = auditService.diff(placed, approved)
            .changes
            .filterIsInstance<ValueChange>()
            .first { it.propertyName == "status" }

        statusChange.left shouldBeEqualTo OrderStatus.PLACED
        statusChange.right shouldBeEqualTo OrderStatus.APPROVED
    }

    @Test
    fun `rollback leaves no JaVers snapshot or diff`() {
        val command = validPlaceOrderCommand()

        val rolledBackOrder = assertFailsWith<RollbackOrderException> {
            rollbackAuditProbe.placeThenFail(command)
        }.order

        orderRepository.count() shouldBeEqualTo 0L
        auditService.getHistory(rolledBackOrder.id) shouldBeEqualTo emptyList()
        auditService.diff(rolledBackOrder, rolledBackOrder).hasChanges().shouldBeFalse()
    }

    @Test
    fun `audit trail exposes synthetic ids status and amounts only`() {
        val placed = commandService.place(validPlaceOrderCommand())

        await atMost Duration.ofSeconds(5) untilAsserted {
            val auditTrail = auditService.getAuditTrail(placed.id)
            auditTrail.size shouldBeEqualTo 1
            auditTrail.single().orderId shouldBeEqualTo placed.id.value
            auditTrail.single().status shouldBeEqualTo OrderStatus.PLACED
            auditTrail.single().totalAmount shouldBeEqualTo BigDecimal("25.00")
            auditTrail.single().lineCount shouldBeEqualTo 1
        }
    }

    private fun validPlaceOrderCommand(): PlaceOrderCommand =
        PlaceOrderCommand(
            customerId = CustomerId("customer-1"),
            lines = listOf(
                OrderLine(
                    sku = "sku-1",
                    quantity = 2,
                    unitPrice = Money(BigDecimal("12.50"), "USD"),
                ),
            ),
        )

    @TestConfiguration(proxyBeanMethods = false)
    class RollbackAuditProbeConfig {
        @Bean
        fun rollbackAuditProbe(
            orderRepository: OrderJpaRepository,
            eventPublisher: ApplicationEventPublisher,
            auditService: OrderAuditService,
        ): RollbackAuditProbe =
            RollbackAuditProbe(orderRepository, eventPublisher, auditService)
    }
}

open class RollbackAuditProbe(
    private val orderRepository: OrderJpaRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val auditService: OrderAuditService,
) {
    @Transactional
    open fun placeThenFail(command: PlaceOrderCommand) {
        val order = Order.place(command)
        orderRepository.save(OrderEntity.from(order))
        auditService.commitAfterTransaction(
            author = "rollback-probe",
            order = order.withoutEvents(),
            properties = mapOf("action" to "place"),
        )
        eventPublisher.publishEvent(order.events.single())
        throw RollbackOrderException(order)
    }
}

class RollbackOrderException(val order: Order): RuntimeException("rollback probe failed for orderId=${order.id.value}")

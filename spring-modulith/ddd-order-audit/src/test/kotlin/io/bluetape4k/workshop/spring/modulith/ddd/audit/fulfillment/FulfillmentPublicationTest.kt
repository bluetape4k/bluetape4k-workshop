package io.bluetape4k.workshop.spring.modulith.ddd.audit.fulfillment

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.spring.modulith.ddd.audit.AbstractDddOrderAuditTest
import io.bluetape4k.workshop.spring.modulith.ddd.audit.orders.ApproveOrderCommand
import io.bluetape4k.workshop.spring.modulith.ddd.audit.orders.CustomerId
import io.bluetape4k.workshop.spring.modulith.ddd.audit.orders.Money
import io.bluetape4k.workshop.spring.modulith.ddd.audit.orders.OrderApproved
import io.bluetape4k.workshop.spring.modulith.ddd.audit.orders.OrderCommandService
import io.bluetape4k.workshop.spring.modulith.ddd.audit.orders.OrderJpaRepository
import io.bluetape4k.workshop.spring.modulith.ddd.audit.orders.OrderLine
import io.bluetape4k.workshop.spring.modulith.ddd.audit.orders.PlaceOrderCommand
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.modulith.events.core.EventPublicationRepository
import java.math.BigDecimal
import java.time.Duration

class FulfillmentPublicationTest(
    private val commandService: OrderCommandService,
    private val orderRepository: OrderJpaRepository,
    private val reservationRepository: FulfillmentReservationRepository,
    private val failureSwitch: FulfillmentFailureSwitch,
    private val eventPublisher: ApplicationEventPublisher,
    private val failedPublications: FailedEventPublications,
    private val incompletePublications: IncompleteEventPublications,
    private val eventPublicationRepository: EventPublicationRepository,
): AbstractDddOrderAuditTest() {

    @AfterEach
    fun cleanup() {
        failureSwitch.clear()
        reservationRepository.deleteAll()
        orderRepository.deleteAll()

        val publicationIds = EventPublication.Status.entries
            .flatMap { status -> eventPublicationRepository.findByStatus(status).map { it.identifier } }
        if (publicationIds.isNotEmpty()) {
            eventPublicationRepository.deletePublications(publicationIds)
        }
    }

    @Test
    fun `successful approval creates exactly one fulfillment reservation`() {
        val placed = commandService.place(validPlaceOrderCommand())
        val approved = commandService.approve(ApproveOrderCommand(placed.id, approvedBy = "ops-user"))

        await atMost Duration.ofSeconds(5) untilAsserted {
            reservationRepository.existsById(approved.id.value).shouldBeTrue()
            reservationRepository.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `handler failure leaves failed publication and replay creates reservation`() {
        val placed = commandService.place(validPlaceOrderCommand())
        failureSwitch.failOnce(placed.id.value)

        val approved = commandService.approve(ApproveOrderCommand(placed.id, approvedBy = "ops-user"))

        await atMost Duration.ofSeconds(5) untilAsserted {
            reservationRepository.existsById(approved.id.value) shouldBeEqualTo false
            eventPublicationRepository.countByStatus(EventPublication.Status.FAILED) shouldBeGreaterThan 0
        }

        failureSwitch.clear()
        failedPublications.resubmit(
            ResubmissionOptions.defaults()
                .withMaxInFlight(1)
                .withBatchSize(1),
        )
        incompletePublications.resubmitIncompletePublications { publication ->
            publication.event is OrderApproved
        }

        await atMost Duration.ofSeconds(5) untilAsserted {
            reservationRepository.existsById(approved.id.value).shouldBeTrue()
            reservationRepository.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `duplicate replay does not create duplicate reservations`() {
        val placed = commandService.place(validPlaceOrderCommand())
        val approved = commandService.approve(ApproveOrderCommand(placed.id, approvedBy = "ops-user"))

        await atMost Duration.ofSeconds(5) untilAsserted {
            reservationRepository.existsById(approved.id.value).shouldBeTrue()
        }

        eventPublisher.publishEvent(
            OrderApproved(
                aggregateId = approved.id.value,
                approvedBy = "ops-user",
            ),
        )

        await atMost Duration.ofSeconds(5) untilAsserted {
            reservationRepository.count() shouldBeEqualTo 1L
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
}

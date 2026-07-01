package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.spring.modulith.ddd.audit.AbstractDddOrderAuditTest
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@Import(
    OrderCommandServiceTest.TestPublicationConfig::class,
    OrderCommandServiceTest.RollbackProbeConfig::class,
)
class OrderCommandServiceTest(
    private val commandService: OrderCommandService,
    private val orderRepository: OrderJpaRepository,
    private val eventPublicationRepository: EventPublicationRepository,
    private val publicationProbe: TestPublicationProbe,
    private val rollbackProbe: RollbackProbe,
): AbstractDddOrderAuditTest() {

    @AfterEach
    fun cleanup() {
        val publicationIds = EventPublication.Status.entries
            .flatMap { status -> eventPublicationRepository.findByStatus(status).map { it.identifier } }
        if (publicationIds.isNotEmpty()) {
            eventPublicationRepository.deletePublications(publicationIds)
        }
        orderRepository.deleteAll()
        publicationProbe.clear()
    }

    @Test
    fun `place persists order in PostgreSQL`() {
        val placed = commandService.place(validPlaceOrderCommand())

        val saved = orderRepository.findById(placed.id.value).orElseThrow()
        saved.id shouldBeEqualTo placed.id.value
        saved.customerId shouldBeEqualTo placed.customerId.value
        saved.status shouldBeEqualTo OrderStatus.PLACED
        saved.lines shouldHaveSingleLineMatching placed.lines.single()
    }

    @Test
    fun `approve registers publication row with order transaction`() {
        val placed = commandService.place(validPlaceOrderCommand())
        val approved = commandService.approve(ApproveOrderCommand(placed.id, approvedBy = "ops-user"))

        orderRepository.findById(approved.id.value).orElseThrow().status shouldBeEqualTo OrderStatus.APPROVED

        await atMost Duration.ofSeconds(5) untilAsserted {
            publicationProbe.handledIds.contains(approved.id.value).shouldBeTrue()
            totalPublications() shouldBeGreaterThan 0
        }
    }

    @Test
    fun `rollback leaves no order row and no publication row`() {
        val command = validPlaceOrderCommand()

        assertFailsWith<IllegalStateException> {
            rollbackProbe.placeThenFail(command)
        }

        orderRepository.count() shouldBeEqualTo 0L
        totalPublications() shouldBeEqualTo 0
    }

    @Test
    fun `repeated approve does not create a duplicate effective approval`() {
        val placed = commandService.place(validPlaceOrderCommand())
        commandService.approve(ApproveOrderCommand(placed.id, approvedBy = "ops-user"))

        assertFailsWith<IllegalStateException> {
            commandService.approve(ApproveOrderCommand(placed.id, approvedBy = "ops-user"))
        }

        orderRepository.findById(placed.id.value).orElseThrow().status shouldBeEqualTo OrderStatus.APPROVED
    }

    private fun totalPublications(): Int =
        EventPublication.Status.entries.sumOf { eventPublicationRepository.countByStatus(it) }

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

    private infix fun List<OrderLineEntity>.shouldHaveSingleLineMatching(line: OrderLine) {
        size shouldBeEqualTo 1
        single().sku shouldBeEqualTo line.sku
        single().quantity shouldBeEqualTo line.quantity
        single().unitPriceAmount shouldBeEqualTo line.unitPrice.amount
        single().unitPriceCurrency shouldBeEqualTo line.unitPrice.currency
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestPublicationConfig {
        @Bean
        fun testPublicationProbe(): TestPublicationProbe = TestPublicationProbe()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RollbackProbeConfig {
        @Bean
        fun rollbackProbe(
            orderRepository: OrderJpaRepository,
            eventPublisher: ApplicationEventPublisher,
        ): RollbackProbe =
            RollbackProbe(orderRepository, eventPublisher)
    }
}

open class TestPublicationProbe {
    val handledIds: MutableList<String>
        get() = TestPublicationRecords.handledIds

    @ApplicationModuleListener
    open fun on(event: OrderPlaced) {
        TestPublicationRecords.handledIds += event.aggregateId
    }

    @ApplicationModuleListener
    open fun on(event: OrderApproved) {
        TestPublicationRecords.handledIds += event.aggregateId
    }

    fun clear() {
        TestPublicationRecords.handledIds.clear()
    }
}

private object TestPublicationRecords {
    val handledIds: MutableList<String> = CopyOnWriteArrayList()
}

open class RollbackProbe(
    private val orderRepository: OrderJpaRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Transactional
    open fun placeThenFail(command: PlaceOrderCommand) {
        val order = Order.place(command)
        orderRepository.save(OrderEntity.from(order))
        eventPublisher.publishEvent(order.events.single())
        throw IllegalStateException("rollback probe failed")
    }
}

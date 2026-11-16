package io.bluetape4k.workshop.commerce.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.order.application.InventoryListenerFailureSwitch
import io.bluetape4k.workshop.commerce.order.application.OrderCommandService
import io.bluetape4k.workshop.commerce.order.application.PaymentEventService
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentProviderEvent
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventKind
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import io.bluetape4k.workshop.commerce.order.domain.ReservationStatus
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrder
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrderLine
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRepository
import io.bluetape4k.workshop.commerce.order.persistence.InventoryReservationRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderRepository
import io.bluetape4k.workshop.commerce.order.persistence.PaymentAttemptRepository
import io.bluetape4k.workshop.commerce.order.persistence.ProviderEventInboxRepository
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.utility.Base58
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class OrderLifecycleContextRestartIntegrationTest {

    companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

    @Test
    fun `state failed publication and provider inbox survive context restart and replay`() {
        val schema = "restart_${Base58.randomString(12).lowercase()}"
        createSchema(schema)

        try {
            val first = startContext(schema)
            val evidence = try {
                createFailedPublication(first)
            } finally {
                first.close()
            }

            val restarted = startContext(schema)
            try {
                verifyAutomaticReplay(restarted, evidence)
            } finally {
                restarted.close()
            }
        } finally {
            dropSchema(schema)
        }
    }

    private fun createFailedPublication(context: ConfigurableApplicationContext): RestartEvidence {
        val commands = context.getBean<OrderCommandService>()
        val paymentEvents = context.getBean<PaymentEventService>()
        val payments = context.getBean<PaymentAttemptRepository>()
        val reservations = context.getBean<InventoryReservationRepository>()
        val inbox = context.getBean<ProviderEventInboxRepository>()
        val failureSwitch = context.getBean<InventoryListenerFailureSwitch>()
        val publications = context.getBean<EventPublicationRepository>()
        val submitted = commands.submit(validOrder())

        await atMost 10.seconds withPollInterval 200.milliseconds untilAsserted {
            read(context) {
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.AUTHORIZING
            }
        }

        val providerEventId = "restart-success-${submitted.paymentAttemptId}"
        failureSwitch.failOnce(submitted.orderId)

        val event = PaymentProviderEvent(
            providerEventId = providerEventId,
            paymentAttemptId = submitted.paymentAttemptId,
            kind = ProviderEventKind.SUCCEEDED,
            occurredAt = Instant.parse("2026-07-19T00:00:00Z")
        )
        paymentEvents.ingest(event) shouldBeEqualTo ProviderEventDisposition.APPLIED

        await atMost 10.seconds withPollInterval 200.milliseconds untilAsserted {
            read(context) {
                publications.countByStatus(EventPublication.Status.FAILED) shouldBeGreaterThan 0
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.SUCCEEDED
                reservations.findByOrderId(submitted.orderId)!!.status shouldBeEqualTo ReservationStatus.HELD
                inbox.find("FAKE", providerEventId)!!.disposition shouldBeEqualTo ProviderEventDisposition.APPLIED
            }
        }
        return RestartEvidence(submitted.orderId, submitted.paymentAttemptId, providerEventId)
    }

    private fun verifyAutomaticReplay(
        context: ConfigurableApplicationContext,
        evidence: RestartEvidence,
    ) {
        val orders = context.getBean<OrderRepository>()
        val payments = context.getBean<PaymentAttemptRepository>()
        val reservations = context.getBean<InventoryReservationRepository>()
        val fulfillments = context.getBean<FulfillmentGroupRepository>()
        val inbox = context.getBean<ProviderEventInboxRepository>()
        val publications = context.getBean<EventPublicationRepository>()

        read(context) {
            payments.findById(evidence.paymentAttemptId).status shouldBeEqualTo PaymentStatus.SUCCEEDED
            inbox.find("FAKE", evidence.providerEventId)!!.disposition shouldBeEqualTo ProviderEventDisposition.APPLIED
        }

        await atMost 10.seconds withPollInterval 200.milliseconds untilAsserted {
            read(context) {
                orders.findById(evidence.orderId).status shouldBeEqualTo OrderStatus.FULFILLMENT_IN_PROGRESS
                reservations.findByOrderId(evidence.orderId)!!.status shouldBeEqualTo ReservationStatus.COMMITTED
                fulfillments.findByOrderId(evidence.orderId).size shouldBeEqualTo 2

                inbox.find("FAKE", evidence.providerEventId)!!
                    .disposition shouldBeEqualTo ProviderEventDisposition.APPLIED

                publications.countByStatus(EventPublication.Status.COMPLETED) shouldBeGreaterThan 0
            }
        }
    }

    private fun startContext(schema: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(OrderLifecycleApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=${schemaJdbcUrl(schema)}",
                "--spring.datasource.username=${requireNotNull(postgres.username)}",
                "--spring.datasource.password=${requireNotNull(postgres.password)}",
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--spring.datasource.hikari.minimum-idle=0",
                "--spring.main.banner-mode=off"
            )

    private inline fun <T: Any> read(
        context: ConfigurableApplicationContext,
        crossinline block: () -> T,
    ): T {
        val transactions = TransactionTemplate(context.getBean<PlatformTransactionManager>())
        return transactions.execute { block() }.shouldNotBeNull()
    }

    private fun validOrder() =
        SubmitOrder(
            tenantId = "tenant-restart",
            customerReference = "restart-order",
            providerMode = ProviderMode.DELAYED_SUCCESS,
            lines = listOf(
                SubmitOrderLine("sku-a", 1, BigDecimal("10.00")),
                SubmitOrderLine("sku-b", 2, BigDecimal("20.00"))
            )
        )

    private fun createSchema(schema: String) =
        postgresConnection().use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }

    private fun dropSchema(schema: String) =
        postgresConnection().use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }

    private fun postgresConnection(): Connection =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username.shouldNotBeNull(),
            postgres.password.shouldNotBeNull(),
        )

    private fun schemaJdbcUrl(schema: String): String {
        val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
        return "${postgres.jdbcUrl}${separator}currentSchema=$schema"
    }

    private data class RestartEvidence(
        val orderId: UUID,
        val paymentAttemptId: UUID,
        val providerEventId: String,
    )
}

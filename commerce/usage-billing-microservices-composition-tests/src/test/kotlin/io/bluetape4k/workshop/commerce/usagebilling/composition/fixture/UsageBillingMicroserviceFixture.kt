package io.bluetape4k.workshop.commerce.usagebilling.composition.fixture

import io.bluetape4k.workshop.commerce.usagebilling.billing.BillingServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingOutboxPublisher
import io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.BillingChargeRepository
import io.bluetape4k.workshop.commerce.usagebilling.invoice.InvoiceServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging.InvoiceOutboxPublisher
import io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence.InvoiceLineRepository
import io.bluetape4k.workshop.commerce.usagebilling.meter.MeterServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.meter.application.MeterCommandService
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.ActivatePriceCommand
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxPublisher
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxStatus
import io.bluetape4k.workshop.commerce.usagebilling.meter.persistence.MeterOutboxRepository
import io.bluetape4k.workshop.commerce.usagebilling.query.QueryServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryProjectionJournal
import io.bluetape4k.workshop.commerce.usagebilling.usage.UsageServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.usage.application.UsageCommandService
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.AcceptUsageCommand
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceJournal
import io.bluetape4k.workshop.commerce.usagebilling.usage.messaging.UsageOutboxPublisher
import io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsageOutboxRepository
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Instant
import java.util.Properties
import java.util.UUID

class UsageBillingMicroserviceFixture : AutoCloseable {
    private val failures = KafkaFailureController()
    private val kafka = KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"))
    private val meterDatabase = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
    private val usageDatabase = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
    private val billingDatabase = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
    private val invoiceDatabase = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
    private val queryDatabase = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
    private lateinit var meterContext: ConfigurableApplicationContext
    private lateinit var usageContext: ConfigurableApplicationContext
    private lateinit var billingContext: ConfigurableApplicationContext
    private lateinit var invoiceContext: ConfigurableApplicationContext
    private lateinit var queryContext: ConfigurableApplicationContext

    fun start(): UsageBillingMicroserviceFixture {
        kafka.start()
        meterDatabase.start()
        usageDatabase.start()
        billingDatabase.start()
        invoiceDatabase.start()
        queryDatabase.start()
        createTopics()
        meterContext = startContext(MeterServiceApplication::class.java, "meter", meterDatabase)
        usageContext = startContext(UsageServiceApplication::class.java, "usage", usageDatabase)
        billingContext = startContext(BillingServiceApplication::class.java, "billing", billingDatabase)
        invoiceContext = startContext(InvoiceServiceApplication::class.java, "invoice", invoiceDatabase)
        queryContext = startContext(QueryServiceApplication::class.java, "query", queryDatabase)
        return this
    }

    fun blockTopic(topic: String) = failures.block(topic)

    fun unblockTopic(topic: String) = failures.unblock(topic)

    fun activatePrice(tenantId: String, meterCode: String, amount: BigDecimal) =
        meterContext.getBean(MeterCommandService::class.java).activatePrice(
            ActivatePriceCommand(
                idempotencyKey = UUID.randomUUID().toString(),
                tenantId = tenantId,
                meterCode = meterCode,
                currency = "USD",
                unitPrice = amount,
                effectiveAt = Instant.parse("2026-07-23T00:00:00Z"),
            ),
        )

    fun publishMeterEvents() {
        if (!failures.isBlocked(METER_TOPIC)) {
            meterContext.getBean(MeterOutboxPublisher::class.java).publishPending()
        }
    }

    fun acceptUsage(tenantId: String, meterCode: String, sourceEventId: String = UUID.randomUUID().toString()) =
        usageContext.getBean(UsageCommandService::class.java).accept(
            AcceptUsageCommand(
                tenantId = tenantId,
                sourceSystem = "composition-test",
                sourceEventId = sourceEventId,
                meterCode = meterCode,
                currency = "USD",
                quantity = BigDecimal("2"),
                occurredAt = Instant.parse("2026-07-23T00:01:00Z"),
            ),
        )

    fun publishUsageEvents() {
        usageContext.getBean(UsageOutboxPublisher::class.java).publishPending()
    }

    fun publishBillingEvents() {
        billingContext.getBean(BillingOutboxPublisher::class.java).publishPending()
    }

    fun publishInvoiceEvents() {
        invoiceContext.getBean(InvoiceOutboxPublisher::class.java).publishPending()
    }

    fun redeliverLatestUsageEvent() {
        val event = transaction(usageContext) {
            usageContext.getBean(UsageOutboxRepository::class.java).findAll().last()
        }
        usageKafkaTemplate().send(USAGE_TOPIC, event.partitionKey, event.payload).get()
    }

    fun outboxBacklog(service: String): Long {
        require(service == "meter") { "unsupported_outbox_service:$service" }
        return meterTransaction {
            meterContext.getBean(MeterOutboxRepository::class.java).findAll()
                .count { it.status != MeterOutboxStatus.PUBLISHED.name }
                .toLong()
        }
    }

    fun priceEvidence(tenantId: String, meterCode: String): PriceEvidence? =
        usageTransactionNullable {
            usageContext.getBean(UsageAcceptanceJournal::class.java)
                .priceEvidence(tenantId, meterCode, "USD")
        }

    fun billingHasPriceEvidence(tenantId: String, meterCode: String): Boolean =
        transaction(billingContext) {
            billingContext.getBean(BillingInboxJournal::class.java)
                .priceEvidence(tenantId, meterCode, "USD") != null
        }

    fun chargeCount(): Long =
        transaction(billingContext) {
            billingContext.getBean(BillingChargeRepository::class.java).findAll().count().toLong()
        }

    fun invoiceLineCount(): Long =
        transaction(invoiceContext) {
            invoiceContext.getBean(InvoiceLineRepository::class.java).findAll().count().toLong()
        }

    fun queryAppliedEventCount(): Int =
        transaction(queryContext) {
            queryContext.getBean(QueryProjectionJournal::class.java).readModelEventIds.size
        }

    override fun close() {
        if (::queryContext.isInitialized) queryContext.close()
        if (::invoiceContext.isInitialized) invoiceContext.close()
        if (::billingContext.isInitialized) billingContext.close()
        if (::usageContext.isInitialized) usageContext.close()
        if (::meterContext.isInitialized) meterContext.close()
        queryDatabase.stop()
        invoiceDatabase.stop()
        billingDatabase.stop()
        usageDatabase.stop()
        meterDatabase.stop()
        kafka.stop()
    }

    private fun createTopics() {
        val properties = Properties().also { it["bootstrap.servers"] = kafka.bootstrapServers }
        Admin.create(properties).use { admin ->
            admin.createTopics(TOPICS.map { NewTopic(it, 1, 1.toShort()) }).all().get()
        }
    }

    private fun startContext(
        application: Class<*>,
        service: String,
        database: PostgreSQLContainer,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(application)
            .properties(
                "server.port=0",
                "spring.datasource.url=${database.jdbcUrl}",
                "spring.datasource.username=${database.username}",
                "spring.datasource.password=${database.password}",
                "usage-billing.$service.kafka.bootstrap-servers=${kafka.bootstrapServers}",
                "usage-billing.$service.kafka.listener-auto-startup=true",
                "management.datadog.metrics.export.enabled=false",
            )
            .run()

    private fun <T : Any> meterTransaction(block: () -> T): T =
        transaction(meterContext, block)

    @Suppress("UNCHECKED_CAST")
    private fun usageKafkaTemplate(): KafkaTemplate<String, String> =
        usageContext.getBean("usageKafkaTemplate") as KafkaTemplate<String, String>

    private fun <T> usageTransactionNullable(block: () -> T?): T? {
        var result: T? = null
        TransactionTemplate(usageContext.getBean(PlatformTransactionManager::class.java)).executeWithoutResult {
            result = block()
        }
        return result
    }

    private fun <T : Any> transaction(context: ConfigurableApplicationContext, block: () -> T): T =
        requireNotNull(TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).execute { block() })

    private companion object {
        const val METER_TOPIC = "meter.events.v1"
        const val USAGE_TOPIC = "usage.events.v1"
        val TOPICS = listOf(
            METER_TOPIC,
            USAGE_TOPIC,
            "billing.events.v1",
            "invoice.events.v1",
        )
    }
}

package io.bluetape4k.workshop.commerce.usagebilling.composition.fixture

import io.bluetape4k.workshop.commerce.usagebilling.meter.MeterServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.meter.application.MeterCommandService
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.ActivatePriceCommand
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxPublisher
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxStatus
import io.bluetape4k.workshop.commerce.usagebilling.meter.persistence.MeterOutboxRepository
import io.bluetape4k.workshop.commerce.usagebilling.usage.UsageServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceJournal
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
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
    private lateinit var meterContext: ConfigurableApplicationContext
    private lateinit var usageContext: ConfigurableApplicationContext

    fun start(): UsageBillingMicroserviceFixture {
        kafka.start()
        meterDatabase.start()
        usageDatabase.start()
        createTopics()
        meterContext = startContext(MeterServiceApplication::class.java, "meter", meterDatabase)
        usageContext = startContext(UsageServiceApplication::class.java, "usage", usageDatabase)
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

    override fun close() {
        if (::usageContext.isInitialized) usageContext.close()
        if (::meterContext.isInitialized) meterContext.close()
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
            .web(WebApplicationType.NONE)
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
        val TOPICS = listOf(
            METER_TOPIC,
            "usage.events.v1",
            "billing.events.v1",
            "invoice.events.v1",
        )
    }
}

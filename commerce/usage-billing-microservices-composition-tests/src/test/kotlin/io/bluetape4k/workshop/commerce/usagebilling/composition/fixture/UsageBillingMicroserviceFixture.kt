@file:Suppress("LongParameterList") // Test wire envelopes deliberately mirror the fixed inter-service contract.

package io.bluetape4k.workshop.commerce.usagebilling.composition.fixture

import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireEquals
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.infra.ToxiproxyServer
import io.bluetape4k.workshop.commerce.usagebilling.billing.BillingServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.billing.application.BillingAdjustmentService
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentCommand
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentOutcome
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingOutboxPublisher
import io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.BillingChargeRepository
import io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.BillingOutboxRepository
import io.bluetape4k.workshop.commerce.usagebilling.invoice.InvoiceServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceJournal
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceLine
import io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging.InvoiceOutboxPublisher
import io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence.InvoiceLineRepository
import io.bluetape4k.workshop.commerce.usagebilling.meter.MeterServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.meter.application.MeterCommandService
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.ActivatePriceCommand
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxPublisher
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxStatus
import io.bluetape4k.workshop.commerce.usagebilling.meter.persistence.MeterOutboxRepository
import io.bluetape4k.workshop.commerce.usagebilling.query.QueryServiceApplication
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryRecoveryService
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryProjectionJournal
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryRecoverySnapshot
import io.bluetape4k.workshop.commerce.usagebilling.query.web.QueryTenantAuthorizer
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.Network
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.HexFormat
import java.util.UUID

data class MeterOutboxState(
    val status: MeterOutboxStatus,
    val attempt: Int,
)

class UsageBillingMicroserviceFixture(
    private val brokerPathProxy: Boolean = false,
) : AutoCloseable {
    private val brokerPathNetwork: Network by lazy { Network.newNetwork() }
    private val toxiproxy: ToxiproxyServer by lazy {
        ToxiproxyServer(image = "ghcr.io/shopify/toxiproxy", tag = "2.5.0").apply {
            withNetwork(brokerPathNetwork)
        }
    }
    private val kafka = KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0")).apply {
        if (brokerPathProxy) {
            withNetwork(brokerPathNetwork)
            withListener("$KAFKA_NETWORK_ALIAS:$KAFKA_PROXY_TARGET_PORT") { brokerPathBootstrapServers() }
        }
    }
    private val meterDatabase = postgresServer()
    private val usageDatabase = postgresServer()
    private val billingDatabase = postgresServer()
    private val invoiceDatabase = postgresServer()
    private val queryDatabase = postgresServer()
    private lateinit var meterContext: ConfigurableApplicationContext
    private lateinit var usageContext: ConfigurableApplicationContext
    private lateinit var billingContext: ConfigurableApplicationContext
    private lateinit var invoiceContext: ConfigurableApplicationContext
    private lateinit var queryContext: ConfigurableApplicationContext
    private lateinit var brokerPath: Proxy
    private var isBrokerPathCut: Boolean = false

    fun start(): UsageBillingMicroserviceFixture {
        startBrokerPathProxy()
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

    fun blockTopic(topic: String) {
        topic.requireEquals(METER_TOPIC, "topic")
        meterContext.getBean(MeterTransportFailureSwitch::class.java).isFailing = true
    }

    fun unblockTopic(topic: String) {
        topic.requireEquals(METER_TOPIC, "topic")
        meterContext.getBean(MeterTransportFailureSwitch::class.java).isFailing = false
    }

    fun cutBrokerPath() {
        brokerPathProxy.requireEquals(true, "brokerPathProxy")
        check(!isBrokerPathCut) { "broker_path_already_cut" }
        brokerPath.toxics().bandwidth(CUT_UPSTREAM, ToxicDirection.UPSTREAM, 0)
        brokerPath.toxics().bandwidth(CUT_DOWNSTREAM, ToxicDirection.DOWNSTREAM, 0)
        isBrokerPathCut = true
    }

    fun restoreBrokerPath() {
        brokerPathProxy.requireEquals(true, "brokerPathProxy")
        if (!isBrokerPathCut) return
        brokerPath.toxics().get(CUT_UPSTREAM).remove()
        brokerPath.toxics().get(CUT_DOWNSTREAM).remove()
        isBrokerPathCut = false
    }

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

    fun publishMeterEvents() =
        meterContext.getBean(MeterOutboxPublisher::class.java).publishPending()

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

    fun sendUsageEvent(
        tenantId: String,
        meterCode: String,
        aggregateId: String,
        aggregateVersion: Long,
        eventId: UUID = UUID.randomUUID(),
    ) {
        val payload = """{"meterCode":"$meterCode","currency":"USD","quantity":"1"}"""
        val envelope = envelope(
            WireEvent(
                eventId = eventId,
                eventType = "UsageAccepted",
                tenantId = tenantId,
                aggregateType = "Usage",
                aggregateId = aggregateId,
                aggregateVersion = aggregateVersion,
                payload = payload,
            ),
        )
        usageKafkaTemplate().send(USAGE_TOPIC, "$tenantId|Usage|$aggregateId", envelope).get()
    }

    fun sendQueryEvent(
        eventId: UUID,
        tenantId: String,
        eventType: String,
        schemaVersion: Int = 1,
        validDigest: Boolean = true,
    ) {
        val payload = "{}"
        val wirePayload = envelope(
            WireEvent(
                eventId = eventId,
                eventType = eventType,
                tenantId = tenantId,
                aggregateType = "Invoice",
                aggregateId = eventId.toString(),
                aggregateVersion = 1,
                payload = payload,
                schemaVersion = schemaVersion,
                payloadDigest = if (validDigest) digestOf(payload) else "0".repeat(64),
            ),
        )
        usageKafkaTemplate().send(INVOICE_TOPIC, "$tenantId|Invoice|$eventId", wirePayload).get()
    }

    fun postBillingAdjustment(
        tenantId: String,
        correctionOf: UUID,
        amount: BigDecimal,
        eventId: UUID = UUID.randomUUID(),
    ): BillingAdjustmentOutcome =
        billingContext.getBean(BillingAdjustmentService::class.java).post(
            BillingAdjustmentCommand(
                adjustmentEventId = eventId,
                tenantId = tenantId,
                correctionOf = correctionOf,
                amount = amount,
                currency = "USD",
            ),
        )

    fun restartUsageContext() {
        usageContext.close()
        usageContext = startContext(UsageServiceApplication::class.java, "usage", usageDatabase)
    }

    fun redeliverLatestUsageEvent() {
        val event = transaction(usageContext) {
            usageContext.getBean(UsageOutboxRepository::class.java).findAll().last()
        }
        usageKafkaTemplate().send(USAGE_TOPIC, event.partitionKey, event.payload).get()
    }

    fun outboxBacklog(service: String): Long {
        service.requireEquals("meter", "service")
        return meterTransaction {
            meterContext.getBean(MeterOutboxRepository::class.java).findAll()
                .count { it.status != MeterOutboxStatus.PUBLISHED.name }
                .toLong()
        }
    }

    fun meterOutboxState(eventId: UUID): MeterOutboxState =
        meterTransaction {
            meterContext.getBean(MeterOutboxRepository::class.java).findAll()
                .single { it.eventId == eventId }
                .let { MeterOutboxState(MeterOutboxStatus.valueOf(it.status), it.attempt) }
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

    fun chargeAmounts(): List<BigDecimal> =
        transaction(billingContext) {
            billingContext.getBean(BillingChargeRepository::class.java).findAll().map { it.amount }
        }

    fun latestBillingEventId(): UUID =
        transaction(billingContext) {
            billingContext.getBean(BillingOutboxRepository::class.java).findAll().last().eventId
        }

    fun invoiceLineCount(): Long =
        transaction(invoiceContext) {
            invoiceContext.getBean(InvoiceLineRepository::class.java).findAll().count().toLong()
        }

    fun invoiceLines(): List<InvoiceLine> =
        transaction(invoiceContext) {
            invoiceContext.getBean(InvoiceJournal::class.java).lines
        }

    fun queryAppliedEventCount(): Int =
        transaction(queryContext) {
            queryContext.getBean(QueryProjectionJournal::class.java).readModelEventIds.size
        }

    fun queryRecoverySnapshot(): QueryRecoverySnapshot =
        transaction(queryContext) {
            queryContext.getBean(QueryRecoveryService::class.java).snapshot()
        }

    fun redriveQueryEvent(eventId: UUID): Boolean =
        queryContext.getBean(QueryRecoveryService::class.java)
            .redrive(eventId, "composition-operator", "correlation-$eventId")
            .requested

    fun queryTenantAccessAllowed(authenticationTenant: String, targetTenant: String): Boolean {
        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            "composition-user",
            "n/a",
            listOf(SimpleGrantedAuthority("TENANT_$authenticationTenant")),
        )
        return try {
            queryContext.getBean(QueryTenantAuthorizer::class.java).requireAccess(authentication, targetTenant)
            true
        } catch (_: AccessDeniedException) {
            false
        }
    }

    override fun close() {
        if (brokerPathProxy && isBrokerPathCut) restoreBrokerPath()
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
        if (brokerPathProxy) {
            toxiproxy.stop()
            brokerPathNetwork.close()
        }
    }

    private fun createTopics() {
        val properties = Properties().also { it["bootstrap.servers"] = brokerBootstrapServers() }
        Admin.create(properties).use { admin ->
            admin.createTopics(TOPICS.map { NewTopic(it, 1, 1.toShort()) }).all().get()
        }
    }

    private fun startContext(
        application: Class<*>,
        service: String,
        database: PostgreSQLServer,
    ): ConfigurableApplicationContext {
        val sources = if (service == "meter") {
            arrayOf(application, MeterCompositionTestConfiguration::class.java)
        } else {
            arrayOf(application)
        }
        return SpringApplicationBuilder(*sources)
            .properties(
                "server.port=0",
                "spring.datasource.url=${database.jdbcUrl}",
                "spring.datasource.username=${database.username}",
                "spring.datasource.password=${database.password}",
                "usage-billing.$service.kafka.bootstrap-servers=${brokerBootstrapServers()}",
                "usage-billing.$service.kafka.listener-auto-startup=true",
                "management.datadog.metrics.export.enabled=false",
                "spring.kafka.producer.properties.delivery.timeout.ms=5000",
                "spring.kafka.producer.properties.request.timeout.ms=1000",
                "spring.kafka.producer.properties.max.block.ms=3000",
            )
            .run()
    }

    private fun startBrokerPathProxy() {
        if (!brokerPathProxy) return
        toxiproxy.start()
        brokerPath = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort).createProxy(
            "kafka-broker-path",
            "0.0.0.0:$TOXIPROXY_LISTEN_PORT",
            "$KAFKA_NETWORK_ALIAS:$KAFKA_PROXY_TARGET_PORT",
        )
    }

    private fun brokerBootstrapServers(): String =
        if (brokerPathProxy) brokerPathBootstrapServers() else kafka.bootstrapServers

    private fun brokerPathBootstrapServers(): String {
        return "${toxiproxy.host}:${toxiproxy.getMappedPort(TOXIPROXY_LISTEN_PORT)}"
    }

    private fun postgresServer(): PostgreSQLServer =
        PostgreSQLServer(image = "postgres", tag = "16-alpine")

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

    private fun envelope(event: WireEvent): String =
        Jackson.defaultJsonMapper.writeValueAsString(
            linkedMapOf(
                "eventId" to event.eventId.toString(),
                "eventType" to event.eventType,
                "schemaVersion" to event.schemaVersion,
                "tenantId" to event.tenantId,
                "aggregateType" to event.aggregateType,
                "aggregateId" to event.aggregateId,
                "aggregateVersion" to event.aggregateVersion,
                "payload" to event.payload,
                "payloadDigest" to event.payloadDigest,
                "occurredAt" to "2026-07-23T00:00:00Z",
                "recordedAt" to "2026-07-23T00:00:01Z",
            ),
        )

    private data class WireEvent(
        val eventId: UUID,
        val eventType: String,
        val tenantId: String,
        val aggregateType: String,
        val aggregateId: String,
        val aggregateVersion: Long,
        val payload: String,
        val schemaVersion: Int = 1,
        val payloadDigest: String = digestOf(payload),
    )

    private companion object {
        const val KAFKA_NETWORK_ALIAS = "kafka"
        const val KAFKA_PROXY_TARGET_PORT = 19092
        const val TOXIPROXY_LISTEN_PORT = 8666
        const val CUT_UPSTREAM = "cut-kafka-upstream"
        const val CUT_DOWNSTREAM = "cut-kafka-downstream"
        const val METER_TOPIC = "meter.events.v1"
        const val USAGE_TOPIC = "usage.events.v1"
        const val INVOICE_TOPIC = "invoice.events.v1"
        val TOPICS = listOf(
            METER_TOPIC,
            USAGE_TOPIC,
            BILLING_TOPIC,
            INVOICE_TOPIC,
        )

        const val BILLING_TOPIC = "billing.events.v1"

        fun digestOf(value: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))
    }
}

@file:Suppress("LongParameterList") // Test wire envelopes deliberately mirror the fixed inter-service contract.

package io.bluetape4k.workshop.commerce.usagebilling.composition.fixture

import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.toUtf8Bytes
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
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterActivationResult
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
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceResult
import io.bluetape4k.workshop.commerce.usagebilling.usage.messaging.UsageOutboxPublisher
import io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsageOutboxRepository
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.getBean
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.Network
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.*

/**
 * MeterOutboxState 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class MeterOutboxState(
/**
 * status은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val status: MeterOutboxStatus,
/**
 * attempt은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val attempt: Int,
)

/**
 * UsageBillingMicroserviceFixture 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class UsageBillingMicroserviceFixture(
/**
 * brokerPathProxy은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val brokerPathProxy: Boolean = false,
): AutoCloseable {

    private companion object: KLogging() {
/**
 * KAFKA_NETWORK_ALIAS은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val KAFKA_NETWORK_ALIAS = "kafka"
/**
 * KAFKA_PROXY_TARGET_PORT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val KAFKA_PROXY_TARGET_PORT = 19092
/**
 * TOXIPROXY_LISTEN_PORT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TOXIPROXY_LISTEN_PORT = 8666
/**
 * CUT_UPSTREAM은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val CUT_UPSTREAM = "cut-kafka-upstream"
/**
 * CUT_DOWNSTREAM은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val CUT_DOWNSTREAM = "cut-kafka-downstream"
/**
 * METER_TOPIC은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val METER_TOPIC = "meter.events.v1"
/**
 * USAGE_TOPIC은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val USAGE_TOPIC = "usage.events.v1"
/**
 * INVOICE_TOPIC은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val INVOICE_TOPIC = "invoice.events.v1"
/**
 * TOPICS은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val TOPICS = listOf(
            METER_TOPIC,
            USAGE_TOPIC,
            BILLING_TOPIC,
            INVOICE_TOPIC,
        )

/**
 * BILLING_TOPIC은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val BILLING_TOPIC = "billing.events.v1"

/**
 * digestOf은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun digestOf(value: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()))
    }

/**
 * brokerPathNetwork은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val brokerPathNetwork: Network by lazy { Network.newNetwork() }
/**
 * toxiproxy은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val toxiproxy: ToxiproxyServer by lazy {
        ToxiproxyServer(image = "ghcr.io/shopify/toxiproxy", tag = "2.5.0").apply {
            withNetwork(brokerPathNetwork)
        }
    }
/**
 * kafka은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val kafka by lazy {
        KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0")).apply {
            if (brokerPathProxy) {
                withNetwork(brokerPathNetwork)
                withListener("$KAFKA_NETWORK_ALIAS:$KAFKA_PROXY_TARGET_PORT") { brokerPathBootstrapServers() }
            }
        }
    }

/**
 * meterDatabase은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val meterDatabase = postgresServer()
/**
 * usageDatabase은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val usageDatabase = postgresServer()
/**
 * billingDatabase은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val billingDatabase = postgresServer()
/**
 * invoiceDatabase은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val invoiceDatabase = postgresServer()
/**
 * queryDatabase은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val queryDatabase = postgresServer()
/**
 * meterContext은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var meterContext: ConfigurableApplicationContext
/**
 * usageContext은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var usageContext: ConfigurableApplicationContext
/**
 * billingContext은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var billingContext: ConfigurableApplicationContext
/**
 * invoiceContext은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var invoiceContext: ConfigurableApplicationContext
/**
 * queryContext은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var queryContext: ConfigurableApplicationContext
/**
 * brokerPath은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var brokerPath: Proxy
/**
 * isBrokerPathCut은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var isBrokerPathCut: Boolean = false

/**
 * start은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * blockTopic은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun blockTopic(topic: String) {
        topic.requireEquals(METER_TOPIC, "topic")
        meterContext.getBean(MeterTransportFailureSwitch::class.java).isFailing = true
    }

/**
 * unblockTopic은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun unblockTopic(topic: String) {
        topic.requireEquals(METER_TOPIC, "topic")
        meterContext.getBean(MeterTransportFailureSwitch::class.java).isFailing = false
    }

/**
 * cutBrokerPath은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun cutBrokerPath() {
        brokerPathProxy.requireEquals(true, "brokerPathProxy")
        check(!isBrokerPathCut) { "broker_path_already_cut" }

        brokerPath.toxics().bandwidth(CUT_UPSTREAM, ToxicDirection.UPSTREAM, 0)
        brokerPath.toxics().bandwidth(CUT_DOWNSTREAM, ToxicDirection.DOWNSTREAM, 0)
        isBrokerPathCut = true
    }

/**
 * restoreBrokerPath은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun restoreBrokerPath() {
        brokerPathProxy.requireEquals(true, "brokerPathProxy")
        if (!isBrokerPathCut) return

        brokerPath.toxics().get(CUT_UPSTREAM).remove()
        brokerPath.toxics().get(CUT_DOWNSTREAM).remove()
        isBrokerPathCut = false
    }

/**
 * activatePrice은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun activatePrice(tenantId: String, meterCode: String, amount: BigDecimal): MeterActivationResult =
        meterContext.getBean<MeterCommandService>()
            .activatePrice(
                ActivatePriceCommand(
                    idempotencyKey = UUID.randomUUID().toString(),
                    tenantId = tenantId,
                    meterCode = meterCode,
                    currency = "USD",
                    unitPrice = amount,
                    effectiveAt = Instant.parse("2026-07-23T00:00:00Z"),
                ),
            )

/**
 * publishMeterEvents은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun publishMeterEvents() =
        meterContext.getBean<MeterOutboxPublisher>().publishPending()

/**
 * acceptUsage은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun acceptUsage(
        tenantId: String,
        meterCode: String,
        sourceEventId: String = UUID.randomUUID().toString(),
    ): UsageAcceptanceResult =
        usageContext.getBean<UsageCommandService>()
            .accept(
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

/**
 * publishUsageEvents은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun publishUsageEvents() {
        usageContext.getBean<UsageOutboxPublisher>().publishPending()
    }

/**
 * publishBillingEvents은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun publishBillingEvents() {
        billingContext.getBean<BillingOutboxPublisher>().publishPending()
    }

/**
 * publishInvoiceEvents은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun publishInvoiceEvents() {
        invoiceContext.getBean<InvoiceOutboxPublisher>().publishPending()
    }

/**
 * sendUsageEvent은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun sendUsageEvent(
        tenantId: String,
        meterCode: String,
        aggregateId: String,
        aggregateVersion: Long,
        eventId: UUID = UUID.randomUUID(),
    ) {
/**
 * payload은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payload = """{"meterCode":"$meterCode","currency":"USD","quantity":"1"}"""
/**
 * envelope은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * sendQueryEvent은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun sendQueryEvent(
        eventId: UUID,
        tenantId: String,
        eventType: String,
        schemaVersion: Int = 1,
        validDigest: Boolean = true,
    ) {
/**
 * payload은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payload = "{}"
/**
 * wirePayload은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * postBillingAdjustment은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun postBillingAdjustment(
        tenantId: String,
        correctionOf: UUID,
        amount: BigDecimal,
        eventId: UUID = UUID.randomUUID(),
    ): BillingAdjustmentOutcome =
        billingContext.getBean<BillingAdjustmentService>()
            .post(
                BillingAdjustmentCommand(
                    adjustmentEventId = eventId,
                    tenantId = tenantId,
                    correctionOf = correctionOf,
                    amount = amount,
                    currency = "USD",
                ),
            )

/**
 * restartUsageContext은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun restartUsageContext() {
        usageContext.close()
        usageContext = startContext(UsageServiceApplication::class.java, "usage", usageDatabase)
    }

/**
 * redeliverLatestUsageEvent은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun redeliverLatestUsageEvent() {
        val event = transaction(usageContext) {
            usageContext.getBean<UsageOutboxRepository>().findAll().last()
        }
        usageKafkaTemplate().send(USAGE_TOPIC, event.partitionKey, event.payload).get()
    }

/**
 * outboxBacklog은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun outboxBacklog(service: String): Long {
        service.requireEquals("meter", "service")

        return meterTransaction {
            meterContext.getBean<MeterOutboxRepository>()
                .findAll()
                .count { it.status != MeterOutboxStatus.PUBLISHED.name }
                .toLong()
        }
    }

/**
 * meterOutboxState은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun meterOutboxState(eventId: UUID): MeterOutboxState =
        meterTransaction {
            meterContext.getBean<MeterOutboxRepository>()
                .findAll()
                .single { it.eventId == eventId }
                .let { MeterOutboxState(MeterOutboxStatus.valueOf(it.status), it.attempt) }
        }

/**
 * priceEvidence은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun priceEvidence(tenantId: String, meterCode: String): PriceEvidence? =
        usageTransactionNullable {
            usageContext.getBean<UsageAcceptanceJournal>()
                .priceEvidence(tenantId, meterCode, "USD")
        }

/**
 * billingHasPriceEvidence은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun billingHasPriceEvidence(tenantId: String, meterCode: String): Boolean =
        transaction(billingContext) {
            billingContext.getBean<BillingInboxJournal>()
                .priceEvidence(tenantId, meterCode, "USD") != null
        }

/**
 * chargeCount은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun chargeCount(): Long =
        transaction(billingContext) {
            billingContext.getBean<BillingChargeRepository>().findAll().count().toLong()
        }

/**
 * chargeAmounts은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun chargeAmounts(): List<BigDecimal> =
        transaction(billingContext) {
            billingContext.getBean<BillingChargeRepository>().findAll().map { it.amount }
        }

/**
 * latestBillingEventId은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun latestBillingEventId(): UUID =
        transaction(billingContext) {
            billingContext.getBean<BillingOutboxRepository>().findAll().last().eventId
        }

/**
 * invoiceLineCount은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun invoiceLineCount(): Long =
        transaction(invoiceContext) {
            invoiceContext.getBean<InvoiceLineRepository>().findAll().count().toLong()
        }

/**
 * invoiceLines은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun invoiceLines(): List<InvoiceLine> =
        transaction(invoiceContext) {
            invoiceContext.getBean<InvoiceJournal>().lines
        }

/**
 * queryAppliedEventCount은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun queryAppliedEventCount(): Int =
        transaction(queryContext) {
            queryContext.getBean<QueryProjectionJournal>().readModelEventIds.size
        }

/**
 * queryRecoverySnapshot은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun queryRecoverySnapshot(): QueryRecoverySnapshot =
        transaction(queryContext) {
            queryContext.getBean<QueryRecoveryService>().snapshot()
        }

/**
 * redriveQueryEvent은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun redriveQueryEvent(eventId: UUID): Boolean =
        queryContext.getBean<QueryRecoveryService>()
            .redrive(eventId = eventId, "composition-operator", "correlation-$eventId")
            .requested

/**
 * queryTenantAccessAllowed은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun queryTenantAccessAllowed(authenticationTenant: String, targetTenant: String): Boolean {
        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            "composition-user",
            "n/a",
            listOf(SimpleGrantedAuthority("TENANT_$authenticationTenant")),
        )
        return try {
            queryContext.getBean<QueryTenantAuthorizer>().requireAccess(authentication, targetTenant)
            true
        } catch (_: AccessDeniedException) {
            false
        }
    }

/**
 * close은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * createTopics은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createTopics() {
        val properties = Properties().also { it["bootstrap.servers"] = brokerBootstrapServers() }

        Admin.create(properties).use { admin ->
            val topics = TOPICS.map {
                NewTopic(it, 1, 1.toShort())
            }
            admin.createTopics(topics).all().get()
        }
    }

/**
 * startContext은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun startContext(
        application: Class<*>,
        service: String,
        database: PostgreSQLServer,
    ): ConfigurableApplicationContext {
/**
 * sources은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val sources = if (service == "meter") {
            arrayOf(application, MeterCompositionTestConfiguration::class.java)
        } else {
            arrayOf(application)
        }
        return SpringApplicationBuilder(*sources).properties(
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
        ).run()
    }

/**
 * startBrokerPathProxy은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun startBrokerPathProxy() {
        if (!brokerPathProxy) return
        toxiproxy.start()

        brokerPath = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort).createProxy(
            "kafka-broker-path",
            "0.0.0.0:$TOXIPROXY_LISTEN_PORT",
            "$KAFKA_NETWORK_ALIAS:$KAFKA_PROXY_TARGET_PORT",
        )
    }

/**
 * brokerBootstrapServers은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun brokerBootstrapServers(): String =
        if (brokerPathProxy) brokerPathBootstrapServers() else kafka.bootstrapServers

/**
 * brokerPathBootstrapServers은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun brokerPathBootstrapServers(): String {
        return "${toxiproxy.host}:${toxiproxy.getMappedPort(TOXIPROXY_LISTEN_PORT)}"
    }

/**
 * postgresServer은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun postgresServer(): PostgreSQLServer =
        PostgreSQLServer(image = "postgres", tag = "16-alpine")

/**
 * meterTransaction은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun <T: Any> meterTransaction(block: () -> T): T =
        transaction(meterContext, block)

    @Suppress("UNCHECKED_CAST")
/**
 * usageKafkaTemplate은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun usageKafkaTemplate(): KafkaTemplate<String, String> =
        usageContext.getBean("usageKafkaTemplate") as KafkaTemplate<String, String>

/**
 * usageTransactionNullable은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun <T> usageTransactionNullable(block: () -> T?): T? {
        var result: T? = null
        TransactionTemplate(usageContext.getBean<PlatformTransactionManager>())
            .executeWithoutResult {
                result = block()
            }
        return result
    }

/**
 * transaction은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun <T: Any> transaction(context: ConfigurableApplicationContext, block: () -> T): T =
        TransactionTemplate(context.getBean<PlatformTransactionManager>())
            .execute { block() }
            .shouldNotBeNull()

/**
 * envelope은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * WireEvent 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class WireEvent(
/**
 * eventId은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val eventId: UUID,
/**
 * eventType은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val eventType: String,
/**
 * tenantId은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val tenantId: String,
/**
 * aggregateType은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val aggregateType: String,
/**
 * aggregateId은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val aggregateId: String,
/**
 * aggregateVersion은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val aggregateVersion: Long,
/**
 * payload은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payload: String,
/**
 * schemaVersion은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val schemaVersion: Int = 1,
/**
 * payloadDigest은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payloadDigest: String = digestOf(payload),
    )
}

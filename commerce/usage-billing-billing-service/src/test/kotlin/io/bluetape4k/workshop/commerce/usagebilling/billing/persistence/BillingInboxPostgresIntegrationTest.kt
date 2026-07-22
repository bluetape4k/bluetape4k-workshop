package io.bluetape4k.workshop.commerce.usagebilling.billing.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.billing.application.BillingInboxService
import io.bluetape4k.workshop.commerce.usagebilling.billing.application.BillingPricingEvidenceService
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidence
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Tag("integration")
@SpringBootTest
@Suppress("VarCouldBeVal") // Spring injects these mutable lateinit collaborators after construction.
class BillingInboxPostgresIntegrationTest {
    @Autowired
    private lateinit var pricingEvidence: BillingPricingEvidenceService

    @Autowired
    private lateinit var inbox: BillingInboxService

    @Autowired
    private lateinit var charges: BillingChargeRepository

    @Autowired
    private lateinit var outbox: BillingOutboxRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `accepted usage event creates one append-only charge and one pending outbox event`() {
        val meterCode = "api_calls_${UUID.randomUUID()}"
        pricingEvidence.record(
            BillingPriceEvidence("tenant-a", meterCode, "USD", BigDecimal("0.10"), Instant.EPOCH),
        )
        val chargeCount = transaction { charges.findAll().count() }
        val outboxCount = transaction { outbox.findAll().count() }
        val event = BillingInboxEvent(
            UUID.randomUUID(), "tenant-a", "Usage", "usage-1", 1, "digest-a", meterCode,
            quantity = BigDecimal.TWO,
        )

        inbox.handle(event) shouldBeEqualTo BillingInboxOutcome.APPLIED
        inbox.handle(event) shouldBeEqualTo BillingInboxOutcome.DUPLICATE

        transaction { charges.findAll().count() } shouldBeEqualTo chargeCount + 1
        transaction { charges.findAll().last().amount } shouldBeEqualTo BigDecimal("0.20")
        transaction { outbox.findAll().count() } shouldBeEqualTo outboxCount + 1
        transaction { outbox.findAll().last().status } shouldBeEqualTo "PENDING"
        val payload = transaction { outbox.findAll().last().payload }
        requireNotNull(Jackson.defaultJsonMapper.readTree(payload).get("eventType")).asString() shouldBeEqualTo
            "ChargeRated"
    }

    private fun <T : Any> transaction(block: () -> T): T =
        requireNotNull(TransactionTemplate(transactionManager).execute { block() })

    private companion object {
        val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
            registry.add("management.datadog.metrics.export.enabled") { false }
        }
    }
}

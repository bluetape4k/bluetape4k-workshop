package io.bluetape4k.workshop.commerce.usagebilling.usage.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.usage.application.PriceEvidenceService
import io.bluetape4k.workshop.commerce.usagebilling.usage.application.UsageCommandService
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.AcceptUsageCommand
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxOutcome
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
class UsageAcceptancePostgresIntegrationTest {
    @Autowired
    private lateinit var priceEvidence: PriceEvidenceService

    @Autowired
    private lateinit var usage: UsageCommandService

    @Autowired
    private lateinit var usageRecords: UsageRecordRepository

    @Autowired
    private lateinit var outbox: UsageOutboxRepository

    @Autowired
    private lateinit var priceEvidenceInbox: UsagePriceEvidenceInboxRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `persisted price evidence accepts one source event and replays its duplicate without a second outbox row`() {
        val meterCode = "api_calls_${UUID.randomUUID()}"
        priceEvidence.record(PriceEvidence("tenant-a", meterCode, "USD", BigDecimal("0.10"), NOW))
        val usageCount = transaction { usageRecords.findAll().count() }
        val outboxCount = transaction { outbox.findAll().count() }
        val command = AcceptUsageCommand(
            tenantId = "tenant-a",
            sourceSystem = "meter-agent",
            sourceEventId = "source_${UUID.randomUUID()}",
            meterCode = meterCode,
            currency = "USD",
            quantity = BigDecimal.ONE,
            occurredAt = NOW,
        )

        usage.accept(command).replayed shouldBeEqualTo false
        usage.accept(command).replayed shouldBeEqualTo true

        transaction { usageRecords.findAll().count() } shouldBeEqualTo usageCount + 1
        transaction { outbox.findAll().count() } shouldBeEqualTo outboxCount + 1
        transaction { outbox.findAll().last().status } shouldBeEqualTo "PENDING"
    }

    @Test
    fun `price evidence inbox atomically absorbs duplicate delivery and quarantines digest conflicts`() {
        val meterCode = "api_calls_${UUID.randomUUID()}"
        val eventId = UUID.randomUUID()
        val event = PriceEvidenceInboxEvent(
            eventId = eventId,
            tenantId = "tenant-a",
            payloadDigest = "a".repeat(64),
            evidence = PriceEvidence("tenant-a", meterCode, "USD", BigDecimal("0.10"), NOW),
        )

        priceEvidence.record(event) shouldBeEqualTo PriceEvidenceInboxOutcome.APPLIED
        priceEvidence.record(event) shouldBeEqualTo PriceEvidenceInboxOutcome.DUPLICATE
        priceEvidence.record(event.copy(payloadDigest = "b".repeat(64))) shouldBeEqualTo
            PriceEvidenceInboxOutcome.QUARANTINED

        transaction { priceEvidenceInbox.findAll().count() } shouldBeEqualTo 1
    }

    private fun <T : Any> transaction(block: () -> T): T =
        requireNotNull(TransactionTemplate(transactionManager).execute { block() })

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-22T00:00:00Z")
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

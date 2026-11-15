package io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.invoice.application.InvoiceInboxService
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxEvent
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.*

@SpringBootTest
class InvoiceInboxPostgresIntegrationTest {

    private companion object: KLogging() {
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

    @Autowired
    private val inbox: InvoiceInboxService = uninitialized()

    @Autowired
    private val lines: InvoiceLineRepository = uninitialized()

    @Autowired
    private val outbox: InvoiceOutboxRepository = uninitialized()

    @Autowired
    private val outboxJournal: ExposedInvoiceOutboxJournal = uninitialized()

    @Autowired
    private val transactionManager: PlatformTransactionManager = uninitialized()

    @Test
    fun `charge replay preserves one original line while adjustment appends a correction line`() {
        val initialCount = transaction { lines.findAll().count() }
        val initialOutboxCount = transaction { outbox.findAll().count() }
        val chargeEventId = UUID.randomUUID()
        val charge = InvoiceInboxEvent(chargeEventId, "ChargeRated", null, BigDecimal("0.10"))

        inbox.handle(charge).created.shouldBeTrue()
        inbox.handle(charge).created.shouldBeFalse()

        val adjustPost = InvoiceInboxEvent(
            eventId = UUID.randomUUID(),
            eventType = "AdjustmentPosted",
            correctionOf = chargeEventId,
            amount = BigDecimal("-0.10")
        )
        inbox.handle(adjustPost).created.shouldBeTrue()

        transaction { lines.findAll().count() } shouldBeEqualTo initialCount + 2
        transaction { requireNotNull(lines.findAll().last().correctionOf) } shouldBeEqualTo chargeEventId
        transaction { outbox.findAll().count() } shouldBeEqualTo initialOutboxCount + 2
        transaction { outbox.findAll().last().eventType } shouldBeEqualTo "InvoiceCorrectionIssued"
    }

    @Test
    fun `Invoice outbox claim and published mark remain fenced by owner`() {
        val event = InvoiceInboxEvent(UUID.randomUUID(), "ChargeRated", null, BigDecimal("1.00"))
        inbox.handle(event)
        val now = java.time.Instant.parse("2026-07-23T00:00:00Z")

        val lease = outboxJournal.claim("owner-a", now, 1).single()

        outboxJournal.markPublished(lease.eventId, "owner-b", now).shouldBeFalse()
        outboxJournal.markPublished(lease.eventId, "owner-a", now).shouldBeTrue()
    }

    private inline fun <T: Any> transaction(crossinline block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }.shouldNotBeNull()
}

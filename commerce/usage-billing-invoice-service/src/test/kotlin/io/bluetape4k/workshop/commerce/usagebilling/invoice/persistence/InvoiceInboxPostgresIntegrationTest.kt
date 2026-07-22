package io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.invoice.application.InvoiceInboxService
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID

@Tag("integration")
@SpringBootTest
class InvoiceInboxPostgresIntegrationTest {
    @Autowired
    private lateinit var inbox: InvoiceInboxService

    @Autowired
    private lateinit var lines: InvoiceLineRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `charge replay preserves one original line while adjustment appends a correction line`() {
        val initialCount = transaction { lines.findAll().count() }
        val chargeEventId = UUID.randomUUID()
        val charge = InvoiceInboxEvent(chargeEventId, "ChargeRated", null, BigDecimal("0.10"))

        inbox.handle(charge).created shouldBeEqualTo true
        inbox.handle(charge).created shouldBeEqualTo false
        inbox.handle(InvoiceInboxEvent(UUID.randomUUID(), "AdjustmentPosted", chargeEventId, BigDecimal("-0.10")))
            .created shouldBeEqualTo true

        transaction { lines.findAll().count() } shouldBeEqualTo initialCount + 2
        transaction { requireNotNull(lines.findAll().last().correctionOf) } shouldBeEqualTo chargeEventId
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

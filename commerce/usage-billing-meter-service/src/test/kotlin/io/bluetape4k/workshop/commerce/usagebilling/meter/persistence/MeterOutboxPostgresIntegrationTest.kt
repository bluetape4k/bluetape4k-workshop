package io.bluetape4k.workshop.commerce.usagebilling.meter.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.meter.application.MeterCommandService
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.ActivatePriceCommand
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
class MeterOutboxPostgresIntegrationTest {
    @Autowired
    private lateinit var service: MeterCommandService

    @Autowired
    private lateinit var priceVersions: MeterPriceVersionRepository

    @Autowired
    private lateinit var outbox: MeterOutboxRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `price activation commits an immutable price version and pending outbox event together`() {
        val priceVersionCount = transaction { priceVersions.findAll().count() }
        val outboxCount = transaction { outbox.findAll().count() }

        service.activatePrice(
            ActivatePriceCommand(
                idempotencyKey = "postgres-activation-1",
                tenantId = "tenant-a",
                meterCode = "api_calls",
                currency = "USD",
                unitPrice = BigDecimal("0.10"),
                effectiveAt = Instant.parse("2026-07-22T00:00:00Z"),
            ),
        )

        transaction { priceVersions.findAll().count() } shouldBeEqualTo priceVersionCount + 1
        transaction { outbox.findAll().count() } shouldBeEqualTo outboxCount + 1
        transaction { outbox.findAll().last().status } shouldBeEqualTo "PENDING"
    }

    @Test
    fun `price version constraint failure rolls back its local outbox and receipt`() {
        val meterCode = "api_calls_${UUID.randomUUID()}"
        val effectiveAt = Instant.parse("2026-07-22T01:00:00Z")
        val first = activation("postgres-atomic-1", meterCode, effectiveAt)
        service.activatePrice(first)
        val priceVersionCount = transaction { priceVersions.findAll().count() }
        val outboxCount = transaction { outbox.findAll().count() }

        runCatching { service.activatePrice(activation("postgres-atomic-2", meterCode, effectiveAt)) }
            .isFailure shouldBeEqualTo true

        transaction { priceVersions.findAll().count() } shouldBeEqualTo priceVersionCount
        transaction { outbox.findAll().count() } shouldBeEqualTo outboxCount
    }

    private fun activation(
        idempotencyKey: String,
        meterCode: String,
        effectiveAt: Instant,
    ): ActivatePriceCommand =
        ActivatePriceCommand(
            idempotencyKey = idempotencyKey,
            tenantId = "tenant-a",
            meterCode = meterCode,
            currency = "USD",
            unitPrice = BigDecimal("0.10"),
            effectiveAt = effectiveAt,
        )

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

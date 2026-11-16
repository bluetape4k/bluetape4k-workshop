package io.bluetape4k.workshop.commerce.usagebilling.meter.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
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
import java.util.*

@Tag("integration")
@SpringBootTest
@Suppress("VarCouldBeVal") // Spring injects these mutable lateinit collaborators after construction.
class MeterOutboxPostgresIntegrationTest {

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
    private lateinit var service: MeterCommandService

    @Autowired
    private lateinit var priceVersions: MeterPriceVersionRepository

    @Autowired
    private lateinit var outbox: MeterOutboxRepository

    @Autowired
    private lateinit var outboxJournal: ExposedMeterOutboxJournal

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

        assertFailsWith<Exception> {
            service.activatePrice(activation("postgres-atomic-2", meterCode, effectiveAt))
        }

        transaction { priceVersions.findAll().count() } shouldBeEqualTo priceVersionCount
        transaction { outbox.findAll().count() } shouldBeEqualTo outboxCount
    }

    @Test
    fun `an expired outbox claim can be safely reclaimed by a later worker`() {
        val activation = activation(
            idempotencyKey = "lease-${UUID.randomUUID()}",
            meterCode = "lease-meter",
            effectiveAt = Instant.EPOCH
        )
        val result = service.activatePrice(activation)
        val claimedAt = Instant.parse("2026-07-22T00:00:00Z")

        outboxJournal
            .claim("worker-a", claimedAt, 100)
            .map { it.eventId }
            .contains(result.eventId).shouldBeTrue()

        outboxJournal
            .claim("worker-b", claimedAt.plusSeconds(31), 100)
            .first { it.eventId == result.eventId }
            .claimOwner shouldBeEqualTo "worker-b"
    }

    @Test
    fun `conditional claim prevents a second worker from leasing an active event`() {
        val activation = activation("active-${UUID.randomUUID()}", "active-meter", Instant.EPOCH)
        val result = service.activatePrice(activation)
        val claimedAt = Instant.parse("2026-07-22T00:00:00Z")

        outboxJournal
            .claim("worker-a", claimedAt, 100)
            .map { it.eventId }
            .contains(result.eventId).shouldBeTrue()

        outboxJournal
            .claim("worker-b", claimedAt, 100)
            .map { it.eventId }
            .contains(result.eventId).shouldBeFalse()
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

    private inline fun <T: Any> transaction(crossinline block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }.shouldNotBeNull()
}

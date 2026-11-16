package io.bluetape4k.workshop.commerce.usagebilling.query.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryInboxService
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryQuarantineService
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryRecoveryService
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryQuarantineEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.*

@Tag("integration")
@SpringBootTest
class QueryProjectionPostgresIntegrationTest {

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
    private val inbox: QueryInboxService = uninitialized()

    @Autowired
    private val inboxEvents: QueryInboxRepository = uninitialized()

    @Autowired
    private val readModels: QueryReadModelRepository = uninitialized()

    @Autowired
    private val checkpoints: QueryCheckpointRepository = uninitialized()

    @Autowired
    private val recovery: QueryRecoveryService = uninitialized()

    @Autowired
    private val quarantine: QueryQuarantineService = uninitialized()

    @Autowired
    private val redriveAudits: QueryRedriveAuditRepository = uninitialized()

    @Autowired
    private val transactionManager: PlatformTransactionManager = uninitialized()

    @Test
    fun `replay persists one inbox receipt and one read model while advancing one checkpoint`() {
        val inboxCount = transaction { inboxEvents.findAll().count() }
        val readModelCount = transaction { readModels.findAll().count() }
        val checkpoint = transaction { checkpoints.findAll().singleOrNull()?.position ?: 0 }
        val event = QueryInboxEvent(UUID.randomUUID(), "tenant-a", "InvoiceIssued")

        inbox.apply(event).applied.shouldBeTrue()
        inbox.apply(event).applied.shouldBeFalse()

        transaction { inboxEvents.findAll().count() } shouldBeEqualTo inboxCount + 1
        transaction { readModels.findAll().count() } shouldBeEqualTo readModelCount + 1
        transaction { checkpoints.findAll().single().position } shouldBeEqualTo checkpoint + 1
    }

    @Test
    fun `redrive creates an immutable audit without mutating a financial projection`() {
        val auditCount = transaction { redriveAudits.findAll().count() }
        val eventId = UUID.randomUUID()
        quarantine.record(
            QueryQuarantineEvent(
                eventId = eventId,
                tenantId = "tenant-a",
                eventType = "InvoiceIssued",
                reason = "unsupported_schema",
                quarantinedAt = Instant.now()
            ),
        )

        recovery.redrive(eventId = eventId, "operator-a", "correlation-a").requested.shouldBeTrue()

        transaction { redriveAudits.findAll().count() } shouldBeEqualTo auditCount + 1
        transaction { requireNotNull(redriveAudits.findAll().last()).currentState } shouldBeEqualTo "REDRIVE_REQUESTED"
    }

    private inline fun <T: Any> transaction(crossinline block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }.shouldNotBeNull()
}

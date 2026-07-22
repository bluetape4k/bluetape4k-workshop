package io.bluetape4k.workshop.commerce.usagebilling.query.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryInboxService
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Tag("integration")
@SpringBootTest
@Suppress("VarCouldBeVal") // Spring injects these mutable lateinit collaborators after construction.
class QueryProjectionPostgresIntegrationTest {
    @Autowired
    private lateinit var inbox: QueryInboxService

    @Autowired
    private lateinit var inboxEvents: QueryInboxRepository

    @Autowired
    private lateinit var readModels: QueryReadModelRepository

    @Autowired
    private lateinit var checkpoints: QueryCheckpointRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `replay persists one inbox receipt and one read model while advancing one checkpoint`() {
        val inboxCount = transaction { inboxEvents.findAll().count() }
        val readModelCount = transaction { readModels.findAll().count() }
        val checkpoint = transaction { checkpoints.findAll().singleOrNull()?.position ?: 0 }
        val event = QueryInboxEvent(UUID.randomUUID(), "tenant-a", "InvoiceIssued")

        inbox.apply(event).applied shouldBeEqualTo true
        inbox.apply(event).applied shouldBeEqualTo false

        transaction { inboxEvents.findAll().count() } shouldBeEqualTo inboxCount + 1
        transaction { readModels.findAll().count() } shouldBeEqualTo readModelCount + 1
        transaction { checkpoints.findAll().single().position } shouldBeEqualTo checkpoint + 1
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

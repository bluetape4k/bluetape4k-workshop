package io.bluetape4k.workshop.optimization.planning.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.planning.PlanningContractsApplication
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAggregateTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAuditTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningCallbackInboxTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxRecord
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    classes = [PlanningContractsApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
internal class PlanningRequestServiceTest @Autowired constructor(
    private val service: PlanningRequestService,
    private val requestRepository: PlanningRequestRepository,
    private val outboxRepository: PlanningOutboxRepository,
) {

    @BeforeEach
    fun resetSchema() {
        transaction {
            SchemaUtils.drop(
                PlanningAuditTable,
                PlanningCallbackInboxTable,
                PlanningOutboxTable,
                PlanningRequestTable,
                PlanningAggregateTable,
            )
            SchemaUtils.create(
                PlanningAggregateTable,
                PlanningRequestTable,
                PlanningOutboxTable,
                PlanningCallbackInboxTable,
                PlanningAuditTable,
            )
        }
    }

    @Test
    fun `request and outbox are committed together`() {
        val created = service.create(command(), REQUEST_ID)

        created.id shouldBeEqualTo REQUEST_ID
        transaction {
            requestRepository.count() shouldBeEqualTo 1L
            outboxRepository.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `request rolls back when outbox insert fails`() {
        transaction {
            outboxRepository.save(
                PlanningOutboxRecord(
                    planningRequestId = REQUEST_ID,
                    payload = "existing",
                    nextAttemptAt = Instant.EPOCH,
                ),
            )
        }

        assertFailsWith<Exception> {
            service.create(command(), REQUEST_ID)
        }

        transaction {
            requestRepository.count() shouldBeEqualTo 0L
            outboxRepository.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `request rejects a provider that is not active`() {
        assertFailsWith<IllegalArgumentException> {
            service.create(command().copy(provider = PlanningProvider.TIMEFOLD_PLATFORM), REQUEST_ID)
        }

        transaction {
            requestRepository.count() shouldBeEqualTo 0L
            outboxRepository.count() shouldBeEqualTo 0L
        }
    }

    private fun command() = CreatePlanningRequest(
        aggregateId = "roster-42",
        aggregateVersion = 7,
        datasetId = "dataset-42",
        parentRevision = null,
        provider = PlanningProvider.FAKE,
    )

    companion object {
        private val postgres = PostgreSQLServer.Launcher.postgres
        private val REQUEST_ID = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { requireNotNull(postgres.username) }
            registry.add("spring.datasource.password") { requireNotNull(postgres.password) }
        }
    }
}

package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanningStateRepositoryTest {

    companion object: KLogging() {
        private val REQUEST_ID = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b")
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

    private val aggregateRepository = PlanningAggregateRepository()
    private val requestRepository = PlanningRequestRepository()
    private val auditRepository = PlanningAuditRepository()

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username.shouldNotBeNull(),
            password = postgres.password.shouldNotBeNull(),
        )
    }

    @BeforeEach
    fun createSchema() {
        transaction {
            SchemaUtils.drop(PlanningAuditTable, PlanningRequestTable, PlanningAggregateTable)
            SchemaUtils.create(PlanningAggregateTable, PlanningRequestTable, PlanningAuditTable)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction {
            SchemaUtils.drop(PlanningAuditTable, PlanningRequestTable, PlanningAggregateTable)
        }
    }

    @Test
    fun `aggregate repository compares the current version`() {
        transaction {
            val saved = aggregateRepository.save(PlanningAggregateRecord(aggregateId = "roster-42", version = 7))

            log.debug { "saved=$saved" }
            aggregateRepository.existsById(saved.id).shouldBeTrue()
            aggregateRepository.versionMatches("roster-42", 7).shouldBeTrue()
            aggregateRepository.versionMatches("roster-42", 8).shouldBeFalse()
        }
    }

    @Test
    fun `request repository accepts only a newer provider revision`() {
        transaction {
            requestRepository.save(requestRecord())

            requestRepository.acceptIfNewer(
                requestId = REQUEST_ID,
                providerRevision = 2,
                scoreSummary = "0hard/-2soft",
                redactedExplanation = "balanced workload",
            ).shouldBeTrue()

            requestRepository.acceptIfNewer(
                requestId = REQUEST_ID,
                providerRevision = 1,
                scoreSummary = "stale",
                redactedExplanation = "stale",
            ).shouldBeFalse()

            val stored = requestRepository.findById(REQUEST_ID)

            log.debug { "stored=$stored" }
            stored.acceptedRevision shouldBeEqualTo 2L
            stored.scoreSummary shouldBeEqualTo "0hard/-2soft"
        }
    }

    @Test
    fun `audit repository appends immutable decisions`() {
        transaction {
            val saved = auditRepository.append(
                PlanningAuditRecord(
                    planningRequestId = REQUEST_ID,
                    callbackEventId = "event-42",
                    aggregateVersion = 7,
                    providerRevision = 2,
                    status = PlanningStatus.SUCCEEDED,
                    scoreSummary = "0hard/-2soft",
                    redactedExplanation = "balanced workload",
                    decision = PlanningAuditDecision.ACCEPTED,
                ),
            )

            auditRepository.findById(saved.id).decision shouldBeEqualTo PlanningAuditDecision.ACCEPTED
            auditRepository.count() shouldBeEqualTo 1L
        }
    }

    private fun requestRecord(): PlanningRequestRecord =
        PlanningRequestRecord(
            id = REQUEST_ID,
            aggregateId = "roster-42",
            aggregateVersion = 7,
            datasetId = "dataset-42",
            parentRevision = null,
            status = PlanningStatus.QUEUED,
            provider = PlanningProvider.FAKE,
        )

}

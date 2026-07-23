package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.OperatorAudits
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class OperatorAuditRepositoryIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private lateinit var database: Database
    private val rebuilds = ProjectionRebuildRepository()
    private val audits = OperatorAuditRepository()
    private lateinit var operator: EventSourcedRebuildOperator

    @BeforeAll
    fun connectPostgres() {
        database =
            Database.connect(
                url = postgres.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = requireNotNull(postgres.username),
                password = requireNotNull(postgres.password),
            )
        operator =
            EventSourcedRebuildOperator(
                EventSourcedPermitTransactionRunner(
                    database,
                    EventSourcedDatabasePermitGate(),
                    EventSourcedDatabaseLane.FOREGROUND,
                ),
                rebuilds,
                audits,
            )
    }

    @BeforeEach
    fun setUp() {
        transaction(database) {
            SchemaUtils.create(ProjectionGenerations, ActiveProjectionGenerations, OperatorAudits)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(database) {
            SchemaUtils.drop(OperatorAudits, ActiveProjectionGenerations, ProjectionGenerations)
        }
    }

    @Test
    fun `duplicate audit insertion rolls back its rebuild cancellation mutation`() {
        val candidate =
            transaction(database) {
                rebuilds.initializeActive(PROJECTION, NOW)
                rebuilds.start(PROJECTION, targetPosition = 1, now = NOW)
            }
        val audit = audit(candidate.key.generation)
        transaction(database) { audits.append(audit) }

        assertFailsWith<Exception> {
            operator.cancel(cancellation(candidate.key.generation))
        }

        transaction(database) {
            ProjectionGenerations
                .selectAll()
                .where { ProjectionGenerations.generation eq candidate.key.generation }
                .single()[ProjectionGenerations.state] shouldBeEqualTo ProjectionGenerationState.BUILDING
            OperatorAudits.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `fenced cancellation writes applied audit with its state transition`() {
        val candidate =
            transaction(database) {
                rebuilds.initializeActive(PROJECTION, NOW)
                rebuilds.start(PROJECTION, targetPosition = 1, now = NOW)
            }

        val outcome = operator.cancel(cancellation(candidate.key.generation))

        outcome.state shouldBeEqualTo ProjectionGenerationState.CANCELLING
        outcome.audit.outcome shouldBeEqualTo OperatorAuditOutcome.APPLIED
        transaction(database) {
            OperatorAudits.selectAll().single().let { row ->
                row[OperatorAudits.beforeState] shouldBeEqualTo ProjectionGenerationState.BUILDING
                row[OperatorAudits.afterState] shouldBeEqualTo ProjectionGenerationState.CANCELLING
                row[OperatorAudits.expectedFencingToken] shouldBeEqualTo candidate.fencingToken
            }
        }
    }

    private fun audit(generation: Long): OperatorAuditEntry =
        OperatorAuditEntry(
            identity =
                OperatorAuditIdentity(
                    actorDigest = ReceiptDigest.sha256("operator-a"),
                    tenant = "tenant-a",
                    requestDigest = ReceiptDigest.sha256("request-a"),
                ),
            action = OperatorAuditAction.REBUILD_CANCELLED,
            target = OperatorAuditTarget(PROJECTION, generation, expectedFencingToken = 1),
            transition =
                OperatorAuditTransition(
                    beforeState = ProjectionGenerationState.BUILDING,
                    afterState = ProjectionGenerationState.CANCELLING,
                    checkpointPosition = 0,
                    streamPosition = 1,
                    reasonClass = null,
                ),
            result = OperatorAuditResult(OperatorAuditOutcome.APPLIED, NOW),
        )

    private fun cancellation(generation: Long): RebuildCancellationCommand =
        RebuildCancellationCommand(
            identity =
                OperatorAuditIdentity(
                    actorDigest = ReceiptDigest.sha256("operator-a"),
                    tenant = "tenant-a",
                    requestDigest = ReceiptDigest.sha256("request-a"),
                ),
            target = OperatorAuditTarget(PROJECTION, generation, expectedFencingToken = 1),
            checkpointPosition = 0,
            streamPosition = 1,
            occurredAt = NOW,
        )

    private companion object {
        private const val PROJECTION = "voucher-lifecycle"
        private val NOW = Instant.parse("2026-07-23T14:00:00Z")
    }
}

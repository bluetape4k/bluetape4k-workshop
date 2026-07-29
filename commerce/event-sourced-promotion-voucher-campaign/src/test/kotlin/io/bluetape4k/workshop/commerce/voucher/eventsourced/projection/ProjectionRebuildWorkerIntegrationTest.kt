package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendFences
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExposedEventStoreTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionProcessedEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamHeads
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ProjectionRebuildWorkerIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val leases = ProjectionLeaseRepository()
    private val rebuilds = ProjectionRebuildRepository()
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database
    private lateinit var events: EventStoreRepository

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-rebuild-worker")
        database = postgresDatabase.database
        events = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @BeforeEach
    fun createSchema() = transaction(database) { SchemaUtils.create(*TABLES) }

    @AfterEach
    fun dropSchema() = transaction(database) { SchemaUtils.drop(*TABLES) }

    @Test
    fun `bounded rebuild page updates only the candidate generation before activation`() {
        appendEvents()
        val active = transaction(database) { rebuilds.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { rebuilds.start(PROJECTION, TARGET_POSITION, NOW) }
        val lease =
            transaction(database) { leases.acquire(PROJECTION, candidate.key.generation, OWNER, NOW) }
                .shouldNotBeNull()
        val projections = ProjectionRepository(leases)
        val worker = ProjectionRebuildWorker(rebuilds, projections, ExposedProjectionEventReader())

        val result = transaction(database) { worker.poll(candidate.key, lease, NOW) }

        (result is ProjectionRebuildPollResult.Applied).shouldBeTrue()
        (result as ProjectionRebuildPollResult.Applied).position shouldBeEqualTo TARGET_POSITION
        transaction(database) { findGeneration(candidate.key) }?.currentPosition shouldBeEqualTo TARGET_POSITION
        transaction(database) { projections.checkpoint(candidate.key) }?.position shouldBeEqualTo TARGET_POSITION
        transaction(database) { findActive(PROJECTION) }?.generation shouldBeEqualTo active.generation
    }

    @Test
    fun `worker fenced by prior cancellation cannot write candidate projection state`() {
        appendEvents()
        transaction(database) { rebuilds.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { rebuilds.start(PROJECTION, TARGET_POSITION, NOW) }
        val lease =
            transaction(database) { leases.acquire(PROJECTION, candidate.key.generation, OWNER, NOW) }
                .shouldNotBeNull()
        transaction(database) { rebuilds.requestCancellation(candidate.key, NOW) }
        val projections = ProjectionRepository(leases)
        val worker = ProjectionRebuildWorker(rebuilds, projections, ExposedProjectionEventReader())

        transaction(database) { worker.poll(candidate.key, lease, NOW) } shouldBeEqualTo
            ProjectionRebuildPollResult.StaleWorker
        transaction(database) { projections.checkpoint(candidate.key) }.shouldBeNull()
        transaction(database) { findGeneration(candidate.key) }?.currentPosition shouldBeEqualTo 0L
    }

    private fun appendEvents() {
        transaction(database) {
            events.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = StreamKey(TenantId("tenant-a"), STREAM_TYPE, STREAM_ID),
                        expectedVersion = 0,
                        events =
                            listOf(
                                eventToAppend(FIRST_EVENT_ID, "voucher.allocated"),
                                eventToAppend(SECOND_EVENT_ID, "voucher.redeemed"),
                            ),
                    ),
                ),
            )
        }
    }

    private fun eventToAppend(
        eventId: String,
        eventType: String,
    ): EventToAppend =
        EventToAppend(
            eventId = UUID.fromString(eventId),
            eventType = eventType,
            schemaVersion = 1,
            payload = EventPayload("{}"),
        )

    private companion object {
        private const val PROJECTION = "voucher-lifecycle"
        private const val OWNER = "rebuild-owner"
        private const val STREAM_TYPE = "voucher"
        private const val FIRST_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc301"
        private const val SECOND_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc302"
        private const val TARGET_POSITION = 2L
        private val NOW = Instant.parse("2026-07-23T13:00:00Z")
        private val STREAM_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc300")
        private val TABLES =
            arrayOf(
                EventLog,
                StreamHeads,
                AppendFences,
                ProjectionLeases,
                ProjectionProcessedEvents,
                ProjectionReadModels,
                ProjectionCheckpoints,
                ProjectionPoisonEvents,
                ProjectionGenerations,
                ActiveProjectionGenerations,
            )
    }
}

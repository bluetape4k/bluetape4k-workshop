package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendFences
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExposedEventStoreTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionProcessedEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamHeads
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.awaitility.Awaitility.await
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ProjectionWorkerIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val leases = ProjectionLeaseRepository()
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database
    private lateinit var events: EventStoreRepository

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-projection-worker")
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
    fun `worker applies one committed keyset page and reports remaining lag`() {
        appendEvent()
        val repository = ProjectionRepository(leases)
        val worker = ProjectionWorker(repository, ExposedProjectionEventReader())
        val lease = acquireLease()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result =
                executor.submit<ProjectionPollResult> {
                    transaction(database) { worker.poll(KEY, lease, NOW) }
                }

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).untilAsserted {
                transaction(database) { repository.checkpoint(KEY) }?.position shouldBeEqualTo 1L
            }

            val outcome = result.get()
            (outcome is ProjectionPollResult.Applied).shouldBeTrue()
            val applied = outcome as ProjectionPollResult.Applied
            applied.appliedEventCount shouldBeEqualTo 1
            applied.lag shouldBeEqualTo 0L
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `poisoned worker result records metadata while leaving checkpoint unchanged`() {
        appendEvent()
        val repository = ProjectionRepository(leases)
        val worker = ProjectionWorker(repository, ExposedProjectionEventReader(), RejectingHandler)
        val lease = acquireLease()

        val result = transaction(database) { worker.poll(KEY, lease, NOW) }

        (result is ProjectionPollResult.Degraded).shouldBeTrue()
        (result as ProjectionPollResult.Degraded).reasonClass shouldBeEqualTo "UNKNOWN_SCHEMA"
        transaction(database) { repository.checkpoint(KEY) }.shouldBeNull()
    }

    private fun appendEvent() {
        transaction(database) {
            events.appendAll(
                listOf(
                    ExpectedAppend(
                        StreamKey(TenantId("tenant-a"), STREAM_TYPE, STREAM_ID),
                        expectedVersion = 0,
                        events =
                            listOf(
                                EventToAppend(
                                    eventId = UUID.fromString(EVENT_ID),
                                    eventType = "voucher.allocated",
                                    schemaVersion = 1,
                                    payload = EventPayload("{}"),
                                ),
                            ),
                    ),
                ),
            )
        }
    }

    private fun acquireLease(): ProjectionLease =
        transaction(database) { leases.acquire(PROJECTION, GENERATION, "owner-a", NOW) }.shouldNotBeNull()

    private object RejectingHandler : ProjectionEventHandler {
        override fun verify(event: EventEnvelope) {
            throw ProjectionPoisonException("UNKNOWN_SCHEMA")
        }
    }

    private companion object {
        private const val PROJECTION = "voucher-lifecycle"
        private const val GENERATION = 1L
        private const val STREAM_TYPE = "voucher"
        private const val EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc201"
        private const val AWAIT_TIMEOUT_SECONDS = 5L
        private val NOW = Instant.parse("2026-07-23T13:00:00Z")
        private val KEY = ProjectionKey(PROJECTION, GENERATION)
        private val STREAM_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc200")
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
            )
    }
}

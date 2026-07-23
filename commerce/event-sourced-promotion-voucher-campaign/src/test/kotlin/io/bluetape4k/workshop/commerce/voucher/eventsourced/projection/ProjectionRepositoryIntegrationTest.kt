package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.StreamReference
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionProcessedEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionReadModels
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
internal class ProjectionRepositoryIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val leases = ProjectionLeaseRepository()
    private val repository = ProjectionRepository(leases)
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        database =
            Database.connect(
                url = postgres.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = requireNotNull(postgres.username),
                password = requireNotNull(postgres.password),
            )
    }

    @BeforeEach
    fun createSchema() = transaction(database) { SchemaUtils.create(*TABLES) }

    @AfterEach
    fun dropSchema() = transaction(database) { SchemaUtils.drop(*TABLES) }

    @Test
    fun `duplicate and delayed delivery converge to one latest stream read model`() {
        val streamId = UUID.randomUUID()
        val lease = acquireLease()
        val events = listOf(event(FIRST_EVENT_ID, streamId, 1L, 1L), event(SECOND_EVENT_ID, streamId, 2L, 2L))

        val first = transaction(database) { repository.applyBatch(KEY, lease, events, NOW) }
        val duplicate = transaction(database) { repository.applyBatch(KEY, lease, events, RETRY_AT) }
        val readModel = transaction(database) { repository.readModel(KEY, STREAM_TYPE, streamId) }

        first.appliedEventCount shouldBeEqualTo 2
        first.duplicateEventCount shouldBeEqualTo 0
        duplicate.appliedEventCount shouldBeEqualTo 0
        duplicate.duplicateEventCount shouldBeEqualTo 2
        transaction(database) { repository.checkpoint(KEY) }?.position shouldBeEqualTo 2L
        requireNotNull(readModel).streamVersion shouldBeEqualTo 2L
        readModel.globalPosition shouldBeEqualTo 2L
    }

    @Test
    fun `stale lease cannot mutate projection state after a takeover`() {
        val streamId = UUID.randomUUID()
        val first = acquireLease()
        val second =
            requireNotNull(
                transaction(database) {
                    leases.acquire(PROJECTION, GENERATION, "owner-b", TAKEOVER_AT)
                },
            )

        assertFailsWith<ProjectionLeaseLostException> {
            transaction(database) {
                repository.applyBatch(KEY, first, listOf(event(FIRST_EVENT_ID, streamId, 1L, 1L)), TAKEOVER_AT)
            }
        }

        transaction(database) {
            repository.applyBatch(KEY, second, listOf(event(FIRST_EVENT_ID, streamId, 1L, 1L)), TAKEOVER_AT)
        }
            .appliedEventCount shouldBeEqualTo 1
    }

    @Test
    fun `interrupted handler transaction rolls back event identity read model and checkpoint together`() {
        val streamId = UUID.randomUUID()
        val lease = acquireLease()

        assertFailsWith<IllegalStateException> {
            transaction(database) {
                repository.applyBatch(KEY, lease, listOf(event(FIRST_EVENT_ID, streamId, 1L, 1L)), NOW)
                error("handler interrupted")
            }
        }

        transaction(database) { repository.checkpoint(KEY) }.shouldBeNull()
        transaction(database) { repository.readModel(KEY, STREAM_TYPE, streamId) }.shouldBeNull()
    }

    @Test
    fun `poison records retry metadata without advancing the checkpoint`() {
        val lease = acquireLease()
        val event = event(FIRST_EVENT_ID, UUID.randomUUID(), 1L, 1L)

        transaction(database) { repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", NOW) }
        transaction(database) { repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT) }
        transaction(database) { repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT) }
        val exhausted = transaction(database) { repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT) }

        exhausted.attempts shouldBeEqualTo MAX_PROJECTION_POISON_ATTEMPTS
        transaction(database) { repository.checkpoint(KEY) }.shouldBeNull()
    }

    private fun acquireLease(): ProjectionLease =
        requireNotNull(transaction(database) { leases.acquire(PROJECTION, GENERATION, "owner-a", NOW) })

    private fun event(
        eventId: String,
        streamId: UUID,
        streamVersion: Long,
        globalPosition: Long,
    ): EventEnvelope =
        EventEnvelope(
            eventId = UUID.fromString(eventId),
            tenantId = TenantId("tenant-a"),
            stream = StreamReference(STREAM_TYPE, streamId, streamVersion),
            globalPosition = globalPosition,
            eventType = "voucher.allocated",
            schemaVersion = 1,
            occurredAt = NOW,
            recordedAt = NOW,
            correlationId = eventId,
            causationId = null,
            actorSurrogate = "actor-digest",
            payload = EventPayload("{}"),
        )

    private companion object {
        private const val PROJECTION = "voucher-lifecycle"
        private const val GENERATION = 1L
        private const val STREAM_TYPE = "voucher"
        private const val RETRY_SECONDS = 1L
        private const val LEASE_TAKEOVER_SECONDS = 16L
        private const val FIRST_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc101"
        private const val SECOND_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc102"
        private val NOW = Instant.parse("2026-07-23T13:00:00Z")
        private val RETRY_AT = NOW.plusSeconds(RETRY_SECONDS)
        private val TAKEOVER_AT = NOW.plusSeconds(LEASE_TAKEOVER_SECONDS)
        private val KEY = ProjectionKey(PROJECTION, GENERATION)
        private val TABLES =
            arrayOf(
                ProjectionLeases,
                ProjectionProcessedEvents,
                ProjectionReadModels,
                ProjectionCheckpoints,
                ProjectionPoisonEvents,
            )
    }
}

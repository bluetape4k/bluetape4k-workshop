package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.StreamReference
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.CampaignProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionProcessedEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionReadModels
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
internal class ProjectionRepositoryIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val leases = ProjectionLeaseRepository()
    private val repository = ProjectionRepository(leases)
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-projection-repository")
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @BeforeEach
    fun createSchema() = transaction(database) { SchemaUtils.create(*TABLES) }

    @AfterEach
    fun dropSchema() = transaction(database) { SchemaUtils.drop(*TABLES) }

    @Test
    fun `duplicate and delayed delivery converge to one latest stream read model`() {
        val streamId = Uuid.V7.nextUUID()
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
        readModel.shouldNotBeNull().streamVersion shouldBeEqualTo 2L
        readModel.globalPosition shouldBeEqualTo 2L
    }

    @Test
    fun `stale lease cannot mutate projection state after a takeover`() {
        val streamId = Uuid.V7.nextUUID()
        val first = acquireLease()
        val second =
            transaction(database) {
                leases.acquire(PROJECTION, GENERATION, "owner-b", TAKEOVER_AT)
            }.shouldNotBeNull()

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
        val streamId = Uuid.V7.nextUUID()
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
    fun `campaign events update the semantic read model in the fenced projection transaction`() {
        val campaignId = Uuid.V7.nextUUID()
        val lease = acquireLease()
        val events =
            listOf(
                campaignEvent(
                    campaignId = campaignId,
                    identity = CampaignEventIdentity(FIRST_EVENT_ID, 1L, 1L, "campaign.created"),
                    payload =
                        """
                        {
                          "startsAt": "2026-07-24T00:00:00Z",
                          "endsAt": "2026-07-31T00:00:00Z",
                          "capacity": 100,
                          "perUserLimit": 2,
                          "redemptionTtlSeconds": 3600
                        }
                        """.trimIndent(),
                ),
                campaignEvent(
                    campaignId = campaignId,
                    identity = CampaignEventIdentity(SECOND_EVENT_ID, 2L, 2L, "campaign.activated"),
                    payload = "{}",
                ),
            )

        transaction(database) { repository.applyBatch(KEY, lease, events, NOW) }
        val campaign = transaction(database) { repository.campaign(KEY, TenantId("tenant-a"), campaignId) }

        campaign.shouldNotBeNull().state shouldBeEqualTo CampaignState.ACTIVE
        campaign.streamVersion shouldBeEqualTo 2L
        campaign.globalPosition shouldBeEqualTo 2L
        campaign.policyVersion shouldBeEqualTo 1L
        campaign.capacity shouldBeEqualTo 100
        campaign.allocatedCount shouldBeEqualTo 0
    }

    @Test
    fun `poison records retry metadata without advancing the checkpoint`() {
        val lease = acquireLease()
        val event = event(FIRST_EVENT_ID, Uuid.V7.nextUUID(), 1L, 1L)

        transaction(database) { repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", NOW) }
        transaction(database) { repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT) }
        transaction(database) { repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT) }
        transaction(database) { repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT) }
        val exhausted = transaction(database) { repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT) }

        exhausted.attempts shouldBeEqualTo MAX_PROJECTION_POISON_ATTEMPTS
        transaction(database) { repository.checkpoint(KEY) }.shouldBeNull()
    }

    private fun acquireLease(): ProjectionLease =
        transaction(database) { leases.acquire(PROJECTION, GENERATION, "owner-a", NOW) }.shouldNotBeNull()

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

    private fun campaignEvent(
        campaignId: UUID,
        identity: CampaignEventIdentity,
        payload: String,
    ): EventEnvelope =
        EventEnvelope(
            eventId = UUID.fromString(identity.eventId),
            tenantId = TenantId("tenant-a"),
            stream = StreamReference("campaign", campaignId, identity.streamVersion),
            globalPosition = identity.globalPosition,
            eventType = identity.eventType,
            schemaVersion = 1,
            occurredAt = NOW,
            recordedAt = NOW,
            correlationId = identity.eventId,
            causationId = null,
            actorSurrogate = "actor-digest",
            payload = EventPayload(payload),
        )

    private data class CampaignEventIdentity(
        val eventId: String,
        val streamVersion: Long,
        val globalPosition: Long,
        val eventType: String,
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
                CampaignProjectionReadModels,
                ProjectionCheckpoints,
                ProjectionPoisonEvents,
            )
    }
}

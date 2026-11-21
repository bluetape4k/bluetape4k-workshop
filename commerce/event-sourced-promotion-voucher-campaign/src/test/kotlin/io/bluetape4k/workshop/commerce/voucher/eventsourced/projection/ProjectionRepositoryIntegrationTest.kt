package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.StreamReference
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.CampaignProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
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
import java.io.Serializable
import java.time.Instant
import java.util.*

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * ProjectionRepositoryIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ProjectionRepositoryIntegrationTest {

    private companion object: KLogging() {
/**
 * postgres은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }

/**
 * PROJECTION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROJECTION = "voucher-lifecycle"
/**
 * GENERATION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val GENERATION = 1L
/**
 * STREAM_TYPE은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val STREAM_TYPE = "voucher"
/**
 * RETRY_SECONDS은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val RETRY_SECONDS = 1L
/**
 * LEASE_TAKEOVER_SECONDS은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val LEASE_TAKEOVER_SECONDS = 16L
/**
 * FIRST_EVENT_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val FIRST_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc101"
/**
 * SECOND_EVENT_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val SECOND_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc102"
/**
 * NOW은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-23T13:00:00Z")
/**
 * RETRY_AT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val RETRY_AT = NOW.plusSeconds(RETRY_SECONDS)
/**
 * TAKEOVER_AT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val TAKEOVER_AT = NOW.plusSeconds(LEASE_TAKEOVER_SECONDS)
/**
 * KEY은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val KEY = ProjectionKey(PROJECTION, GENERATION)
/**
 * TABLES은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * leases은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val leases = ProjectionLeaseRepository()
/**
 * repository은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = ProjectionRepository(leases)
/**
 * postgresDatabase은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
/**
 * database은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-projection-repository")
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() {
        postgresDatabase.close()
    }

    @BeforeEach
    fun createSchema() {
        transaction(database) { SchemaUtils.create(*TABLES) }
    }

    @AfterEach
    fun dropSchema() {
        transaction(database) { SchemaUtils.drop(*TABLES) }
    }

    @Test
/**
 * 이 시나리오는 "duplicate and delayed delivery converge to one latest stream read model" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `duplicate and delayed delivery converge to one latest stream read model`() {
        val streamId = Uuid.V7.nextUUID()
        val lease = acquireLease()
        val events = listOf(
            event(FIRST_EVENT_ID, streamId, 1L, 1L),
            event(SECOND_EVENT_ID, streamId, 2L, 2L)
        )

        val first = transaction(database) { repository.applyBatch(KEY, lease, events, NOW) }
        val duplicate = transaction(database) { repository.applyBatch(KEY, lease, events, RETRY_AT) }
        val readModel = transaction(database) {
            repository.readModel(KEY, STREAM_TYPE, streamId)
        }.shouldNotBeNull()
        log.debug { "readModel: $readModel" }

        first.appliedEventCount shouldBeEqualTo 2
        first.duplicateEventCount shouldBeEqualTo 0
        duplicate.appliedEventCount shouldBeEqualTo 0
        duplicate.duplicateEventCount shouldBeEqualTo 2

        transaction(database) { repository.checkpoint(KEY)?.position } shouldBeEqualTo 2L

        readModel.streamVersion shouldBeEqualTo 2L
        readModel.globalPosition shouldBeEqualTo 2L
    }

    @Test
/**
 * 이 시나리오는 "stale lease cannot mutate projection state after a takeover" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stale lease cannot mutate projection state after a takeover`() {
        val streamId = Uuid.V7.nextUUID()
        val first = acquireLease()
        val second = transaction(database) {
            leases.acquire(PROJECTION, GENERATION, "owner-b", TAKEOVER_AT)
        }.shouldNotBeNull()

        assertFailsWith<ProjectionLeaseLostException> {
            transaction(database) {
                repository.applyBatch(
                    key = KEY,
                    lease = first,
                    events = listOf(event(FIRST_EVENT_ID, streamId, 1L, 1L)),
                    now = TAKEOVER_AT
                )
            }
        }

        transaction(database) {
            repository.applyBatch(
                KEY,
                second,
                listOf(event(FIRST_EVENT_ID, streamId, 1L, 1L)),
                TAKEOVER_AT
            ).appliedEventCount shouldBeEqualTo 1
        }
    }

    @Test
/**
 * 이 시나리오는 "interrupted handler transaction rolls back event identity read model and checkpoint
 * together" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `interrupted handler transaction rolls back event identity read model and checkpoint together`() {
        val streamId = Uuid.V7.nextUUID()
        val lease = acquireLease()

        assertFailsWith<IllegalStateException> {
            transaction(database) {
                repository.applyBatch(KEY, lease, listOf(event(FIRST_EVENT_ID, streamId, 1L, 1L)), NOW)
                error("handler interrupted")
            }
        }

        transaction(database) {
            repository.checkpoint(KEY).shouldBeNull()
            repository.readModel(KEY, STREAM_TYPE, streamId).shouldBeNull()
        }
    }

    @Test
/**
 * 이 시나리오는 "campaign events update the semantic read model in the fenced projection transaction" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `campaign events update the semantic read model in the fenced projection transaction`() {
        val campaignId = Uuid.V7.nextUUID()
        val lease = acquireLease()
        val events = listOf(
            campaignEvent(
                campaignId = campaignId,
                identity = CampaignEventIdentity(FIRST_EVENT_ID, 1L, 1L, "campaign.created"),
                payload = """
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
        val campaign = transaction(database) {
            repository.campaign(KEY, TenantId("tenant-a"), campaignId).shouldNotBeNull()
        }

        log.debug { "campaign: $campaign" }
        campaign.state shouldBeEqualTo CampaignState.ACTIVE
        campaign.streamVersion shouldBeEqualTo 2L
        campaign.globalPosition shouldBeEqualTo 2L
        campaign.policyVersion shouldBeEqualTo 1L
        campaign.capacity shouldBeEqualTo 100
        campaign.allocatedCount shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "poison records retry metadata without advancing the checkpoint" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `poison records retry metadata without advancing the checkpoint`() {
        val lease = acquireLease()
        val event = event(FIRST_EVENT_ID, Uuid.V7.nextUUID(), 1L, 1L)

        transaction(database) {
            repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", NOW)
            repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT)
            repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT)
            repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT)
        }

        val exhausted = transaction(database) {
            repository.poison(KEY, lease, event, "UNKNOWN_SCHEMA", RETRY_AT)
        }

        exhausted.attempts shouldBeEqualTo MAX_PROJECTION_POISON_ATTEMPTS
        transaction(database) {
            repository.checkpoint(KEY).shouldBeNull()
        }
    }

/**
 * acquireLease은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun acquireLease(): ProjectionLease =
        transaction(database) {
            leases.acquire(PROJECTION, GENERATION, "owner-a", NOW).shouldNotBeNull()
        }

/**
 * event은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * campaignEvent은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * CampaignEventIdentity 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class CampaignEventIdentity(
/**
 * eventId은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val eventId: String,
/**
 * streamVersion은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val streamVersion: Long,
/**
 * globalPosition은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val globalPosition: Long,
/**
 * eventType은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val eventType: String,
    ): Serializable
}

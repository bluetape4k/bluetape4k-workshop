package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendFences
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExposedEventStoreTransactionRunner
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
import java.util.*

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * ProjectionRebuildWorkerIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ProjectionRebuildWorkerIntegrationTest {

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
 * OWNER은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val OWNER = "rebuild-owner"
/**
 * STREAM_TYPE은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val STREAM_TYPE = "voucher"
/**
 * FIRST_EVENT_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val FIRST_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc301"
/**
 * SECOND_EVENT_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val SECOND_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc302"
/**
 * TARGET_POSITION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TARGET_POSITION = 2L
/**
 * NOW은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-23T13:00:00Z")
/**
 * STREAM_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val STREAM_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc300")
/**
 * TABLES은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val TABLES = arrayOf(
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


/**
 * leases은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val leases = ProjectionLeaseRepository()
/**
 * rebuilds은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val rebuilds = ProjectionRebuildRepository()
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
/**
 * events은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var events: EventStoreRepository

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-rebuild-worker")
        database = postgresDatabase.database
        events = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
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
 * 이 시나리오는 "bounded rebuild page updates only the candidate generation before activation" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `bounded rebuild page updates only the candidate generation before activation`() {
        appendEvents()
        val active = transaction(database) { rebuilds.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { rebuilds.start(PROJECTION, TARGET_POSITION, NOW) }

        val lease = transaction(database) {
            leases.acquire(PROJECTION, candidate.key.generation, OWNER, NOW)
        }.shouldNotBeNull()

        val projections = ProjectionRepository(leases)
        val worker = ProjectionRebuildWorker(rebuilds, projections, ExposedProjectionEventReader())

        val result = transaction(database) {
            worker.poll(candidate.key, lease, NOW).shouldBeInstanceOf<ProjectionRebuildPollResult.Applied>()
        }
        result.position shouldBeEqualTo TARGET_POSITION

        transaction(database) { findGeneration(candidate.key) }?.currentPosition shouldBeEqualTo TARGET_POSITION
        transaction(database) { projections.checkpoint(candidate.key) }?.position shouldBeEqualTo TARGET_POSITION
        transaction(database) { findActive(PROJECTION) }?.generation shouldBeEqualTo active.generation
    }

    @Test
/**
 * 이 시나리오는 "worker fenced by prior cancellation cannot write candidate projection state" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `worker fenced by prior cancellation cannot write candidate projection state`() {
        appendEvents()
        transaction(database) { rebuilds.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) {
            rebuilds.start(PROJECTION, TARGET_POSITION, NOW)
        }
        val lease = transaction(database) {
            leases.acquire(PROJECTION, candidate.key.generation, OWNER, NOW)
        }.shouldNotBeNull()
        transaction(database) { rebuilds.requestCancellation(candidate.key, NOW) }

        val projections = ProjectionRepository(leases)
        val worker = ProjectionRebuildWorker(rebuilds, projections, ExposedProjectionEventReader())

        transaction(database) {
            worker.poll(candidate.key, lease, NOW) shouldBeEqualTo ProjectionRebuildPollResult.StaleWorker
            projections.checkpoint(candidate.key).shouldBeNull()
            findGeneration(candidate.key)?.currentPosition shouldBeEqualTo 0L
        }
    }

/**
 * appendEvents은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun appendEvents() {
        transaction(database) {
            events.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = StreamKey(TenantId("tenant-a"), STREAM_TYPE, STREAM_ID),
                        expectedVersion = 0,
                        events = listOf(
                            eventToAppend(FIRST_EVENT_ID, "voucher.allocated"),
                            eventToAppend(SECOND_EVENT_ID, "voucher.redeemed"),
                        ),
                    ),
                ),
            )
        }
    }

/**
 * eventToAppend은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
}

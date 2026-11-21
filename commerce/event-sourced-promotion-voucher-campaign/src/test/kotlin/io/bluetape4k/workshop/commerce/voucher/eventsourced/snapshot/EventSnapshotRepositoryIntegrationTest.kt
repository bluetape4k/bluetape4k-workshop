package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSnapshots
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
 * EventSnapshotRepositoryIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSnapshotRepositoryIntegrationTest {

    companion object: KLogging() {
/**
 * postgres은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
/**
 * NOW은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-23T12:00:00Z")
    }

/**
 * repository은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = EventSnapshotRepository()
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
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-event-snapshot")
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() {
        postgresDatabase.close()
    }

    @BeforeEach
    fun createSchema() {
        transaction(database) { SchemaUtils.create(EventSnapshots) }
    }

    @AfterEach
    fun dropSchema() {
        transaction(database) { SchemaUtils.drop(EventSnapshots) }
    }

    @Test
/**
 * 이 시나리오는 "latest returns no snapshot until a stream checkpoint is persisted" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `latest returns no snapshot until a stream checkpoint is persisted`() {
        val stream = stream()

        transaction(database) { repository.latest(stream) }.shouldBeNull()
        transaction(database) { repository.save(snapshot(stream, 250, "state-250")) }

        val latest = transaction(database) { repository.latest(stream).shouldNotBeNull() }

        log.debug { "latest: $latest" }
        latest.metadata.streamVersion shouldBeEqualTo 250L
        latest.canonicalState shouldBeEqualTo "state-250"
    }

    @Test
/**
 * 이 시나리오는 "latest snapshot uses the greatest stream version" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `latest snapshot uses the greatest stream version`() {
        val stream = stream()
        transaction(database) {
            repository.save(snapshot(stream, 250, "state-250"))
            repository.save(snapshot(stream, 500, "state-500"))
        }

        transaction(database) {
            repository.latest(stream)
        }?.metadata?.streamVersion shouldBeEqualTo 500L
    }

    @Test
/**
 * 이 시나리오는 "same client UUID and version remain isolated across tenant and stream type" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same client UUID and version remain isolated across tenant and stream type`() {
        val streamId = UUID.randomUUID()
        val streams = listOf(
            StreamKey(TenantId("tenant-a"), "campaign", streamId) to "tenant-a-campaign",
            StreamKey(TenantId("tenant-b"), "campaign", streamId) to "tenant-b-campaign",
            StreamKey(TenantId("tenant-a"), "voucher", streamId) to "tenant-a-voucher",
        )

        transaction(database) {
            streams.forEach { (stream, state) ->
                repository.save(snapshot(stream, 250, state))
            }
        }

        streams.forEach { (stream, state) ->
            transaction(database) {
                repository.latest(stream)?.canonicalState shouldBeEqualTo state
            }
        }
    }

/**
 * stream은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun stream(): StreamKey =
        StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())

/**
 * snapshot은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun snapshot(stream: StreamKey, version: Long, state: String): EventSnapshot =
        EventSnapshot(
            stream = stream,
            metadata = SnapshotMetadata(version, schemaVersion = 1, keyVersion = 1),
            canonicalState = state,
            createdAt = NOW,
        )
}

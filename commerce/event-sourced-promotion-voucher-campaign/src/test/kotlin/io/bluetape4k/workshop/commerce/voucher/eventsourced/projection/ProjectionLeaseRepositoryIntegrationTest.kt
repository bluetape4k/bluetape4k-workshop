package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
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

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * ProjectionLeaseRepositoryIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ProjectionLeaseRepositoryIntegrationTest {

    companion object: KLogging() {
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
        private const val PROJECTION = "voucher"
/**
 * GENERATION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val GENERATION = 1L
/**
 * LEASE_TAKEOVER_SECONDS은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val LEASE_TAKEOVER_SECONDS = 16L
/**
 * RENEW_SECONDS은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val RENEW_SECONDS = 17L
/**
 * NOW은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-23T13:00:00Z")
    }

/**
 * leases은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val leases = ProjectionLeaseRepository()
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
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-projection-lease")
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() {
        postgresDatabase.close()
    }

    @BeforeEach
    fun createSchema() {
        transaction(database) { SchemaUtils.create(ProjectionLeases) }
    }

    @AfterEach
    fun dropSchema() {
        transaction(database) { SchemaUtils.drop(ProjectionLeases) }
    }

    @Test
/**
 * 이 시나리오는 "expired takeover increments fencing token and stale release cannot clear the new owner"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expired takeover increments fencing token and stale release cannot clear the new owner`() {
        val first = transaction(database) {
            leases.acquire(PROJECTION, GENERATION, "owner-a", NOW)
        }.shouldNotBeNull()

        val second = transaction(database) {
            leases.acquire(PROJECTION, GENERATION, "owner-b", NOW.plusSeconds(LEASE_TAKEOVER_SECONDS))
        }.shouldNotBeNull()

        // 검증
        second.fencingToken shouldBeGreaterThan first.fencingToken

        transaction(database) {
            leases.release(PROJECTION, GENERATION, first, NOW.plusSeconds(RENEW_SECONDS))
        }.shouldBeFalse()

        transaction(database) {
            leases.renew(PROJECTION, GENERATION, second, NOW.plusSeconds(RENEW_SECONDS))
        }.shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "renewal becomes due five seconds after a fifteen second lease was acquired" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `renewal becomes due five seconds after a fifteen second lease was acquired`() {
        val lease = transaction(database) {
            leases.acquire(PROJECTION, GENERATION, "owner-a", NOW)
        }.shouldNotBeNull()

        lease.isRenewalDue(NOW.plusSeconds(PROJECTION_LEASE_RENEW_SECONDS - 1)).shouldBeFalse()
        lease.isRenewalDue(NOW.plusSeconds(PROJECTION_LEASE_RENEW_SECONDS)).shouldBeTrue()
    }
}

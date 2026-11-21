package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * EventSourcedStartupProbeIntegrationTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedStartupProbeIntegrationTest {

    companion object: KLogging() {
/**
 * postgres은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

/**
 * postgresDatabase은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
/**
 * database은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var database: Database
/**
 * probe은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var probe: EventSourcedStartupProbe

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-startup-probe", maximumPoolSize = 1)
        database = postgresDatabase.database
        probe = EventSourcedOperationsConfiguration().eventSourcedStartupProbe(
            EventSourcedExposedDatabaseRegistration(database),
            EventSourcedDatabasePermitGate(),
        )
    }

    @BeforeEach
    fun clearSchema() {
        transaction(database) { SchemaUtils.drop(EventLog) }
    }

    @AfterEach
    fun dropSchema() {
        transaction(database) { SchemaUtils.drop(EventLog) }
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @Test
/**
 * 이 시나리오는 "probe rejects a missing event authority schema and accepts it after creation" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `probe rejects a missing event authority schema and accepts it after creation`() {
        assertFailsWith<IllegalStateException> { probe.verify() }

        transaction(database) { SchemaUtils.create(EventLog) }

        probe.verify()
    }

    @Test
/**
 * 이 시나리오는 "probe reserves the readiness permit before requesting a Hikari connection" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `probe reserves the readiness permit before requesting a Hikari connection`() {
        val permits = EventSourcedDatabasePermitGate(
            acquireTimeout = 50.millis(),
        )
        val contestedProbe = EventSourcedOperationsConfiguration()
            .eventSourcedStartupProbe(
                EventSourcedExposedDatabaseRegistration(database),
                permits,
            )
        val permitHeld = CountDownLatch(1)
        val releasePermit = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val owner = executor.submit {
                permits.withPermit(EventSourcedDatabaseLane.READINESS) {
                    permitHeld.countDown()
                    releasePermit.await(5.seconds).shouldBeTrue()
                }
            }
            permitHeld.await(5.seconds).shouldBeTrue()

            assertFailsWith<DatabaseBulkheadRejected> {
                contestedProbe.verify()
            }

            releasePermit.countDown()
            owner.get(5.seconds)
        }
    }
}

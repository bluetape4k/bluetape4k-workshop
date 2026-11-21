package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.time.Instant
import java.util.*

/**
 * MeteringSeed 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class MeteringSeed(
/**
 * tenantId은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val tenantId: String = "tenant-a",
/**
 * meterCode은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val meterCode: String = "api_calls",
/**
 * currency은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val currency: String = "USD",
/**
 * meterId은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val meterId: UUID = Uuid.V7.nextId(),
/**
 * calendarId은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val calendarId: UUID = Uuid.V7.nextId(),
)

/**
 * MeteringDatabaseFixture 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class MeteringDatabaseFixture: AutoCloseable {

    companion object: KLogging() {
/**
 * postgres은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }

/**
 * executor은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val executor: MeteringJdbcExecutor

    init {
/**
 * dataSource은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val dataSource = postgres.getHikariDataSource { }
        executor = MeteringJdbcExecutor(dataSource, foregroundPermits = 8)
        resetAndSeed()
    }

    @Suppress("DEPRECATION") // Test-only ephemeral schemas deliberately avoid production migration artifacts.
/**
 * resetAndSeed은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun resetAndSeed(seed: MeteringSeed = MeteringSeed()): MeteringSeed {

        executor.transaction {
            SchemaUtils.drop(*METERING_TABLES.reversedArray())
            SchemaUtils.createMissingTablesAndColumns(*METERING_TABLES)

            MeterEntity.new(seed.meterId) {
                tenantId = seed.tenantId
                meterCode = seed.meterCode
                unit = "request"
                description = "API calls accepted by the platform"
                active = true
                createdAt = Instant.parse("2026-07-01T00:00:00Z")
            }
            BillingCalendarEntity.new(seed.calendarId) {
                tenantId = seed.tenantId
                currency = seed.currency
                createdAt = Instant.parse("2026-07-01T00:00:00Z")
            }
        }
        return seed
    }

/**
 * close은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        executor.transaction {
            SchemaUtils.drop(*METERING_TABLES.reversedArray())
        }
        executor.close()
    }

}

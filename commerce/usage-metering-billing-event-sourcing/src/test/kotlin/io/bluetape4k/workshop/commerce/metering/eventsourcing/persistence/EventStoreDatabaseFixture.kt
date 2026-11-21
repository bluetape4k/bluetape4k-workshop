package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

/**
 * EventStoreDatabaseFixture 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventStoreDatabaseFixture: AutoCloseable {

    private companion object: KLogging() {
/**
 * postgres은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }

/**
 * executor은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val executor: MeteringEventsJdbcExecutor

    init {
/**
 * dataSource은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val dataSource = postgres.getHikariDataSource {}
        executor = MeteringEventsJdbcExecutor(dataSource)
        reset()
    }

    @Suppress("DEPRECATION") // Disposable Testcontainers schema; production never creates schema automatically.
/**
 * reset은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun reset() {
        executor.transaction {
            SchemaUtils.drop(*METERING_EVENT_TABLES.reversedArray())
            SchemaUtils.createMissingTablesAndColumns(*METERING_EVENT_TABLES)
        }
    }

/**
 * close은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        executor.transaction {
            SchemaUtils.drop(*METERING_EVENT_TABLES.reversedArray())
        }
        executor.close()
    }
}

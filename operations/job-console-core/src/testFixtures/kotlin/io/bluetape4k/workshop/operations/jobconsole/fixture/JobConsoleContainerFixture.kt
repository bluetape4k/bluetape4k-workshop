package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionJournal
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleDockerResources
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleProxiedTopology
import java.sql.DriverManager
import java.util.UUID

/**
 * JobConsoleContainerFixture 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class JobConsoleContainerFixture(
/**
 * jdbcUrl은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val jdbcUrl: String,
/**
 * databaseUsername은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val databaseUsername: String,
/**
 * databasePassword은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val databasePassword: String,
/**
 * redisUri은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val redisUri: String,
/**
 * schema은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val schema: String,
) {
/**
 * createSchema은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun createSchema() {
        DriverManager.getConnection(jdbcUrl, databaseUsername, databasePassword).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
    }

/**
 * dropSchema은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun dropSchema() {
        DriverManager.getConnection(jdbcUrl, databaseUsername, databasePassword).use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    companion object {
/**
 * proxiedRedis은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun proxiedRedis(
            journal: HighContentionJournal,
            resources: JobConsoleDockerResources,
        ): JobConsoleProxiedTopology =
            JobConsoleProxiedTopology.start(journal, resources)

/**
 * shared은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun shared(): JobConsoleContainerFixture {
            val postgres = PostgreSQLServer.Launcher.postgres
            val redis = RedisServer.Launcher.redis
            return JobConsoleContainerFixture(
                jdbcUrl = postgres.jdbcUrl,
                databaseUsername = postgres.username ?: PostgreSQLServer.USERNAME,
                databasePassword = postgres.password ?: PostgreSQLServer.PASSWORD,
                redisUri = "redis://${redis.host}:${redis.port}",
                schema = "job_console_${UUID.randomUUID().toString().replace("-", "")}",
            )
        }
    }
}

@file:Suppress("SqlSourceToSinkFlow")

package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.codec.Base58
import io.bluetape4k.jdbc.sql.singleLong
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * VoucherCompatibilityTestSupport 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal abstract class VoucherCompatibilityTestSupport {

    companion object: KLogging() {
/**
 * postgres은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        protected val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres
    }

/**
 * schema은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    protected lateinit var schema: String
/**
 * dataSource은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    protected lateinit var dataSource: DataSource

    @BeforeEach
    fun createCompatibilitySchema() {
        schema = "voucher_compat_${Base58.randomString(8).lowercase()}"
        adminConnection().use { connection ->
            connection.createStatement().use {
                it.execute("CREATE SCHEMA $schema")
            }
        }
        dataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username ?: PostgreSQLServer.USERNAME
            password = postgres.password ?: PostgreSQLServer.PASSWORD
            currentSchema = schema
        }
        applyPreviousSchema()
    }

    @AfterEach
    fun dropCompatibilitySchema() {
        adminConnection().use { connection ->
            connection.createStatement().use {
                it.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }

/**
 * migrationRunner은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun migrationRunner(): VoucherMigrationRunner =
        VoucherMigrationRunner(
            dataSource = dataSource,
            migration = VoucherMigration("001", ClassPathResource("db/migration/V001__voucher_campaign.sql")),
            advisoryLockKey = 534001L,
        )

/**
 * compatibilityJdbcUrl은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun compatibilityJdbcUrl(): String {
        val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
        return "${postgres.jdbcUrl}${separator}currentSchema=$schema"
    }

/**
 * queryLong은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun queryLong(sql: String): Long =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql.requireNotBlank("sql")).use { result ->
                    result.singleLong()
                }
            }
        }

/**
 * postgresUsername은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun postgresUsername(): String = postgres.username ?: PostgreSQLServer.USERNAME

/**
 * postgresPassword은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun postgresPassword(): String = postgres.password ?: PostgreSQLServer.PASSWORD

/**
 * applyPreviousSchema은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun applyPreviousSchema() {
        val sql = ClassPathResource("compatibility/V000__previous_voucher_schema.sql").inputStream.use {
            it.readAllBytes().toUtf8String()
        }
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                sql.split(';')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(stmt::execute)
            }
        }
    }

/**
 * adminConnection은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun adminConnection(): Connection =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )
}

package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import java.sql.DriverManager

/** application-context test를 Exposed `withTables` fixture에서 격리하는 PostgreSQL schema를 생성합니다. */
/** application-context test를 Exposed `withTables` fixture에서 격리하는 PostgreSQL schema를 생성합니다. */
internal object VoucherTestSchema {

/**
 * postgres은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres

/**
 * create은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun create(prefix: String): String {
        val schema = "${prefix}_${Base58.randomString(8).lowercase()}"
        DriverManager.getConnection(
            postgres.jdbcUrl,
            requireNotNull(postgres.username),
            requireNotNull(postgres.password),
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA $schema")
            }
        }
        val separator = if ('?' in postgres.jdbcUrl) '&' else '?'

        return "${postgres.jdbcUrl}${separator}currentSchema=$schema"
    }
}

package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import java.sql.DriverManager

/** Creates a PostgreSQL schema that isolates application-context tests from Exposed `withTables` fixtures. */
internal object VoucherTestSchema {
    private val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres

    fun create(prefix: String): String {
        val schema = "${prefix}_${Base58.randomString(8).lowercase()}"
        DriverManager.getConnection(
            postgres.jdbcUrl,
            requireNotNull(postgres.username),
            requireNotNull(postgres.password),
        ).use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") }
        }
        val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
        return "${postgres.jdbcUrl}${separator}currentSchema=$schema"
    }
}

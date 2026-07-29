package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import java.sql.DriverManager
import javax.sql.DataSource

internal abstract class VoucherCompatibilityTestSupport {
    protected lateinit var schema: String
    protected lateinit var dataSource: DataSource

    @BeforeEach
    fun createCompatibilitySchema() {
        schema = "voucher_compat_${Base58.randomString(8).lowercase()}"
        adminConnection().use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        dataSource =
            PGSimpleDataSource().apply {
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
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    protected fun migrationRunner(): VoucherMigrationRunner =
        VoucherMigrationRunner(
            dataSource = dataSource,
            migration = VoucherMigration("001", ClassPathResource("db/migration/V001__voucher_campaign.sql")),
            advisoryLockKey = 534001L,
        )

    protected fun compatibilityJdbcUrl(): String {
        val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
        return "${postgres.jdbcUrl}${separator}currentSchema=$schema"
    }

    protected fun queryLong(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }

    protected fun postgresUsername(): String = postgres.username ?: PostgreSQLServer.USERNAME

    protected fun postgresPassword(): String = postgres.password ?: PostgreSQLServer.PASSWORD

    private fun applyPreviousSchema() {
        val sql = ClassPathResource("compatibility/V000__previous_voucher_schema.sql").inputStream.use {
            it.readAllBytes().toString(Charsets.UTF_8)
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
            }
        }
    }

    private fun adminConnection() =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )

    companion object {
        protected val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres
    }
}

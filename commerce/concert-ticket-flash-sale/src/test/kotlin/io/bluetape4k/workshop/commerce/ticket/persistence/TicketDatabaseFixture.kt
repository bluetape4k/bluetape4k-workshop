package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.ticket.config.TicketMigration
import io.bluetape4k.workshop.commerce.ticket.config.TicketMigrationRunner
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource

internal class TicketDatabaseFixture : AutoCloseable {
    private val schema = "ticket_repository_${Base58.randomString(8).lowercase()}"
    val dataSource: DataSource
    val executor: TicketJdbcExecutor

    init {
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
        TicketMigrationRunner(
            dataSource = dataSource,
            migration = TicketMigration("001", ClassPathResource("db/migration/V001__concert_ticket_flash_sale.sql")),
            advisoryLockKey = 521_101L,
        ).migrate()
        executor = TicketJdbcExecutor(dataSource, foregroundPermits = 2)
    }

    fun seedAuthority(
        saleId: UUID,
        userSubjectId: UUID,
        ipSubjectId: UUID,
        firstAttemptId: UUID,
        secondAttemptId: UUID,
    ) {
        execute(
            """
            INSERT INTO ticket_sales(sale_id, state, current_policy_version, opens_at, closes_at)
            VALUES ('$saleId', 'open', 1, CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP + INTERVAL '1 hour');
            INSERT INTO ticket_sale_policy_versions(sale_id, policy_version, per_user_limit, max_quantity, hold_seconds)
            VALUES ('$saleId', 1, 1, 4, 30);
            INSERT INTO ticket_inventory(sale_id, grade, total_quantity) VALUES ('$saleId', 'GENERAL', 10);
            INSERT INTO ticket_identity_subjects(subject_id, identity_kind) VALUES
                ('$userSubjectId', 'USER'), ('$ipSubjectId', 'IP');
            INSERT INTO ticket_purchase_attempts(
                attempt_id, sale_id, user_subject_id, ip_subject_id, grade, quantity, policy_version,
                state, hold_deadline, authorization_operation_id
            ) VALUES
                ('$firstAttemptId', '$saleId', '$userSubjectId', '$ipSubjectId', 'GENERAL', 1, 1,
                 'inventory_held', CURRENT_TIMESTAMP + INTERVAL '30 seconds', '${UUID.randomUUID()}'),
                ('$secondAttemptId', '$saleId', '$userSubjectId', '$ipSubjectId', 'GENERAL', 1, 1,
                 'inventory_held', CURRENT_TIMESTAMP + INTERVAL '30 seconds', '${UUID.randomUUID()}');
            """.trimIndent(),
        )
    }

    fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    override fun close() {
        adminConnection().use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    private fun adminConnection() =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )

    companion object {
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }
}

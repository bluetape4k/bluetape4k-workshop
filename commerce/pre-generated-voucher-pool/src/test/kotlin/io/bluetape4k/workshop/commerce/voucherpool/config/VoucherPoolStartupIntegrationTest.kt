package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKeyMaterialUnavailableException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.UUID

@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolStartupIntegrationTest {
    private lateinit var schema: String

    @BeforeEach
    fun createSchema() {
        schema = "voucher_startup_${Base58.randomString(8).lowercase()}"
        postgres.createSchema(schema)
    }

    @AfterEach
    fun dropSchema() {
        postgres.dropSchema(schema)
    }

    @Test
    fun `startup fails closed when the applied migration checksum drifts`() {
        val dataSource = postgresDataSource(schema)
        migrationRunner(dataSource).migrate()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE voucher_pool_schema_history SET checksum='drift' WHERE version='001'",
            ).use { it.executeUpdate() }
        }

        val failure = assertFailsWith<VoucherPoolMigrationException> {
            startupInitializer().afterSingletonsInstantiated()
        }

        failure.code shouldBeEqualTo VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT
    }

    @Test
    fun `startup fails closed when a live row references unavailable key material`() {
        val dataSource = postgresDataSource(schema)
        migrationRunner(dataSource).migrate()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO voucher_pool_campaigns
                    (tenant_id,campaign_id,state,user_identity_key_version,policy_version)
                    VALUES ('startup-missing-key',?,'ACTIVE',99,1)""".trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.executeUpdate()
            }
        }

        assertFailsWith<VoucherKeyMaterialUnavailableException> {
            startupInitializer().afterSingletonsInstantiated()
        }
    }

    private fun startupInitializer(): VoucherPoolStartupInitializer {
        val dataSource = postgresDataSource(schema)
        val keys = VoucherPoolTestKeyMaterialConfiguration().voucherPoolTestKeyMaterialProvider().load()
        return VoucherPoolStartupInitializer(
            migration = migrationRunner(dataSource),
            keyPreflight = VoucherPoolReferencedKeyPreflight(dataSource, keys.digests, keys.kekRing),
            health = VoucherPoolHealthState(),
        )
    }

    private companion object {
        val postgres: PostgreSQLServer = VOUCHER_POOL_TASK_12_POSTGRES
    }
}

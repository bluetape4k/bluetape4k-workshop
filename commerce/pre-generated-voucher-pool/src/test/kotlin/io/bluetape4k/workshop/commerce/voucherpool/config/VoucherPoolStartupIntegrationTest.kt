@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKeyMaterialUnavailableException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.*

@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolStartupIntegrationTest {

    private companion object: KLogging() {
        val postgres: PostgreSQLServer by lazy { VOUCHER_POOL_TASK_12_POSTGRES }
    }

    private lateinit var voucherSchema: String

    @BeforeEach
    fun createSchema() {
        voucherSchema = "voucher_startup_${Base58.randomString(8).lowercase()}"
        postgres.createSchema(voucherSchema)
    }

    @AfterEach
    fun dropSchema() {
        postgres.dropSchema(voucherSchema)
    }

    @Test
    fun `startup fails closed when the applied migration checksum drifts`() {
        val dataSource = postgresDataSource(voucherSchema)
        migrationRunner(dataSource).migrate()
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE voucher_pool_schema_history SET checksum='drift' WHERE version='001'")
                .use { it.executeUpdate() }
        }

        val failure = assertFailsWith<VoucherPoolMigrationException> {
            startupInitializer().afterSingletonsInstantiated()
        }

        log.debug { "failure: $failure" }
        failure.code shouldBeEqualTo VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT
    }

    @Test
    fun `startup fails closed when a live row references unavailable key material`() {
        postgresDataSource(voucherSchema).use { dataSource ->
            migrationRunner(dataSource).migrate()
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO voucher_pool_campaigns
                    (tenant_id,campaign_id,state,user_identity_key_version,policy_version)
                    VALUES ('startup-missing-key',?,'ACTIVE',99,1)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, UUID.randomUUID())
                    stmt.executeUpdate()
                }
            }
        }
        assertFailsWith<VoucherKeyMaterialUnavailableException> {
            startupInitializer().afterSingletonsInstantiated()
        }
    }

    private fun startupInitializer(): VoucherPoolStartupInitializer {
        val dataSource = postgresDataSource(voucherSchema)
        val keys = VoucherPoolTestKeyMaterialConfiguration().voucherPoolTestKeyMaterialProvider().load()

        return VoucherPoolStartupInitializer(
            migration = migrationRunner(dataSource),
            keyPreflight = VoucherPoolReferencedKeyPreflight(dataSource, keys.digests, keys.kekRing),
            health = VoucherPoolHealthState(),
        )
    }
}

package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMigration
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMigrationRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.DriverManagerDataSource

/** migration compatibility verification task에서만 사용하는 격리된 packaged-artifact entry point입니다. */
internal object VoucherCompatibilityCli : KLogging() {
    private const val MODE_PREFIX = "--voucher-compatibility-mode="

    fun supports(args: Array<String>): Boolean = args.any { it.startsWith(MODE_PREFIX) }

    fun run(args: Array<String>) {
        val values = args.associate { argument ->
            val separator = argument.indexOf('=')
            require(argument.startsWith("--") && separator > 2) { "invalid compatibility argument" }
            argument.substring(2, separator) to argument.substring(separator + 1)
        }
        require(values.getValue("voucher-compatibility-mode") == "migrate-read-write")
        val dataSource =
            DriverManagerDataSource(
                values.getValue("voucher-database-url"),
                values.getValue("voucher-database-username"),
                values.getValue("voucher-database-password"),
            )
        VoucherMigrationRunner(
            dataSource = dataSource,
            migration = VoucherMigration("001", ClassPathResource("db/migration/V001__voucher_campaign.sql")),
            advisoryLockKey = 534001L,
        ).migrate()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO voucher_campaigns (
                    tenant_id, campaign_id, state, starts_at, ends_at, capacity, allocated_count,
                    per_user_limit, redemption_ttl_seconds, policy_version, revision
                ) VALUES (?, ?::uuid, 'ACTIVE', CURRENT_TIMESTAMP - INTERVAL '1 minute',
                    CURRENT_TIMESTAMP + INTERVAL '1 hour', 10, 0, 1, 3600, 1, 0)
                ON CONFLICT (tenant_id, campaign_id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, values.getValue("voucher-tenant"))
                statement.setString(2, "018f1f2e-3d4c-7b6a-8f90-1234567890ab")
                statement.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM voucher_campaigns").use { result ->
                    check(result.next() && result.getLong(1) >= 2) { "compatibility rows are missing" }
                }
            }
        }
        log.info { "voucher_compatibility_current_completed" }
    }
}

package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

internal class PreviousSchemaCompatibilityTest : VoucherCompatibilityTestSupport() {
    @Test
    fun `previous schema upgrades without losing a readable campaign row`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO voucher_campaigns (
                        tenant_id, campaign_id, state, starts_at, ends_at, capacity, allocated_count,
                        per_user_limit, redemption_ttl_seconds, policy_version, revision
                    ) VALUES ('tenant-compat', '018f1f2e-3d4c-7b6a-8f90-1234567890aa', 'ACTIVE',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        10, 0, 1, 3600, 1, 0)
                    """.trimIndent(),
                )
            }
        }

        migrationRunner().migrate() shouldBeEqualTo VoucherMigrationResult.APPLIED

        queryLong("SELECT count(*) FROM voucher_campaigns WHERE tenant_id = 'tenant-compat'") shouldBeEqualTo 1L
        queryLong("SELECT count(*) FROM information_schema.tables WHERE table_schema = '$schema'") shouldBeEqualTo 7L
    }
}

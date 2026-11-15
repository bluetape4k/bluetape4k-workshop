@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jdbc.sql.singleLong
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant

internal class VoucherKeyRotationIntegrationTest: VoucherCompatibilityTestSupport() {

    companion object: KLogging()

    @Test
    fun `persisted claim key versions prevent premature retirement`() {
        migrationRunner().migrate()
        val campaignId = Uuid.V7.nextId()
        val claimId = Uuid.V7.nextId()

        dataSource.connection.use { conn ->
            val campaignRowId =
                conn.prepareStatement(
                    """
                    INSERT INTO voucher_campaigns (
                        tenant_id, campaign_id, state, starts_at, ends_at, capacity, allocated_count,
                        per_user_limit, redemption_ttl_seconds, policy_version, revision
                    ) VALUES (?, ?, 'ACTIVE', ?, ?, 10, 1, 1, 3600, 1, 1)
                    RETURNING id
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, "tenant")
                    stmt.setObject(2, campaignId)
                    stmt.setTimestamp(3, Timestamp.from(Instant.parse("2026-07-19T00:00:00Z")))
                    stmt.setTimestamp(4, Timestamp.from(Instant.parse("2026-07-21T00:00:00Z")))
                    stmt.executeQuery().use { rows ->
                        rows.singleLong()
                    }
                }
            conn.prepareStatement(
                """
                INSERT INTO voucher_claims (
                    tenant_id, campaign_row_id, campaign_id, claim_id, allocation_id, user_digest,
                    state, capacity_reserved, allocation_policy_version, code_verifier,
                    generation_key_version, verification_key_version, expires_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, 'ALLOCATED', true, 1, ?, 2, 3, ?, 0)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, "tenant")
                stmt.setLong(2, campaignRowId)
                stmt.setObject(3, campaignId)
                stmt.setObject(4, claimId)
                stmt.setObject(5, Uuid.V7.nextId())
                stmt.setString(6, "a".repeat(64))
                stmt.setBytes(7, ByteArray(32) { 7 })
                stmt.setTimestamp(8, Timestamp.from(Instant.parse("2026-07-20T01:00:00Z")))
                stmt.executeUpdate()
            }
        }

        val source = PostgresReferencedKeyVersionSource(dataSource)
        source.referencedVersions() shouldBeEqualTo ReferencedKeyVersions(setOf(2), setOf(3))
        VoucherKeyRotationPolicy(source).canRetire(2).shouldBeFalse()
        VoucherKeyRotationPolicy(source).canRetire(4).shouldBeTrue()
    }
}

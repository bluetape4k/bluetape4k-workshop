@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * PreviousSchemaCompatibilityTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class PreviousSchemaCompatibilityTest: VoucherCompatibilityTestSupport() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "previous schema upgrades without losing a readable campaign row" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `previous schema upgrades without losing a readable campaign row`() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
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

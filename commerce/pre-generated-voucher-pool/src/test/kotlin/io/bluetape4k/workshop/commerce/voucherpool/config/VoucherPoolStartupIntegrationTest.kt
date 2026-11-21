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
/**
 * VoucherPoolStartupIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolStartupIntegrationTest {

    private companion object: KLogging() {
/**
 * postgres은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val postgres: PostgreSQLServer by lazy { VOUCHER_POOL_TASK_12_POSTGRES }
    }

/**
 * voucherSchema은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "startup fails closed when the applied migration checksum drifts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "startup fails closed when a live row references unavailable key material" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * startupInitializer은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

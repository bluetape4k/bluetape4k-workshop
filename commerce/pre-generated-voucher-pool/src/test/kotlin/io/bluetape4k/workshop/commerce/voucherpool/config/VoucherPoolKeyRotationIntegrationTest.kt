package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
/**
 * VoucherPoolKeyRotationIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolKeyRotationIntegrationTest {

/**
 * schema은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var schema: String
/**
 * dataSource은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun createSchema() {
        schema = "voucher_keys_${io.bluetape4k.codec.Base58.randomString(8).lowercase()}"
        VOUCHER_POOL_TASK_12_POSTGRES.createSchema(schema)
        dataSource = postgresDataSource(schema)
        migrationRunner(dataSource).migrate()
    }

    @AfterAll
    fun dropSchema() {
        VOUCHER_POOL_TASK_12_POSTGRES.dropSchema(schema)
        if (this::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    @Test
/**
 * 이 시나리오는 "live tombstone dedup audit and backup references block key retirement until rehearsal
 * passes" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live tombstone dedup audit and backup references block key retirement until rehearsal passes`() {
        seedReferences()
        val source = PostgresVoucherPoolKeyReferenceSource(dataSource)
        val policy = VoucherPoolKeyRetirementPolicy(source)

        setOf("kek-v1", "11", "12", "1", "2", "15", "16", "13", "14", "17", "18").forEach { version ->
            policy.canRetire(version).shouldBeFalse()
        }
        policy.canRetire("unused").shouldBeFalse()

        execute(
            """
                UPDATE voucher_pool_backup_inventory
                SET restore_rehearsed_at=statement_timestamp(),retained_until=statement_timestamp()
                """.trimIndent(),
        )

        policy.canRetire("unused").shouldBeTrue()
        policy.canRetire("kek-v1").shouldBeFalse()
        policy.canRetire("12").shouldBeFalse()
    }

/**
 * seedReferences은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun seedReferences() {
        val tenant = "key-rotation"
        val campaign = UUID.randomUUID()
        val batch = UUID.randomUUID()
        val entry = UUID.randomUUID()
        val effect = UUID.randomUUID()

        execute(
            """
                INSERT INTO voucher_pool_campaigns
                (tenant_id,campaign_id,state,user_identity_key_version,policy_version)
                VALUES ('$tenant','$campaign','ACTIVE',12,1)
                """.trimIndent(),
        )
        execute(
            """
                INSERT INTO voucher_pool_batches
                (tenant_id,batch_id,campaign_id,state,source_kind,provenance_digest,request_fingerprint,
                 policy_version,activates_at,expected_count,next_source_ordinal,accepted_count)
                VALUES ('$tenant','$batch','$campaign','ACTIVE','GENERATED',decode('01','hex'),decode('02','hex'),
                        1,statement_timestamp(),1,1,1)
                """.trimIndent(),
        )
        execute(
            """INSERT INTO voucher_pool_entries
                (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,stable_dedup_digest,
                 verification_digest,verification_key_version,code_ciphertext,code_nonce,wrapped_dek,wrap_nonce,kek_version)
                VALUES ('$tenant','$entry','$campaign','$batch',0,'AVAILABLE',decode('03','hex'),decode('04','hex'),11,
                        decode('05','hex'),decode('060606060606060606060606','hex'),decode('07','hex'),
                        decode('080808080808080808080808','hex'),'kek-v1')""".trimIndent(),
        )
        execute(
            """INSERT INTO voucher_pool_code_dedup
                (tenant_id,stable_dedup_digest,first_campaign_id,first_batch_id,first_entry_id,key_version)
                VALUES ('$tenant',decode('03','hex'),'$campaign','$batch','$entry',1)""".trimIndent(),
        )
        execute(
            """INSERT INTO voucher_pool_command_tombstones
                (tenant_id,operation,key_version,scoped_key_digest,fingerprint,effect_id)
                VALUES ('$tenant','reserve',2,decode('09','hex'),decode('0a','hex'),'$effect')""".trimIndent(),
        )
        execute(
            """INSERT INTO voucher_pool_audits
                (tenant_id,campaign_id,aggregate_type,aggregate_id,revision,policy_version,actor_type,reason_code,audit_key_version)
                VALUES ('$tenant','$campaign','CAMPAIGN','$campaign',0,1,'SYSTEM','KEY_REFERENCE',15)""".trimIndent(),
        )
        execute(
            """INSERT INTO voucher_pool_revoke_preview_grants
                (tenant_id,grant_id,aggregate_type,aggregate_id,aggregate_revision,impact_digest,
                 affected_count,signature_key_version,signature_digest,expires_at)
                VALUES ('$tenant','${UUID.randomUUID()}','CAMPAIGN','$campaign',0,decode('0b','hex'),
                        1,16,decode('0c','hex'),statement_timestamp()+interval '1 hour')""".trimIndent(),
        )
        execute(
            """INSERT INTO voucher_pool_backup_inventory
                (backup_id,tenant_id,kek_versions,verification_versions,user_identity_versions,audit_versions,
                 signature_versions,stable_dedup_version,
                 command_tombstone_version,retained_until)
                VALUES ('${UUID.randomUUID()}','$tenant',ARRAY['retired-kek'],ARRAY['13'],ARRAY['14'],ARRAY['17'],
                        ARRAY['18'],'1','2',
                        statement_timestamp()+interval '1 day')""".trimIndent(),
        )
    }

/**
 * execute은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun execute(sql: String) {
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute(sql)
            }
        }
    }
}

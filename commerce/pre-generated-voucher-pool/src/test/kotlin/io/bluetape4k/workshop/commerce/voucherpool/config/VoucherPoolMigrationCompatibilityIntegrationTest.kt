@file:Suppress("MagicNumber", "MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.postgresql.Driver
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import java.io.File
import java.security.MessageDigest

@Suppress("SqlResolve")
@Tag("migration-compatibility")
@Execution(ExecutionMode.SAME_THREAD)
/**
 * VoucherPoolMigrationCompatibilityIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolMigrationCompatibilityIntegrationTest {

    companion object: KLogging()

/**
 * schemas은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val schemas = mutableListOf<String>()

    @AfterEach
    fun dropSchemas() {
        schemas.forEach(VOUCHER_POOL_TASK_12_POSTGRES::dropSchema)
        schemas.clear()
    }

    @Test
/**
 * 이 시나리오는 "migration supports clean and warm schema and fails closed on checksum drift" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `migration supports clean and warm schema and fails closed on checksum drift`() {
        val schema = newSchema("clean_warm")
        postgresDataSource(schema).use { dataSource ->
            val migration = migrationRunner(dataSource)

            migration.migrate() shouldBeEqualTo VoucherPoolMigrationResult.APPLIED
            migration.migrate() shouldBeEqualTo VoucherPoolMigrationResult.ALREADY_APPLIED

            val drifted = VoucherPoolMigrationRunner(
                dataSource,
                VoucherPoolMigration("001", ByteArrayResource("SELECT 1".toUtf8Bytes())),
                537_012L,
                5.seconds(),
            )
            val failure = assertFailsWith<VoucherPoolMigrationException> { drifted.migrate() }
            failure.code shouldBeEqualTo VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT
        }
    }

    @Test
/**
 * 이 시나리오는 "pinned previous binary runs before and after current schema expansion" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `pinned previous binary runs before and after current schema expansion`() {
        val schema = newSchema("previous")
        postgresDataSource(schema).use { dataSource ->
            executeSqlScript(
                dataSource.connection,
                ClassPathResource("compatibility/V000__previous_voucher_pool_schema.sql")
            )
            val binary = previousBinary()

            runPrevious(binary, schema) shouldBeEqualTo "previous-revision=0"
            migrationRunner(dataSource).migrate() shouldBeEqualTo VoucherPoolMigrationResult.APPLIED
            dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.executeUpdate(
                        """
                             UPDATE voucher_pool_campaigns 
                             SET state='PAUSED',revision=revision+1
                             WHERE tenant_id='compat-v000' AND campaign_id='00000000-0000-0000-0000-000000000537'
                             """.trimIndent(),
                    ) shouldBeEqualTo 1
                }
            }
            runPrevious(binary, schema) shouldBeEqualTo "previous-revision=2"

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                             SELECT state,revision,user_identity_key_version,to_regclass('voucher_pool_backup_inventory') IS NOT NULL AS expanded
                             FROM voucher_pool_campaigns
                             WHERE tenant_id='compat-v000' AND campaign_id='00000000-0000-0000-0000-000000000537'
                             """.trimIndent(),
                    ).use { result ->
                        check(result.next())
                        result.getString("state") shouldBeEqualTo "PAUSED"
                        result.getLong("revision") shouldBeEqualTo 2L
                        result.getInt("user_identity_key_version") shouldBeEqualTo 3
                        result.getBoolean("expanded").shouldBeTrue()
                    }
                }
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "previous binary jar matches pinned sha256" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `previous binary jar matches pinned sha256`() {
        val binary = previousBinary()
        val expected = ClassPathResource("compatibility/previous-binary.sha256").inputStream
            .bufferedReader()
            .use {
                it.readLine().substringBefore(' ')
            }
        val actual = MessageDigest.getInstance("SHA-256").digest(binary.readBytes()).toHexString()

        actual shouldBeEqualTo expected
    }

/**
 * newSchema은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun newSchema(suffix: String): String =
        "voucher_compat_${suffix}_${Base58.randomString(8).lowercase()}".also {
            VOUCHER_POOL_TASK_12_POSTGRES.createSchema(it)
            schemas += it
        }

/**
 * previousBinary은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun previousBinary(): File =
        File(checkNotNull(System.getProperty("voucherPool.previousBinary")) { "previous binary path is missing" })
            .also { check(it.isFile && it.length() > 0L) { "previous binary jar is missing" } }

/**
 * runPrevious은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun runPrevious(binary: File, schema: String): String {
        val separator = if ('?' in VOUCHER_POOL_TASK_12_POSTGRES.jdbcUrl) '&' else '?'
        val jdbcUrl = "${VOUCHER_POOL_TASK_12_POSTGRES.jdbcUrl}${separator}currentSchema=$schema"
        val driverJar = File(Driver::class.java.protectionDomain.codeSource.location.toURI())
        val process = ProcessBuilder(
            javaExecutable(),
            "-cp",
            listOf(binary, driverJar).joinToString(File.pathSeparator, transform = File::getAbsolutePath),
            "io.bluetape4k.workshop.commerce.voucherpool.compatibility.PreviousVoucherPoolBinaryMain",
            jdbcUrl,
            VOUCHER_POOL_TASK_12_POSTGRES.username,
            VOUCHER_POOL_TASK_12_POSTGRES.password,
        )
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(20.seconds())) {
            process.destroyForcibly()
            check(process.waitFor(5.seconds())) { "previous binary did not stop after timeout" }
            error("previous binary timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.exitValue() == 0) { "previous binary failed: ${output.take(1_024)}" }
        return output
    }

/**
 * javaExecutable은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun javaExecutable(): String =
        File(System.getProperty("java.home"), "bin/java").absolutePath
}

/**
 * executeSqlScript은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun executeSqlScript(connection: java.sql.Connection, resource: ClassPathResource) {
    connection.use { conn ->
        conn.createStatement().use { statement ->
            resource.inputStream.bufferedReader().use { it.readText() }
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach(statement::execute)
        }
    }
}

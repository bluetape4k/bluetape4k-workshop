@file:Suppress("MagicNumber", "MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
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
import java.time.Duration
import java.util.concurrent.TimeUnit

@Tag("migration-compatibility")
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolMigrationCompatibilityIntegrationTest {
    private val schemas = mutableListOf<String>()

    @AfterEach
    fun dropSchemas() {
        schemas.forEach(VOUCHER_POOL_TASK_12_POSTGRES::dropSchema)
        schemas.clear()
    }

    @Test
    fun `migration supports clean and warm schema and fails closed on checksum drift`() {
        val schema = newSchema("clean_warm")
        val dataSource = postgresDataSource(schema)
        val migration = migrationRunner(dataSource)

        migration.migrate() shouldBeEqualTo VoucherPoolMigrationResult.APPLIED
        migration.migrate() shouldBeEqualTo VoucherPoolMigrationResult.ALREADY_APPLIED

        val drifted = VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ByteArrayResource("SELECT 1".toByteArray())),
            537_012L,
            Duration.ofSeconds(5),
        )
        val failure = assertFailsWith<VoucherPoolMigrationException> { drifted.migrate() }
        failure.code shouldBeEqualTo VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT
    }

    @Test
    fun `pinned previous binary runs before and after current schema expansion`() {
        val schema = newSchema("previous")
        val dataSource = postgresDataSource(schema)
        executeSqlScript(dataSource.connection, ClassPathResource("compatibility/V000__previous_voucher_pool_schema.sql"))
        val binary = previousBinary()

        runPrevious(binary, schema) shouldBeEqualTo "previous-revision=0"
        migrationRunner(dataSource).migrate() shouldBeEqualTo VoucherPoolMigrationResult.APPLIED
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    """UPDATE voucher_pool_campaigns SET state='PAUSED',revision=revision+1
                        WHERE tenant_id='compat-v000' AND campaign_id='00000000-0000-0000-0000-000000000537'""",
                ) shouldBeEqualTo 1
            }
        }
        runPrevious(binary, schema) shouldBeEqualTo "previous-revision=2"

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """SELECT state,revision,user_identity_key_version,
                               to_regclass('voucher_pool_backup_inventory') IS NOT NULL AS expanded
                        FROM voucher_pool_campaigns
                        WHERE tenant_id='compat-v000' AND campaign_id='00000000-0000-0000-0000-000000000537'""",
                ).use { result ->
                    check(result.next())
                    result.getString("state") shouldBeEqualTo "PAUSED"
                    result.getLong("revision") shouldBeEqualTo 2L
                    result.getInt("user_identity_key_version") shouldBeEqualTo 3
                    result.getBoolean("expanded") shouldBeEqualTo true
                }
            }
        }
    }

    @Test
    fun `previous binary jar matches pinned sha256`() {
        val binary = previousBinary()
        val expected = ClassPathResource("compatibility/previous-binary.sha256").inputStream
            .bufferedReader()
            .use { it.readLine().substringBefore(' ') }
        val actual = MessageDigest.getInstance("SHA-256").digest(binary.readBytes()).toHexString()

        actual shouldBeEqualTo expected
    }

    private fun newSchema(suffix: String): String =
        "voucher_compat_${suffix}_${Base58.randomString(8).lowercase()}".also {
            VOUCHER_POOL_TASK_12_POSTGRES.createSchema(it)
            schemas += it
        }

    private fun previousBinary(): File =
        File(checkNotNull(System.getProperty("voucherPool.previousBinary")) { "previous binary path is missing" })
            .also { check(it.isFile && it.length() > 0L) { "previous binary jar is missing" } }

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
        ).redirectErrorStream(true).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "previous binary did not stop after timeout" }
            error("previous binary timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.exitValue() == 0) { "previous binary failed: ${output.take(1_024)}" }
        return output
    }

    private fun javaExecutable(): String =
        File(System.getProperty("java.home"), "bin/java").absolutePath
}

private fun executeSqlScript(connection: java.sql.Connection, resource: ClassPathResource) {
    connection.use { current ->
        current.createStatement().use { statement ->
            resource.inputStream.bufferedReader().use { it.readText() }
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach(statement::execute)
        }
    }
}

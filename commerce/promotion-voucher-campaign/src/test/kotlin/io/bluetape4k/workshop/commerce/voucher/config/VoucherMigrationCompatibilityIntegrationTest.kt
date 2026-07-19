package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

@Tag("migration-compatibility")
internal class VoucherMigrationCompatibilityIntegrationTest : VoucherCompatibilityTestSupport() {
    @Test
    fun `previous current previous packaged process sequence remains read write compatible`() {
        val previousJar = Path.of(requiredProperty("voucher.compatibility.previous-jar"))
        val currentBootJar = Path.of(requiredProperty("voucher.compatibility.current-boot-jar"))
        val postgresqlDriver = Path.of(requiredProperty("voucher.compatibility.postgresql-driver"))
        val expectedChecksum =
            ClassPathResource("compatibility/previous-binary.sha256")
                .inputStream
                .bufferedReader()
                .use { it.readText().trim() }

        sha256(previousJar) shouldBeEqualTo expectedChecksum
        runPrevious(previousJar, postgresqlDriver, "write", "018f1f2e-3d4c-7b6a-8f90-1234567890aa")
        runCurrent(currentBootJar)
        runPrevious(previousJar, postgresqlDriver, "read-write", "018f1f2e-3d4c-7b6a-8f90-1234567890ac")

        queryLong("SELECT count(*) FROM voucher_campaigns WHERE tenant_id = 'tenant-compat'") shouldBeEqualTo 3L
        queryLong("SELECT count(*) FROM voucher_schema_history") shouldBeEqualTo 1L
    }

    private fun runPrevious(
        previousJar: Path,
        postgresqlDriver: Path,
        mode: String,
        campaignId: String,
    ) {
        runProcess(
            javaExecutable(),
            "-cp",
            "$previousJar${System.getProperty("path.separator")}$postgresqlDriver",
            "io.bluetape4k.workshop.commerce.voucher.compatibility.PreviousVoucherBinaryMain",
            mode,
            compatibilityJdbcUrl(),
            postgresUsername(),
            postgresPassword(),
            "tenant-compat",
            campaignId,
        )
    }

    private fun runCurrent(currentBootJar: Path) {
        runProcess(
            javaExecutable(),
            "-jar",
            currentBootJar.toString(),
            "--voucher-compatibility-mode=migrate-read-write",
            "--voucher-database-url=${compatibilityJdbcUrl()}",
            "--voucher-database-username=${postgresUsername()}",
            "--voucher-database-password=${postgresPassword()}",
            "--voucher-tenant=tenant-compat",
        )
    }

    private fun runProcess(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val completed = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(completed) {
            process.destroyForcibly()
            "compatibility process timed out"
        }
        check(process.exitValue() == 0) {
            "compatibility process failed exit=${process.exitValue()} output=${output.take(2_000)}"
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toHexString()

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "missing system property $name" }

    private fun javaExecutable(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    companion object {
        private val PROCESS_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

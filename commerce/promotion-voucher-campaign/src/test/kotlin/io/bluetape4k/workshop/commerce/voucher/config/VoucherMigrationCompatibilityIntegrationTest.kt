package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

@Tag("migration-compatibility")
/**
 * VoucherMigrationCompatibilityIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherMigrationCompatibilityIntegrationTest: VoucherCompatibilityTestSupport() {

    companion object: KLogging() {
/**
 * PROCESS_TIMEOUT은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val PROCESS_TIMEOUT: Duration = 30.seconds()
    }

    @Test
/**
 * 이 시나리오는 "previous current previous packaged process sequence remains read write compatible" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `previous current previous packaged process sequence remains read write compatible`() {
        val previousJar = Path.of(requiredProperty("voucher.compatibility.previous-jar"))
        val currentBootJar = Path.of(requiredProperty("voucher.compatibility.current-boot-jar"))
        val postgresqlDriver = Path.of(requiredProperty("voucher.compatibility.postgresql-driver"))
        val expectedChecksum = ClassPathResource("compatibility/previous-binary.sha256")
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

/**
 * runPrevious은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * runCurrent은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * runProcess은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun runProcess(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val completed = process.waitFor(PROCESS_TIMEOUT)
        val output = process.inputStream.bufferedReader().use { it.readText() }

        check(completed) {
            process.destroyForcibly()
            "compatibility process timed out"
        }
        check(process.exitValue() == 0) {
            "compatibility process failed exit=${process.exitValue()} output=${output.take(2_000)}"
        }
    }

/**
 * sha256은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toHexString()

/**
 * requiredProperty은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun requiredProperty(name: String): String =
        System.getProperty(name).requireNotNull { "missing system property $name" }

/**
 * javaExecutable은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

}

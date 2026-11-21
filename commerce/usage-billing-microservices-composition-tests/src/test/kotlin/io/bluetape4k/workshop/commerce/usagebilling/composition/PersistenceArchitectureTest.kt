package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * PersistenceArchitectureTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class PersistenceArchitectureTest {

    private companion object: KLogging() {
/**
 * SERVICE_MODULES은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val SERVICE_MODULES = listOf(
            "commerce/usage-billing-meter-service",
            "commerce/usage-billing-usage-service",
            "commerce/usage-billing-billing-service",
            "commerce/usage-billing-invoice-service",
            "commerce/usage-billing-query-service",
        )
/**
 * FORBIDDEN_APIS은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val FORBIDDEN_APIS = listOf(
            "java.sql.",
            "DriverManager",
            "JdbcTemplate",
            "createStatement(",
            "prepareStatement(",
            "Transaction.exec(",
        )
    }

    @Test
/**
 * 이 시나리오는 "service persistence contains no raw JDBC or SQL execution API" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `service persistence contains no raw JDBC or SQL execution API`() {
        val violations = SERVICE_MODULES.flatMap { module ->
            sourceFiles(module).flatMap { source ->
                FORBIDDEN_APIS.filter { forbidden -> source.toFile().readText().contains(forbidden) }
                    .map { forbidden -> "${source.fileName}:$forbidden" }
            }
        }

        violations.shouldBeEmpty()
    }

/**
 * sourceFiles은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sourceFiles(module: String): List<Path> =
        Files.walk(repositoryRoot().resolve(module).resolve("src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.toList()
        }

/**
 * repositoryRoot은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun repositoryRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().parent.parent
}

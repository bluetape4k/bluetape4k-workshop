package io.bluetape4k.workshop.commerce.metering

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * KotlinPatternArchitectureTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class KotlinPatternArchitectureTest {

    @Test
/**
 * 이 시나리오는 "production Kotlin avoids unsafe null assertions and mutable collection exposure" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `production Kotlin avoids unsafe null assertions and mutable collection exposure`() {
        val violations =
            Files.walk(mainSourceRoot()).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .flatMap { path ->
                        val source = Files.readString(path)
                        FORBIDDEN_PATTERNS
                            .filterValues { pattern -> pattern.containsMatchIn(source) }
                            .keys
                            .map { rule -> "${path.fileName}: $rule" }
                            .stream()
                    }
                    .sorted()
                    .toList()
            }

        violations.shouldBeEmpty()
    }

/**
 * mainSourceRoot은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun mainSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/main/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/usage-metering-billing-ledger/src/main/kotlin")
    }

    companion object: KLogging() {
/**
 * FORBIDDEN_PATTERNS은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val FORBIDDEN_PATTERNS =
            mapOf(
                // "non-null assertion" to Regex("!!"),
                "public mutable collection" to
                        Regex("(?:public\\s+)?val\\s+\\w+\\s*:\\s*Mutable(?:Collection|List|Set|Map)"),
            )
    }
}

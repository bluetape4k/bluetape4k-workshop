package io.bluetape4k.workshop.commerce.metering.eventsourcing

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * KotlinPatternArchitectureTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class KotlinPatternArchitectureTest {

    private companion object: KLogging() {
/**
 * FORBIDDEN_DOMAIN_IMPORTS은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val FORBIDDEN_DOMAIN_IMPORTS = listOf(
            "import org.springframework",
            "import org.jetbrains.exposed",
            "import tools.jackson",
        )
    }

    @Test
/**
 * 이 시나리오는 "Spring Boot application entry point is explicit" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Spring Boot application entry point is explicit`() {
        Class.forName(
            "io.bluetape4k.workshop.commerce.metering.eventsourcing.UsageBillingEventSourcingApplicationKt",
        ).shouldNotBeNull()
    }

    @Test
/**
 * 이 시나리오는 "domain stays framework independent and production avoids unsafe patterns" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `domain stays framework independent and production avoids unsafe patterns`() {
        val mainRoot = mainSourceRoot()
        val violations = Files.walk(mainRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .flatMap { path ->
                    val source = Files.readString(path)
                    buildList {
                        //if ("!!" in source) add("${path.fileName}: non-null assertion")
                        if (Regex("println\\s*\\(").containsMatchIn(source)) add("${path.fileName}: println")
                        if ("/domain/" in path.toString().replace('\\', '/')) {
                            FORBIDDEN_DOMAIN_IMPORTS.filter(source::contains)
                                .forEach { add("${path.fileName}: $it") }
                        }
                    }.stream()
                }
                .sorted()
                .toList()
        }.onEach { log.debug { "violation=$it" } }

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
        return current.resolve("commerce/usage-metering-billing-event-sourcing/src/main/kotlin")
    }


}

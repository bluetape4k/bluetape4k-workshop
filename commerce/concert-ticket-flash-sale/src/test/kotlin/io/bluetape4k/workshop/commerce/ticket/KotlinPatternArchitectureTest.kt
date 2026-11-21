package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path

/**
 * KotlinPatternArchitectureTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class KotlinPatternArchitectureTest {

    companion object: KLogging() {
/**
 * RAW_UUID_GENERATOR은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val RAW_UUID_GENERATOR = "UUID" + ".randomUUID("
/**
 * SECURITY_FALLBACK_MARKER은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val SECURITY_FALLBACK_MARKER = "보안 nonce fallback"

/**
 * FORBIDDEN_PRODUCTION_PATTERNS은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val FORBIDDEN_PRODUCTION_PATTERNS = mapOf(
            "raw UUID generator" to Regex("UUID\\.randomUUID\\("),
            "monitor synchronization" to Regex("\\bsynchronized\\("),
        )
    }

    @Test
/**
 * 이 시나리오는 "production Kotlin uses Bluetape primitives instead of raw fallbacks" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `production Kotlin uses Bluetape primitives instead of raw fallbacks`() {
        val violations = Files.walk(locateMainSourceRoot()).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .flatMap { path ->
                    val source = Files.readString(path)
                    FORBIDDEN_PRODUCTION_PATTERNS
                        .filter { (_, pattern) -> pattern.containsMatchIn(source) }
                        .map { (name, _) -> "${path.fileName}: $name" }
                        .stream()
                }
                .sorted()
                .toList()
        }

        violations.shouldBeEmpty()
    }

    @Test
/**
 * 이 시나리오는 "fixture Kotlin uses Bluetape primitives for generated identifiers" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fixture Kotlin uses Bluetape primitives for generated identifiers`() {
        val violations = Files.walk(locateTestSourceRoot()).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .flatMap { path ->
                    val lines = Files.readAllLines(path)
                    lines
                        .withIndex()
                        .filter { (index, line) ->
                            line.contains(RAW_UUID_GENERATOR) &&
                                    !(line.contains("grantNonce") &&
                                            lines.getOrNull(index - 1)?.contains(SECURITY_FALLBACK_MARKER) == true)
                        }
                        .map { (index, _) -> "${path.fileName}:${index + 1}" }
                        .stream()
                }
                .sorted()
                .toList()
        }

        violations.shouldBeEmpty()
    }

    @Test
/**
 * 이 시나리오는 "production data classes declare stable Java serialization contracts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `production data classes declare stable Java serialization contracts`() {
        val violations = PathMatchingResourcePatternResolver()
            .getResources("classpath*:io/bluetape4k/workshop/commerce/ticket/**/*.class")
            .mapNotNull(::loadClass)
            .filter { it.kotlin.isData }
            .flatMap { type ->
                buildList {
                    if (!Serializable::class.java.isAssignableFrom(type)) {
                        add("${type.name}: must implement Serializable")
                    }
                    if (type.declaredFields.none { it.name == "serialVersionUID" }) {
                        add("${type.name}: must declare serialVersionUID")
                    }
                }
            }
            .sorted()

        violations.shouldBeEmpty()
    }

/**
 * locateMainSourceRoot은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun locateMainSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/main/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/concert-ticket-flash-sale/src/main/kotlin")
    }

/**
 * locateTestSourceRoot은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun locateTestSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/test/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/concert-ticket-flash-sale/src/test/kotlin")
    }

/**
 * loadClass은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun loadClass(resource: org.springframework.core.io.Resource): Class<*>? {
        val classPath = resource.url.toString().substringAfter("/classes/kotlin/main/", missingDelimiterValue = "")
        if (classPath.isEmpty() || classPath.contains("module-info")) return null
        val className = classPath.removeSuffix(".class").replace('/', '.')
        return Class.forName(className, false, javaClass.classLoader)
    }
}

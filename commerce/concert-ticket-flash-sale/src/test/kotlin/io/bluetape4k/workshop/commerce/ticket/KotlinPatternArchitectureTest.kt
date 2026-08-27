package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path

internal class KotlinPatternArchitectureTest {
    @Test
    fun `production Kotlin uses Bluetape primitives instead of raw fallbacks`() {
        val violations =
            Files.walk(locateMainSourceRoot()).use { paths ->
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
    fun `fixture Kotlin uses Bluetape primitives for generated identifiers`() {
        val violations =
            Files.walk(locateTestSourceRoot()).use { paths ->
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
    fun `production data classes declare stable Java serialization contracts`() {
        val violations =
            PathMatchingResourcePatternResolver()
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

    private fun locateMainSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/main/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/concert-ticket-flash-sale/src/main/kotlin")
    }

    private fun locateTestSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/test/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/concert-ticket-flash-sale/src/test/kotlin")
    }

    private fun loadClass(resource: org.springframework.core.io.Resource): Class<*>? {
        val classPath = resource.url.toString().substringAfter("/classes/kotlin/main/", missingDelimiterValue = "")
        if (classPath.isEmpty() || classPath.contains("module-info")) return null
        val className = classPath.removeSuffix(".class").replace('/', '.')
        return Class.forName(className, false, javaClass.classLoader)
    }

    companion object {
        private const val RAW_UUID_GENERATOR = "UUID" + ".randomUUID("
        private const val SECURITY_FALLBACK_MARKER = "보안 nonce fallback"

        private val FORBIDDEN_PRODUCTION_PATTERNS =
            mapOf(
                "raw UUID generator" to Regex("UUID\\.randomUUID\\("),
                "raw caller validation" to Regex("\\brequire\\("),
                "monitor synchronization" to Regex("\\bsynchronized\\("),
            )
    }
}

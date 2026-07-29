package io.bluetape4k.workshop.commerce.metering

import io.bluetape4k.assertions.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class KotlinPatternArchitectureTest {

    @Test
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

    private fun mainSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/main/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/usage-metering-billing-ledger/src/main/kotlin")
    }

    companion object {
        private val FORBIDDEN_PATTERNS =
            mapOf(
                "non-null assertion" to Regex("!!"),
                "public mutable collection" to
                    Regex("(?:public\\s+)?val\\s+\\w+\\s*:\\s*Mutable(?:Collection|List|Set|Map)"),
            )
    }
}

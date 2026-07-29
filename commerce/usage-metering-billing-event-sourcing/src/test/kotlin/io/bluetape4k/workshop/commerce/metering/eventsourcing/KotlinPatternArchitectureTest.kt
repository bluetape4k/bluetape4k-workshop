package io.bluetape4k.workshop.commerce.metering.eventsourcing

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class KotlinPatternArchitectureTest {
    @Test
    fun `Spring Boot application entry point is explicit`() {
        assertNotNull(
            Class.forName(
                "io.bluetape4k.workshop.commerce.metering.eventsourcing.UsageBillingEventSourcingApplicationKt",
            ),
        )
    }

    @Test
    fun `domain stays framework independent and production avoids unsafe patterns`() {
        val mainRoot = mainSourceRoot()
        val violations = Files.walk(mainRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .flatMap { path ->
                    val source = Files.readString(path)
                    buildList {
                        if ("!!" in source) add("${path.fileName}: non-null assertion")
                        if (Regex("println\\s*\\(").containsMatchIn(source)) add("${path.fileName}: println")
                        if ("/domain/" in path.toString().replace('\\', '/')) {
                            FORBIDDEN_DOMAIN_IMPORTS.filter(source::contains)
                                .forEach { add("${path.fileName}: $it") }
                        }
                    }.stream()
                }
                .sorted()
                .toList()
        }

        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    private fun mainSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/main/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/usage-metering-billing-event-sourcing/src/main/kotlin")
    }

    private companion object {
        val FORBIDDEN_DOMAIN_IMPORTS = listOf(
            "import org.springframework",
            "import org.jetbrains.exposed",
            "import tools.jackson",
        )
    }
}

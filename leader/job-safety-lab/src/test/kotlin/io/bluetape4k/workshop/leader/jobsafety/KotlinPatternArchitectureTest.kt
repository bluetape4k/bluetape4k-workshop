package io.bluetape4k.workshop.leader.jobsafety

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

internal class KotlinPatternArchitectureTest {
    private val sourceRoot = Path.of("src/main/kotlin")

    @Test
    fun `production source contains no raw database access`() {
        val forbidden =
            kotlinSources().flatMap { source ->
                FORBIDDEN_DATABASE_PATTERNS.mapNotNull { pattern ->
                    pattern.takeIf { source.readText().contains(it) }?.let { "${source.name}:$it" }
                }
            }

        forbidden.shouldBeEmpty()
    }

    @Test
    fun `concrete repositories use the bluetape Exposed repository contract`() {
        val repositorySource = sourceRoot.resolve("io/bluetape4k/workshop/leader/jobsafety/persistence/JobSafetyRepositories.kt")
        val text = repositorySource.readText()
        val repositories = Regex("class (Job\\w+Repository)\\(").findAll(text).map { it.groupValues[1] }.toList()

        repositories.isNotEmpty().shouldBeTrue()
        repositories.forEach { repository ->
            Regex("class $repository\\([^)]*\\)\\s*:\\s*JobSafetyExposedJdbcRepository", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(text)
                .shouldBeTrue()
        }
    }

    private fun kotlinSources(): List<Path> =
        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
        }

    companion object {
        private val FORBIDDEN_DATABASE_PATTERNS =
            listOf(
                "Jdbc" + "Template",
                "Prepared" + "Statement",
                "create" + "Statement(",
                "Transaction." + "exec",
                "exec(\"",
                "src/main/resources/db/" + "migration",
            )
    }
}

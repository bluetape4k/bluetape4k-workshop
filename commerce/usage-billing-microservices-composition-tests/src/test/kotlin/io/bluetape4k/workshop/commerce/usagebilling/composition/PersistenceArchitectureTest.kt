package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

class PersistenceArchitectureTest {
    @Test
    fun `service persistence contains no raw JDBC or SQL execution API`() {
        val violations = SERVICE_MODULES.flatMap { module ->
            sourceFiles(module).flatMap { source ->
                FORBIDDEN_APIS.filter { forbidden -> source.toFile().readText().contains(forbidden) }
                    .map { forbidden -> "${source.fileName}:$forbidden" }
            }
        }

        violations shouldBeEqualTo emptyList()
    }

    private fun sourceFiles(module: String): List<Path> =
        Files.walk(repositoryRoot().resolve(module).resolve("src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.toList()
        }

    private fun repositoryRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().parent.parent

    private companion object {
        val SERVICE_MODULES = listOf(
            "commerce/usage-billing-meter-service",
            "commerce/usage-billing-usage-service",
            "commerce/usage-billing-billing-service",
            "commerce/usage-billing-invoice-service",
            "commerce/usage-billing-query-service",
        )
        val FORBIDDEN_APIS = listOf(
            "java.sql.",
            "DriverManager",
            "JdbcTemplate",
            "createStatement(",
            "prepareStatement(",
            "Transaction.exec(",
        )
    }
}

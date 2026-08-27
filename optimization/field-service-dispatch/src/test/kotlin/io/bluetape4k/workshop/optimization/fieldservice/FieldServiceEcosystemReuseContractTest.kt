package io.bluetape4k.workshop.optimization.fieldservice

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class FieldServiceEcosystemReuseContractTest {

    @Test
    fun `default JSON paths use the shared Bluetape mapper`() {
        val sources = listOf(
            "adapter/http/FieldServiceCallbackEnvelope.kt",
            "adapter/http/FieldServiceController.kt",
            "adapter/http/FieldServiceHttpService.kt",
            "persistence/FieldServiceRecords.kt",
        ).map { sourcePath(it) }

        sources.forEach { source ->
            Files.readString(source).shouldContain("Jackson.defaultJsonMapper")
            Files.readString(source).shouldNotContain("JsonMapper.builder")
        }

        val rawMapperFiles = Files.walk(sourceRoot()).use { files ->
            files
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains("JsonMapper.builder") }
                .map { sourceRoot().relativize(it).toString() }
                .sorted()
                .toList()
        }
        rawMapperFiles.shouldNotBeEmpty()
        rawMapperFiles shouldBeEqualTo listOf("adapter/http/FieldServiceCanonicalizer.kt")
        Files.readString(sourcePath("adapter/http/FieldServiceCanonicalizer.kt"))
            .replace(Regex("\\s+"), " ")
            .shouldContain("strict canonical input guard")
    }

    @Test
    fun `field service does not retain unused HTTP provider dependencies`() {
        val build = Files.readString(projectRoot().resolve("optimization/field-service-dispatch/build.gradle.kts"))
        build.shouldNotContain("libs.bluetape4k.http")
        build.shouldNotContain("libs.httpclient5")
        build.shouldNotContain("libs.httpcore5.lib")
    }

    @Test
    fun `field service persistence uses Bluetape Exposed and UUID capabilities`() {
        val repositories = Files.readString(sourcePath("persistence/FieldServiceRepositories.kt"))
        repositories.shouldContain("LongJdbcRepository<FieldServicePlanRecord>")
        repositories.shouldContain("findBy(")
        repositories.shouldContain("Uuid.V4.nextUUID()")

        val tables = Files.readString(sourcePath("persistence/FieldServiceTables.kt"))
        tables.shouldContain("FieldServicePlansTable : LongIdTable")
    }

    private fun projectRoot(): Path = listOf(Path.of("."), Path.of("../.."))
        .first { Files.exists(it.resolve("optimization/field-service-dispatch/build.gradle.kts")) }

    private fun sourceRoot(): Path = projectRoot().resolve(
        "optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice",
    )

    private fun sourcePath(relative: String): Path = sourceRoot().resolve(relative)
}

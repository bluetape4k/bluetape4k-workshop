package io.bluetape4k.workshop.buildlogic.highcontention

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighContentionArtifactValidatorTest {

    @Test
    fun `launch failure writes one constants only fallback`(@org.junit.jupiter.api.io.TempDir root: Path) {
        val runRoot = Files.createDirectory(root.resolve("run"))
        val uploadRoot = Files.createDirectory(root.resolve("upload"))
        val validator = validator {
            throw IllegalStateException("sensitive /tmp/path token=secret")
        }

        assertFailsWith<IllegalStateException> {
            validator.validate(root, runRoot, uploadRoot)
        }

        val fallback = uploadRoot.resolve("upload-failure-summary.json")
        assertEquals(HighContentionArtifactValidator.FAILURE_BYTES.decodeToString(), fallback.readText())
        assertFalse(fallback.readText().contains("sensitive"))
        assertEquals(1, Files.list(uploadRoot).use { it.count() })
    }

    @Test
    fun `timeout crash and malformed results leave the same no replace fallback`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        listOf(
            HighContentionValidationProcessResult(timedOut = true, exitCode = null, stdout = ""),
            HighContentionValidationProcessResult(timedOut = false, exitCode = 9, stdout = ""),
            HighContentionValidationProcessResult(timedOut = false, exitCode = 0, stdout = "not-json"),
        ).forEachIndexed { index, result ->
            val runRoot = Files.createDirectory(root.resolve("run-$index"))
            val uploadRoot = Files.createDirectory(root.resolve("upload-$index"))

            assertFailsWith<IllegalStateException> {
                validator { result }.validate(root, runRoot, uploadRoot)
            }
            assertEquals(
                HighContentionArtifactValidator.FAILURE_BYTES.decodeToString(),
                uploadRoot.resolve("upload-failure-summary.json").readText(),
            )
        }
    }

    @Test
    fun `missing terminal output fails even when validator process exits zero`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val runRoot = Files.createDirectory(root.resolve("run"))
        val uploadRoot = Files.createDirectory(root.resolve("upload"))

        assertFailsWith<IllegalStateException> {
            validator {
                HighContentionValidationProcessResult(
                    timedOut = false,
                    exitCode = 0,
                    stdout = """{"result":"PASS"}""",
                )
            }.validate(root, runRoot, uploadRoot)
        }

        assertTrue(Files.isRegularFile(uploadRoot.resolve("upload-failure-summary.json")))
    }

    @Test
    fun `verified terminal output passes without fallback`(@org.junit.jupiter.api.io.TempDir root: Path) {
        val runRoot = Files.createDirectory(root.resolve("run"))
        val uploadRoot = Files.createDirectory(root.resolve("upload"))
        runRoot.resolve("summary.json").writeText("""{"schemaVersion":1,"result":"PASS"}""")
        runRoot.resolve("upload-manifest.json").writeText(
            """{"schemaVersion":1,"files":["summary.json"]}""",
        )

        validator {
            HighContentionValidationProcessResult(
                timedOut = false,
                exitCode = 0,
                stdout = """{"result":"PASS"}""",
            )
        }.validate(root, runRoot, uploadRoot)

        assertFalse(Files.exists(uploadRoot.resolve("upload-failure-summary.json")))
    }

    private fun validator(
        launch: () -> HighContentionValidationProcessResult,
    ): HighContentionArtifactValidator =
        HighContentionArtifactValidator(
            nodeExecutable = Path.of("node"),
            script = Path.of("scripts/high-contention/validate-run.mjs"),
            timeoutMillis = 1_000,
            processLauncher = HighContentionValidationProcessLauncher { _, _ -> launch() },
        )
}

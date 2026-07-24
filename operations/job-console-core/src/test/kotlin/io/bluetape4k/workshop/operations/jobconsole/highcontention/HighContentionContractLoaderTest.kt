package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.support.requireNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class HighContentionContractLoaderTest {

    @Test
    fun `valid selection preserves the canonical profile and implementation order`() {
        val loaded = HighContentionContractLoader().load(
            contractRoot = contractRoot(),
            mode = HighContentionMode.CI_CORRECTNESS,
        )

        loaded.selections.size shouldBeEqualTo 28
        loaded.selections.first().profile.profileId shouldBeEqualTo "burst"
        loaded.selections.first().implementation shouldBeEqualTo "job-core"
        loaded.selections.last().profile.profileId shouldBeEqualTo "duplicate-delivery"
        loaded.selections.last().implementation shouldBeEqualTo "ticket-spring"
    }

    @Test
    fun `optional filters select one exact matrix tuple`() {
        val loaded = HighContentionContractLoader().load(
            contractRoot = contractRoot(),
            mode = HighContentionMode.LOCAL_REFERENCE,
            profileId = "redis-path-outage",
            implementation = "job-spring",
        )

        loaded.selections.size shouldBeEqualTo 1
        loaded.selections.single().profile.mode shouldBeEqualTo HighContentionMode.LOCAL_REFERENCE
        loaded.selections.single().profile.profileId shouldBeEqualTo "redis-path-outage"
        loaded.selections.single().implementation shouldBeEqualTo "job-spring"
    }

    @Test
    fun `empty and unknown caller filters fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            HighContentionContractLoader().load(
                contractRoot = contractRoot(),
                mode = HighContentionMode.CI_CORRECTNESS,
                profileId = "",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HighContentionContractLoader().load(
                contractRoot = contractRoot(),
                mode = HighContentionMode.CI_CORRECTNESS,
                implementation = "unknown-adapter",
            )
        }
    }

    @Test
    fun `duplicate keys and unknown fields fail before topology startup`(@TempDir tempDir: Path) {
        val duplicateRoot = copyContract(tempDir.resolve("duplicate"))
        duplicateRoot.resolve("suite-manifest.json").replaceText(
            "\"suiteSchemaVersion\": 1,",
            "\"suiteSchemaVersion\": 1,\n  \"suiteSchemaVersion\": 1,",
        )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(duplicateRoot, HighContentionMode.CI_CORRECTNESS)
        }

        val unknownRoot = copyContract(tempDir.resolve("unknown"))
        unknownRoot.resolve("profiles/ci-correctness/burst.json").replaceText(
            "\"knownLimitations\":",
            "\"unreviewedOption\": true,\n  \"knownLimitations\":",
        )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(unknownRoot, HighContentionMode.CI_CORRECTNESS)
        }
    }

    @Test
    fun `suite profile and report versions are checked independently`(@TempDir tempDir: Path) {
        listOf(
            Triple("suite-manifest.json", "\"suiteSchemaVersion\": 1", "\"suiteSchemaVersion\": 2"),
            Triple("suite-manifest.json", "\"profileSchemaVersion\": 1", "\"profileSchemaVersion\": 2"),
            Triple("report-contract.json", "\"reportSchemaVersion\": 1", "\"reportSchemaVersion\": 2"),
        ).forEachIndexed { index, (relativePath, oldValue, newValue) ->
            val copiedRoot = copyContract(tempDir.resolve("version-$index"))
            copiedRoot.resolve(relativePath).replaceText(oldValue, newValue)

            assertFailsWith<HighContentionContractException> {
                HighContentionContractLoader().load(copiedRoot, HighContentionMode.CI_CORRECTNESS)
            }
        }
    }

    @Test
    fun `negative and overflowing numeric values fail closed`(@TempDir tempDir: Path) {
        val negativeRoot = copyContract(tempDir.resolve("negative"))
        negativeRoot.resolve("profiles/ci-correctness/burst.json").replaceText(
            "\"operationCount\": 48",
            "\"operationCount\": -1",
        )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(negativeRoot, HighContentionMode.CI_CORRECTNESS)
        }

        val overflowRoot = copyContract(tempDir.resolve("overflow"))
        overflowRoot.resolve("profiles/ci-correctness/burst.json").replaceText(
            "\"operationCount\": 48",
            "\"operationCount\": 9223372036854775808",
        )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(overflowRoot, HighContentionMode.CI_CORRECTNESS)
        }
    }

    @Test
    fun `document size and nesting depth are bounded`(@TempDir tempDir: Path) {
        val oversizedRoot = copyContract(tempDir.resolve("oversized"))
        Files.writeString(
            oversizedRoot.resolve("suite-manifest.json"),
            " ".repeat(1_048_577),
        )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(oversizedRoot, HighContentionMode.CI_CORRECTNESS)
        }

        val deepRoot = copyContract(tempDir.resolve("deep"))
        val nested = "[".repeat(40) + "0" + "]".repeat(40)
        deepRoot.resolve("suite-manifest.json").replaceText(
            "\"entries\":",
            "\"tooDeep\": $nested,\n  \"entries\":",
        )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(deepRoot, HighContentionMode.CI_CORRECTNESS)
        }
    }

    @Test
    fun `profile paths cannot escape or cross a symbolic link`(@TempDir tempDir: Path) {
        val escapedRoot = copyContract(tempDir.resolve("escaped"))
        escapedRoot.resolve("suite-manifest.json").replaceText(
            "\"profileFile\": \"profiles/ci-correctness/burst.json\"",
            "\"profileFile\": \"../burst.json\"",
        )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(escapedRoot, HighContentionMode.CI_CORRECTNESS)
        }

        val symlinkRoot = copyContract(tempDir.resolve("symlink"))
        val profile = symlinkRoot.resolve("profiles/ci-correctness/burst.json")
        Files.delete(profile)
        Files.createSymbolicLink(profile, contractRoot().resolve("profiles/ci-correctness/burst.json"))
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(symlinkRoot, HighContentionMode.CI_CORRECTNESS)
        }
    }

    @Test
    fun `a file identity change during a stable read fails closed`(@TempDir tempDir: Path) {
        val copiedRoot = copyContract(tempDir.resolve("identity"))
        var replaced = false
        val loader = HighContentionContractLoader(
            fileReadObserver = HighContentionFileReadObserver { path ->
                if (!replaced && path.fileName.toString() == "suite-manifest.json") {
                    val replacement = path.resolveSibling("suite-manifest.replacement.json")
                    Files.copy(path, replacement)
                    Files.move(replacement, path, StandardCopyOption.REPLACE_EXISTING)
                    replaced = true
                }
            },
        )

        assertFailsWith<HighContentionContractException> {
            loader.load(copiedRoot, HighContentionMode.CI_CORRECTNESS)
        }
    }

    private fun contractRoot(): Path =
        Path.of(System.getProperty("highContentionContractRoot").requireNotNull("highContentionContractRoot"))

    private fun copyContract(target: Path): Path {
        val source = contractRoot()
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(path, destination)
                }
            }
        }
        return target
    }

    private fun Path.replaceText(oldValue: String, newValue: String) {
        Files.writeString(this, Files.readString(this).replaceFirst(oldValue, newValue))
    }
}

package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * HighContentionContractLoaderTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionContractLoaderTest {

    companion object: KLogging() {
/**
 * contractRoot은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun contractRoot(): Path =
            Path.of(System.getProperty("highContentionContractRoot").shouldNotBeNull())

/**
 * copyContract은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * replaceText은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun Path.replaceText(oldValue: String, newValue: String) {
            Files.writeString(this, Files.readString(this).replaceFirst(oldValue, newValue))
        }
    }

    @Test
/**
 * 이 시나리오는 "valid selection preserves the canonical profile and implementation order" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `valid selection preserves the canonical profile and implementation order`() {
        val loaded = HighContentionContractLoader()
            .load(
                contractRoot = contractRoot(),
                mode = HighContentionMode.CI_CORRECTNESS,
            )

        log.debug { "loaded=$loaded" }
        loaded.selections.size shouldBeEqualTo 28
        loaded.selections.first().profile.profileId shouldBeEqualTo "burst"
        loaded.selections.first().implementation shouldBeEqualTo "job-core"
        loaded.selections.last().profile.profileId shouldBeEqualTo "duplicate-delivery"
        loaded.selections.last().implementation shouldBeEqualTo "ticket-spring"
    }

    @Test
/**
 * 이 시나리오는 "optional filters select one exact matrix tuple" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `optional filters select one exact matrix tuple`() {
        val loaded = HighContentionContractLoader()
            .load(
                contractRoot = contractRoot(),
                mode = HighContentionMode.LOCAL_REFERENCE,
                profileId = "redis-path-outage",
                implementation = "job-spring",
            )

        log.debug { "loaded=$loaded" }
        loaded.selections.size shouldBeEqualTo 1
        loaded.selections.single().profile.mode shouldBeEqualTo HighContentionMode.LOCAL_REFERENCE
        loaded.selections.single().profile.profileId shouldBeEqualTo "redis-path-outage"
        loaded.selections.single().implementation shouldBeEqualTo "job-spring"
    }

    @Test
/**
 * 이 시나리오는 "empty and unknown caller filters fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "duplicate keys and unknown fields fail before topology startup" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `duplicate keys and unknown fields fail before topology startup`(@TempDir tempDir: Path) {
        val duplicateRoot = copyContract(tempDir.resolve("duplicate"))
        duplicateRoot.resolve("suite-manifest.json")
            .replaceText(
                "\"suiteSchemaVersion\": 1,",
                "\"suiteSchemaVersion\": 1,\n  \"suiteSchemaVersion\": 1,",
            )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(duplicateRoot, HighContentionMode.CI_CORRECTNESS)
        }

        val unknownRoot = copyContract(tempDir.resolve("unknown"))
        unknownRoot.resolve("profiles/ci-correctness/burst.json")
            .replaceText(
                "\"knownLimitations\":",
                "\"unreviewedOption\": true,\n  \"knownLimitations\":",
            )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(unknownRoot, HighContentionMode.CI_CORRECTNESS)
        }
    }

    @Test
/**
 * 이 시나리오는 "suite profile and report versions are checked independently" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "report vocabulary is checked independently" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `report vocabulary is checked independently`(@TempDir tempDir: Path) {
        val copiedRoot = copyContract(tempDir.resolve("report-vocabulary"))
        copiedRoot.resolve("report-contract.json").replaceText(
            "\"INVALID_PROFILE\"",
            "\"SURPRISE_ERROR\"",
        )

        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(copiedRoot, HighContentionMode.CI_CORRECTNESS)
        }

        val failurePointRoot = copyContract(tempDir.resolve("failure-point-vocabulary"))
        failurePointRoot.resolve("report-contract.json").replaceText(
            "\"BEFORE_AUTHORITY\"",
            "\"DURING_AUTHORITY\"",
        )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(failurePointRoot, HighContentionMode.CI_CORRECTNESS)
        }
    }

    @Test
/**
 * 이 시나리오는 "negative and overflowing numeric values fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "document size and nesting depth are bounded" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
        deepRoot.resolve("suite-manifest.json")
            .replaceText(
                "\"entries\":",
                "\"tooDeep\": $nested,\n  \"entries\":",
            )
        assertFailsWith<HighContentionContractException> {
            HighContentionContractLoader().load(deepRoot, HighContentionMode.CI_CORRECTNESS)
        }
    }

    @Test
/**
 * 이 시나리오는 "profile paths cannot escape or cross a symbolic link" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `profile paths cannot escape or cross a symbolic link`(@TempDir tempDir: Path) {
        val escapedRoot = copyContract(tempDir.resolve("escaped"))
        escapedRoot.resolve("suite-manifest.json")
            .replaceText(
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
/**
 * 이 시나리오는 "a file identity change during a stable read fails closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `a file identity change during a stable read fails closed`(@TempDir tempDir: Path) {
        val copiedRoot = copyContract(tempDir.resolve("identity"))
        var replaced = false
        val loader = HighContentionContractLoader(
            fileReadObserver = { path ->
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
}

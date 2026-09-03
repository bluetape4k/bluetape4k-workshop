# Issue #861 Sudachi 일본어 tokenizer 비교 예제 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `kotlin-text-processing`에 Kuromoji와 Sudachi JVM의 동일 corpus 비교 예제를 추가하고, 공식 SudachiDict를 별도 준비하는 재현 가능한 통합 실행 경계를 제공한다.

**Architecture:** 기존 `JapaneseProcessor` 색인 경로는 그대로 두고 새 `text.tokenizer` package에 내부 observation report를 추가한다. 기본 `test`는 dictionary 없이 Kuromoji와 `UNAVAILABLE` candidate를 검증하며, `sudachiTest`만 `prepareSudachiDictionary`를 먼저 실행해 실제 Sudachi A/B/C 결과를 검증한다. 사전은 공식 archive의 URL·크기·SHA-256·ZIP entry를 확인한 뒤 `build/` 아래에만 추출한다.

**Tech Stack:** Kotlin 2.4.0, Java 25, Gradle Kotlin DSL, JUnit 5, `com.worksap.nlp:sudachi:0.8.0`, `bluetape4k-text` `JapaneseProcessor`, 공식 SudachiDict `v20260428` core.

---

## 파일 구조와 책임

| 파일 | 변경 책임 |
|---|---|
| `gradle/libs.versions.toml` | 외부 `sudachi` version과 catalog alias 추가 |
| `kotlin/text-processing/build.gradle.kts` | Sudachi dependency, dictionary preparation, `sudachiTest` 실행 경계 추가 |
| `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/tokenizer/JapaneseBackendComparisonExamples.kt` | Kuromoji/Sudachi observation model, corpus, 실행·render 함수 |
| `kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/tokenizer/JapaneseBackendComparisonExamplesTest.kt` | dictionary 없는 기본 경로와 report 계약의 단위 회귀 테스트 |
| `kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/tokenizer/JapaneseBackendComparisonIntegrationTest.kt` | `sudachi-integration` tag의 실제 dictionary-backed 결과 테스트 |
| `kotlin/text-processing/README.md` | English 실행법·의존성·migration 경계 |
| `kotlin/text-processing/README.ko.md` | 위 English README와 의미가 같은 Korean 안내 |
| `.github/workflows/Examples.yml` | 기존 module 변경을 감지하고 기본 text-processing 회귀 test를 hosted smoke에 포함 |
| `scripts/smoke-validate.sh` | `all-smoke`에서 `:kotlin-text-processing:test` 실행 |
| `docs/lessons/2026-09-03-issue-861-sudachi-japanese-comparison.md` | 결정, 검증 결과, 재발 방지 guard 기록 |

새 module을 만들지 않으므로 `settings.gradle.kts`, module validation matrix,
Kover aggregation은 수정하지 않는다. 다만 기존 module의 source/test 변경이
hosted 기본 회귀 경로에서 빠지지 않도록 Examples workflow path filter와
`all-smoke` 명령은 갱신한다. `sudachiTest`는 217 MB dictionary를 요구하므로
hosted 기본 smoke에는 넣지 않고 local/manual-only로 유지한다.

## Task 1: catalog와 dictionary preparation 경계 추가

**Files:**
- Modify: `gradle/libs.versions.toml`의 `[libraries]` `bluetape4k-text-*` 인접 영역
- Modify: `kotlin/text-processing/build.gradle.kts`

- [ ] **Step 1: external catalog alias와 dependency 선언을 추가한다**

`bluetape4k-dependencies` BOM의 published constraints와 catalog source를
현재 checkout에서 확인한 결과 `com.worksap.nlp:sudachi`는 catalog에는
있지만 BOM의 `api(...)` constraint에는 포함되지 않는다. 따라서 외부
Sudachi artifact의 버전은 workshop catalog에서 명시하고, Bluetape 모듈은
기존처럼 root BOM으로 관리한다. 다음 두 항목을 추가한다.

```toml
# External Sudachi JVM artifact; it is not a bluetape4k module governed by the BOM.
[versions]
sudachi = "0.8.0"

[libraries]
sudachi = { module = "com.worksap.nlp:sudachi", version.ref = "sudachi" }
```

`kotlin/text-processing/build.gradle.kts`의 `dependencies`에는 다음 한 줄을 추가한다.

```kotlin
implementation(libs.sudachi)
```

root `implementation(platform(rootLibs.bluetape4k.dependencies))`는 모든
`io.github.bluetape4k.*` 모듈을 계속 관리하고, 위 catalog version은
`com.worksap.nlp` 외부 artifact에만 적용한다. 개별 text BOM import나
Bluetape 모듈의 명시적 version pinning은 추가하지 않는다. 중앙
`bluetape4k-dependencies` catalog/BOM에 Sudachi constraint를 추가하는 일은
별도 dependencies repository 변경으로 분리한다.

- [ ] **Step 2: 고정된 SudachiDict 값과 SHA-256 helper를 정의한다**

파일 상단에 다음 상수를 둔다. URL, archive 크기, digest, ZIP entry, 추출 파일 크기는 Issue #861 설계 문서와 공식 release에서 확인한 값 그대로 유지한다.

```kotlin
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.zip.ZipFile

private const val SUDACHI_DICTIONARY_VERSION = "20260428"
private const val SUDACHI_DICTIONARY_ARCHIVE_NAME =
    "sudachi-dictionary-20260428-core.zip"
private const val SUDACHI_DICTIONARY_ENTRY =
    "sudachi-dictionary-20260428/system_core.dic"
private const val SUDACHI_DICTIONARY_ARCHIVE_SHA256 =
    "40c8ffc095283f07aa06cae922e7b8147bf2919ec8830567b0b3f7a7efa3239f"
private const val SUDACHI_DICTIONARY_ARCHIVE_SIZE = 72_238_136L
private const val SUDACHI_DICTIONARY_SIZE = 217_374_303L
private const val SUDACHI_DICTIONARY_SHA256 =
    "6c1d5adc8a2389875713056e7b39bbcd0073d6122ffd509866e1d3a196f8608e"
private const val SUDACHI_DICTIONARY_URL =
    "https://github.com/WorksApplications/SudachiDict/releases/download/v20260428/sudachi-dictionary-20260428-core.zip"

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
```

`sha256()`는 `Files.isRegularFile(path, NOFOLLOW_LINKS)`와
`!Files.isSymbolicLink(path)`를 먼저 확인하고, 확인된 regular file만 읽는다.
archive와 extracted dictionary 모두 이 helper로 검증한다.

- [ ] **Step 3: `prepareSudachiDictionary`를 idempotent·검증 우선 task로 작성한다**

`build/sudachi-dictionary/v20260428`를 `layout.buildDirectory`로 계산하고,
archive와 extracted dictionary 모두 검증 가능한 staging 파일에서 시작한다.
archive는 offline 재실행을 위한 build-only cache output으로 남기고,
`system_core.dic`는 integration test가 읽는 build-only test input으로
취급한다. 두 파일과 `.part`는 모두 Git에 추가하지 않는다.
다음 동작을 코드로 고정한다.

```kotlin
val sudachiDictionaryDirectory =
    layout.buildDirectory.dir("sudachi-dictionary/v$SUDACHI_DICTIONARY_VERSION")
val sudachiDictionaryArchive =
    sudachiDictionaryDirectory.map { it.file(SUDACHI_DICTIONARY_ARCHIVE_NAME) }
val sudachiSystemDictionary =
    sudachiDictionaryDirectory.map { it.file("system_core.dic") }

val prepareSudachiDictionary = tasks.register("prepareSudachiDictionary") {
    description = "Downloads and verifies the pinned SudachiDict core dictionary into build/."
    group = "verification"
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
    inputs.property("sudachiDictionaryUrl", SUDACHI_DICTIONARY_URL)
    inputs.property("sudachiDictionaryArchiveSha256", SUDACHI_DICTIONARY_ARCHIVE_SHA256)
    inputs.property("sudachiDictionaryArchiveSize", SUDACHI_DICTIONARY_ARCHIVE_SIZE)
    inputs.property("sudachiDictionarySha256", SUDACHI_DICTIONARY_SHA256)
    inputs.property("sudachiDictionarySize", SUDACHI_DICTIONARY_SIZE)
    outputs.files(sudachiDictionaryArchive, sudachiSystemDictionary)
    outputs.upToDateWhen {
        val directory = sudachiDictionaryDirectory.get().asFile.toPath()
        val archive = sudachiDictionaryArchive.get().asFile.toPath()
        val dictionary = sudachiSystemDictionary.get().asFile.toPath()
        isSafeDirectoryPath(directory) &&
            isSafeRegularPath(archive) &&
            Files.size(archive) == SUDACHI_DICTIONARY_ARCHIVE_SIZE &&
            archive.toFile().sha256() == SUDACHI_DICTIONARY_ARCHIVE_SHA256 &&
            isSafeRegularPath(dictionary) &&
            Files.size(dictionary) == SUDACHI_DICTIONARY_SIZE &&
            dictionary.toFile().sha256() == SUDACHI_DICTIONARY_SHA256 &&
            !Files.exists(archive.resolveSibling("${archive.fileName}.part"), NOFOLLOW_LINKS) &&
            !Files.exists(dictionary.resolveSibling("${dictionary.fileName}.part"), NOFOLLOW_LINKS)
    }

    doLast {
        val directory = sudachiDictionaryDirectory.get().asFile.toPath()
        val archive = sudachiDictionaryArchive.get().asFile.toPath()
        val dictionary = sudachiSystemDictionary.get().asFile.toPath()
        val archivePart = archive.resolveSibling("${archive.fileName}.part")
        val dictionaryPart = dictionary.resolveSibling("${dictionary.fileName}.part")
        requireSafeDirectoryTree(directory.parent)
        rejectSymlink(archive)
        rejectSymlink(archivePart)
        rejectSymlink(dictionary)
        rejectSymlink(dictionaryPart)
        Files.createDirectories(directory)
        requireSafeDirectoryTree(directory)

        try {
            if (!isSafeRegularPath(archive) ||
                Files.size(archive) != SUDACHI_DICTIONARY_ARCHIVE_SIZE ||
                archive.toFile().sha256() != SUDACHI_DICTIONARY_ARCHIVE_SHA256
            ) {
                Files.deleteIfExists(archivePart)
                val connection = openPinnedHttpsConnection(URI(SUDACHI_DICTIONARY_URL))
                try {
                    val contentLength = connection.contentLengthLong
                    require(contentLength < 0 || contentLength == SUDACHI_DICTIONARY_ARCHIVE_SIZE) {
                        "Unexpected SudachiDict archive content length"
                    }
                    connection.inputStream.buffered().use { input ->
                        Files.newOutputStream(archivePart, CREATE_NEW, WRITE).buffered().use { output ->
                            copyBounded(input, output, SUDACHI_DICTIONARY_ARCHIVE_SIZE)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                require(Files.size(archivePart) == SUDACHI_DICTIONARY_ARCHIVE_SIZE)
                require(archivePart.toFile().sha256() == SUDACHI_DICTIONARY_ARCHIVE_SHA256)
                Files.move(archivePart, archive, ATOMIC_MOVE, REPLACE_EXISTING)
            }

            Files.deleteIfExists(dictionaryPart)
            ZipFile(archive.toFile()).use { zip ->
                require(zip.getEntry("sudachi-dictionary-20260428/LEGAL") != null)
                require(zip.getEntry("sudachi-dictionary-20260428/LICENSE-2.0.txt") != null)
                val entry = requireNotNull(zip.getEntry(SUDACHI_DICTIONARY_ENTRY))
                require(entry.size < 0 || entry.size == SUDACHI_DICTIONARY_SIZE)
                zip.getInputStream(entry).buffered().use { input ->
                    Files.newOutputStream(dictionaryPart, CREATE_NEW, WRITE).buffered().use { output ->
                        copyBounded(input, output, SUDACHI_DICTIONARY_SIZE)
                    }
                }
            }
            require(Files.size(dictionaryPart) == SUDACHI_DICTIONARY_SIZE)
            require(dictionaryPart.toFile().sha256() == SUDACHI_DICTIONARY_SHA256)
            Files.move(dictionaryPart, dictionary, ATOMIC_MOVE, REPLACE_EXISTING)
            Files.deleteIfExists(archivePart)
        } catch (error: Throwable) {
            Files.deleteIfExists(archivePart)
            Files.deleteIfExists(dictionaryPart)
            throw error
        }
    }
}
```

구현 helper의 필수 계약은 다음과 같다.

- `rejectSymlink`와 `requireSafeDirectoryTree`는 기존 부모와 대상에
  `Files.isSymbolicLink`를 적용하고, regular-file 확인은
  `Files.isRegularFile(path, NOFOLLOW_LINKS)`로 수행한다. `isSafeDirectoryPath`
  는 같은 방식으로 non-symlink directory만 허용한다. 아직 없는 ancestor와
  leaf directory는 첫 실행에서 허용하지만, 이미 존재하는 경로가 symlink이면
  거부한다. `Files.createDirectories` 전에는 존재하는 부모까지 검사하고,
  생성 후에는 전체 트리를 다시 검사해 workspace 밖으로 나가는 symlink를
  차단한다.
- `openPinnedHttpsConnection`은 최대 5회의 redirect만 직접 따라가며,
  각 단계의 scheme은 `https`이고 host는 `github.com`,
  `objects.githubusercontent.com`, `release-assets.githubusercontent.com`
  중 하나인지 확인한다. 각 connection에 connect timeout 30초와 read timeout
  120초를 설정하고, response code가 2xx인지 확인한다. redirect 응답에는
  `Location`이 반드시 있어야 하며, 다음 connection을 열기 전에 현재와
  중간 connection을 모두 `disconnect()`한다. generic HTTP connection,
  HTTP downgrade, timeout·status·redirect 횟수 초과는 거부한다.
- `copyBounded`는 남은 예상 크기보다 최대 한 바이트만 읽어
  `expectedSize + 1`에서 즉시 실패한다. archive 응답의
  `contentLengthLong`이 알려진 경우에는 스트림을 열기 전에 동일한
  고정값인지 검사한다.
- archive와 dictionary는 `.part`에 `CREATE_NEW`로 기록하고 내용 검증 후
  `ATOMIC_MOVE`와 `REPLACE_EXISTING`으로 최종 경로에 승격한다. 대상이나
  부모가 symlink이면 먼저 실패하며, atomic move를 지원하지 않는
  파일시스템에서는 안전을 위해 task를 실패시킨다.
- `upToDateWhen`은 archive와 extracted dictionary의 크기·digest를 모두
  확인하고 stale `.part`가 없을 때만 `true`를 반환한다. 성공 시 archive와
  dictionary `.part`를 제거하고, 실패 시에도 `finally`에서 partial을
  제거한다. 전체 파일 경로는 예외·report 메시지에 포함하지 않는다.

ZIP 전체를 임의 경로로 풀지 않고 승인된 `SUDACHI_DICTIONARY_ENTRY` 하나만
bounded streaming해 추출한다. archive 또는 entry 검증 실패는 task 실패로
남기며 invalid dictionary를 실행 경계에 전달하지 않는다.

`sudachiTest`에는 저장소 선례와 같은 `failOnZeroTests()` listener를 추가한다.
tag가 사라지거나 test discovery가 잘못되어도 Gradle 성공으로 오인하지
않도록 실제 `afterTest` 호출 수가 1 이상인지 `doLast`에서 확인한다.

- [ ] **Step 4: Gradle task wiring만 추가하고 기본 test와 분리한다**

기존 `test` task는 다음처럼 tag를 제외하고, 새 task는 test classpath를 재사용해 tag만 포함한다.

```kotlin
tasks.test {
    useJUnitPlatform {
        excludeTags("sudachi-integration")
    }
}

tasks.register<Test>("sudachiTest") {
    description = "Runs Sudachi dictionary-backed tokenizer comparisons."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    workingDir(projectDir)
    dependsOn(prepareSudachiDictionary)
    shouldRunAfter(tasks.test)
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
    inputs.file(sudachiSystemDictionary)
        .withPropertyName("sudachiSystemDictionary")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty(
        "bluetape4k.sudachi.system-dictionary",
        sudachiSystemDictionary.get().asFile.absolutePath,
    )
    useJUnitPlatform {
        includeTags("sudachi-integration")
    }
    failOnZeroTests()
    jvmArgs = tasks.test.get().jvmArgs
    systemProperties(tasks.test.get().systemProperties)
    environment(tasks.test.get().environment)
}
```

`test`에는 `prepareSudachiDictionary` dependency를 걸지 않는다. 이 단계의
검증은 `./gradlew :kotlin-text-processing:tasks --all`에서 두 task가 보이고,
`--dry-run` task graph에서 `test`에는 preparation이 없고 `sudachiTest`에는
preparation이 먼저 포함되는지 확인하는 것이다. `sudachiTest`의 dictionary
경로 input fingerprint와 `tasks.test`의 system properties/environment까지
복사해 기본 test runtime과의 차이를 의도적으로 없앤다.

## Task 2: 단위·통합 테스트를 먼저 작성한다

**Files:**
- Create: `kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/tokenizer/JapaneseBackendComparisonExamplesTest.kt`
- Create: `kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/tokenizer/JapaneseBackendComparisonIntegrationTest.kt`

- [ ] **Step 1: 기본 오프라인 경로의 failing test를 작성한다**

`JapaneseBackendComparisonExamplesTest`는 `@TestInstance(PER_CLASS)`를 사용하고 `io.bluetape4k.assertions` 확장만 사용한다. 외부 Gradle invocation이 property를 주입해도 기본 단위 테스트가 흔들리지 않도록 `@BeforeEach`에서 `bluetape4k.sudachi.system-dictionary`의 기존 값을 저장한 뒤 지우고, `@AfterEach`에서 이전 값을 복구한다. property를 임시로 설정하는 보조 함수도 `try/finally`로 감싸 assertion 실패 시 원복을 보장한다. 첫 테스트는 다음 계약을 고정한다.

```kotlin
private const val SUDACHI_DICTIONARY_PROPERTY = "bluetape4k.sudachi.system-dictionary"

private var previousDictionaryPath: String? = null

@BeforeEach
fun clearSudachiProperty() {
    previousDictionaryPath = System.getProperty(SUDACHI_DICTIONARY_PROPERTY)
    System.clearProperty(SUDACHI_DICTIONARY_PROPERTY)
}

@AfterEach
fun restoreSudachiProperty() {
    if (previousDictionaryPath == null) {
        System.clearProperty(SUDACHI_DICTIONARY_PROPERTY)
    } else {
        System.setProperty(SUDACHI_DICTIONARY_PROPERTY, requireNotNull(previousDictionaryPath))
    }
}

@Test
fun `default comparison keeps Kuromoji live and Sudachi unavailable`() {
    val report = runJapaneseBackendComparison()

    report.current.backend shouldBeEqualTo "Kuromoji IPADic"
    report.current.execution shouldBeEqualTo BackendExecution.LIVE
    report.current.tokens.shouldNotBeEmpty()
    report.candidate.backend shouldBeEqualTo "Sudachi JVM"
    report.candidate.execution shouldBeEqualTo BackendExecution.UNAVAILABLE
    report.candidate.tokens shouldBeEmpty()
    report.candidate.splitModes shouldBeEmpty()
    report.candidate.statusMessage.orEmpty() shouldContain "prepareSudachiDictionary"
}
```

같은 파일에 `comparisonCorpus()`가 정확히 세 문장을 같은 순서로 반환하는지, 승인되지 않은 입력이 `IllegalArgumentException`을 내는지, 준비된 build 출력과 일치하지 않는 dictionary path 또는 malformed system property가 `UNAVAILABLE`과 절대 경로 없는 원인을 반환하는지, dictionary 파일 또는 부모 디렉터리가 symlink이면 실행하지 않는지, 같은 크기의 변조 dictionary가 `UNAVAILABLE`이 되는지 테스트한다. property 복구 테스트는 의도적으로 assertion을 실패시키는 보조 블록을 실행한 뒤 다음 assertion에서 원래 property가 복원되었는지 확인한다. render 결과는 backend/license/dependency/archive hash/extracted hash/migration 경계와 unavailable 안내를 포함해야 한다. API가 아직 없으므로 이 단계에서 컴파일 또는 테스트가 실패해야 한다.

- [ ] **Step 2: failing test를 실행해 실패 원인을 기록한다**

실행:

```bash
./gradlew :kotlin-text-processing:test --tests '*JapaneseBackendComparisonExamplesTest' --no-daemon --console=plain
```

예상 결과: 새 함수·타입이 없어 compile/test가 실패한다. 실패 로그는 구현 후 같은 명령이 PASS로 바뀌었는지 비교할 근거로 남긴다.

- [ ] **Step 3: 실제 dictionary-backed integration test를 작성한다**

`JapaneseBackendComparisonIntegrationTest`에는 다음 tag와 live fixture를 둔다.

```kotlin
@Tag("sudachi-integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JapaneseBackendComparisonIntegrationTest {

    private lateinit var reports: List<JapaneseBackendComparisonReport>

    @BeforeAll
    fun prepareReports() {
        reports = runJapaneseBackendComparisons()
    }

    @Test
    fun `prepared dictionary executes all approved corpus observations`() {
        reports.forEach { report ->
            report.candidate.execution shouldBeEqualTo BackendExecution.LIVE
            report.candidate.tokens.shouldNotBeEmpty()
            report.candidate.splitModes.map { it.mode } shouldBeEqualTo listOf("A", "B", "C")
            report.candidate.splitModes.forEach { it.surfaces.shouldNotBeEmpty() }
            report.candidate.posMapping shouldBeEqualTo PosMappingStatus.MAPPED
            report.candidate.statusMessage shouldBeEqualTo null
        }
    }

    @Test
    fun `prepared dictionary preserves official split fixtures`() {
        val election = reports.first { it.input == "選挙管理委員会" }
        election.candidate.splitModes.first { it.mode == "A" }.surfaces shouldBeEqualTo
            listOf("選挙", "管理", "委員", "会")
        election.candidate.splitModes.first { it.mode == "B" }.surfaces shouldBeEqualTo
            listOf("選挙", "管理", "委員会")
        election.candidate.splitModes.first { it.mode == "C" }.surfaces shouldBeEqualTo
            listOf("選挙管理委員会")

        val tokyo = reports.first { it.input == "東京都へ行く" }
        tokyo.candidate.splitModes.first { it.mode == "B" }.surfaces shouldBeEqualTo
            listOf("東京都", "へ", "行く")

        val foreign = reports.first { it.input == "外国人参政権" }
        foreign.candidate.splitModes.first { it.mode == "A" }.surfaces shouldBeEqualTo
            listOf("外国", "人", "参政", "権")
        foreign.candidate.splitModes.first { it.mode == "C" }.surfaces shouldBeEqualTo
            listOf("外国人参政権")
    }
}
```

두 integration test는 기본 `test`에 들어가지 않고 `sudachiTest`에서만 실행된다. 한 integration invocation은 하나의 열린 dictionary/tokenizer session으로 세 corpus를 모두 처리하고 session 종료를 보장한다. 이 구조로 corpus마다 217 MB dictionary를 다시 열지 않으며, 각 C-mode morpheme 목록을 한 번만 만들어 C surface와 POS observation에 함께 사용한다. 위의 세 문장 fixture는 upstream 결과와의 drift를 잡는다.

- [ ] **Step 4: 단위 테스트를 다시 실행해 red 상태를 확인한다**

`./gradlew :kotlin-text-processing:test --tests '*JapaneseBackendComparisonExamplesTest' --no-daemon --console=plain`이 구현 전에는 실패하고, dependency cache가 준비된 뒤 `--offline`을 붙여도 dictionary archive 다운로드·추출 없이 PASS해야 한다.

## Task 3: observation model과 tokenizer 실행을 최소 구현한다

**File:** `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/tokenizer/JapaneseBackendComparisonExamples.kt`

- [ ] **Step 1: 내부 report 계약을 구현한다**

다음 타입을 `internal`로 정의하고, reader-facing KDoc은 Korean으로 작성한다. `statusMessage: String?`는 candidate가 `UNAVAILABLE`일 때 원인·복구 명령을 담고 live 실행에서는 `null`이다.

```kotlin
/** tokenizer backend가 실제 실행되었는지 또는 준비되지 않았는지를 나타낸다. */
internal enum class BackendExecution { LIVE, UNAVAILABLE }
/** 두 backend의 POS 체계를 동일하다고 판정하지 않고 observation field만 채웠는지 나타낸다. */
internal enum class PosMappingStatus { MAPPED, UNMAPPED }

internal data class JapaneseTokenObservation(
    val surface: String,
    val partOfSpeech: String,
)

internal data class CandidateSplitModeObservation(
    val mode: String,
    val surfaces: List<String>,
)

internal data class JapaneseBackendObservation(
    val backend: String,
    val dictionary: String,
    val license: String,
    val runtimeFootprint: String,
    val gradleDependency: String,
    val dictionaryVersion: String,
    val dictionaryArchiveSha256: String,
    val dictionarySha256: String,
    val dictionarySize: Long,
    val execution: BackendExecution,
    val posMapping: PosMappingStatus,
    val tokens: List<JapaneseTokenObservation>,
    val splitModes: List<CandidateSplitModeObservation>,
    val statusMessage: String?,
)

internal data class JapaneseBackendComparisonReport(
    val input: String,
    val current: JapaneseBackendObservation,
    val candidate: JapaneseBackendObservation,
)
```

- [ ] **Step 2: 승인 corpus와 Kuromoji observation을 구현한다**

`comparisonCorpus()`는 승인된 세 문장을 고정 순서로 반환한다.
`runJapaneseBackendComparisons(texts: List<String> = comparisonCorpus())`는
각 입력을 먼저 검증하고 Kuromoji는 기존 `JapaneseProcessor.tokenize(text)`를
호출해 `token.surface`와 `token.allFeaturesArray.firstOrNull().orEmpty()`만
중립 observation에 복사한다. 기존 `MultilingualSearchIndex` 또는 그
fixture는 호출·수정하지 않는다. 단일 입력 helper
`runJapaneseBackendComparison(text: String = "選挙管理委員会")`는 위 runner에
한 문장만 전달한 뒤 `.single()`을 반환해 실행 계약을 중복하지 않는다.

- [ ] **Step 3: Sudachi property 경계와 DictionaryFactory 실행을 구현한다**

다음 실행 순서를 지킨다.

1. `bluetape4k.sudachi.system-dictionary`가 blank이면
   `UNAVAILABLE` observation을 반환한다.
2. property path는 `user.dir/build/sudachi-dictionary/v20260428/system_core.dic`
   정규화 경로와 정확히 일치해야 한다. 부모와 파일에 symlink가 있거나
   `NOFOLLOW_LINKS` 기준 regular file이 아니거나 `217_374_303L`과 크기가
   다르거나 `SUDACHI_DICTIONARY_SHA256`과 digest가 다르면 dictionary를
   열지 않고 절대 경로 없는 `UNAVAILABLE` 원인을 반환한다.
3. 유효한 path만
   `DictionaryFactory().create(Config.defaultConfig(PathAnchor.filesystem(path.parent)).systemDictionary(path))`
   로 열고 `use`로 닫는다. `runJapaneseBackendComparisons`는 세 corpus를
   처리하는 동안 이 dictionary와 tokenizer session을 하나만 열고, block
   종료 시 닫는다.
4. 하나의 tokenizer session에서 `Tokenizer.SplitMode.A/B/C`의 morpheme
   목록을 각각 한 번씩 얻고 surface 목록을 저장한다.
5. C mode에서 얻은 동일 morpheme 목록을 다시 tokenize하지 않고
   `partOfSpeech().firstOrNull().orEmpty()`와 token surface를 함께
   `JapaneseTokenObservation`으로 만든다.
6. live candidate metadata에는 `SudachiDict v20260428 core`, archive
   digest, extracted digest, `217_374_303L`, `libs.sudachi`
   (`com.worksap.nlp:sudachi:0.8.0`), Apache-2.0, `local build directory`
   footprint를 기록한다. current Kuromoji에는 archive/extracted digest가
   `not applicable`임을 명시한다.

path 검증 실패는 기본 test를 깨뜨리지 않는 observation 상태로만 변환하고,
승인 corpus 검증 실패는 예외로 남긴다. 예외와 status 메시지는 raw
dictionary content 또는 절대 로컬 경로를 포함하지 않는다. property 경계의
`Path.of`/`InvalidPathException`, `Files.size`의 `IOException`/`SecurityException`
만 좁게 `UNAVAILABLE`로 변환하고, 유효 path 이후의 `DictionaryFactory` 생성
또는 tokenize 예외는 숨기지 않고 integration failure로 보존한다.

- [ ] **Step 4: renderer와 단위 테스트를 green으로 만든다**

`renderJapaneseBackendComparison`은 current/candidate backend, license,
Gradle alias, dictionary version/archive hash/extracted hash/size, execution,
POS mapping, token surface/POS, A/B/C surfaces, `statusMessage`(있을 때),
그리고 “migration 전에 동일 corpus의 mode별 surface/POS 불일치를 검토한다”는
문장을 고정된 key-value 줄로 출력한다. Task 2의 offline 테스트를 실행해
PASS를 확인한다.

## Task 4: dictionary 준비와 통합 실행을 검증한다

**Files:**
- Modify: `kotlin/text-processing/build.gradle.kts`
- Test: `kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/tokenizer/JapaneseBackendComparisonIntegrationTest.kt`

- [ ] **Step 1: dependency resolution과 task graph를 확인한다**

```bash
./gradlew :kotlin-text-processing:dependencyInsight \
  --dependency com.worksap.nlp:sudachi \
  --configuration runtimeClasspath \
  --no-daemon --console=plain
./gradlew :kotlin-text-processing:tasks --all --no-daemon --console=plain
./gradlew :kotlin-text-processing:test --dry-run --no-daemon --console=plain
./gradlew :kotlin-text-processing:sudachiTest --dry-run --no-daemon --console=plain
```

`dependencyInsight`에서 `com.worksap.nlp:sudachi:0.8.0`이 local catalog
version으로 선택되고, `prepareSudachiDictionary`와 `sudachiTest`가
표시되며, 기본 `test` 실행 전에 preparation이 연결되지 않아야 한다.
`test --dry-run` 출력에는 `prepareSudachiDictionary`가 없어야 하고,
`sudachiTest --dry-run` 출력에는 `prepareSudachiDictionary`가
`sudachiTest`보다 먼저 있어야 한다.

- [ ] **Step 2: 공식 archive를 준비하고 파일 경계를 확인한다**

```bash
./gradlew :kotlin-text-processing:prepareSudachiDictionary --no-daemon --console=plain
test -f kotlin/text-processing/build/sudachi-dictionary/v20260428/system_core.dic
test "$(wc -c < kotlin/text-processing/build/sudachi-dictionary/v20260428/system_core.dic | tr -d ' ')" = 217374303
shasum -a 256 kotlin/text-processing/build/sudachi-dictionary/v20260428/system_core.dic
test ! -e kotlin/text-processing/build/sudachi-dictionary/v20260428/system_core.dic.part
test ! -e kotlin/text-processing/build/sudachi-dictionary/v20260428/sudachi-dictionary-20260428-core.zip.part
./gradlew :kotlin-text-processing:prepareSudachiDictionary --offline --info \
  --configuration-cache --configuration-cache-problems=fail --no-daemon --console=plain
```

첫 실행은 72 MB archive 다운로드와 217 MB 추출을 수행하고, 두 번째 실행은
archive/dictionary digest와 크기만 확인해 `UP-TO-DATE`가 되어야 한다. 두 번째
실행은 `--offline`으로 dictionary archive에 네트워크가 필요 없음을 증명하고,
configuration cache를 재사용하며 problems를 보고하지 않아야 한다. 첫 실행도
다음 명령으로 configuration-cache 문제를 fail-fast 검증한다.

```bash
./gradlew :kotlin-text-processing:prepareSudachiDictionary \
  --configuration-cache --configuration-cache-problems=fail --no-daemon --console=plain
```

실패 시 archive 크기·SHA-256·LEGAL·LICENSE-2.0.txt·승인 entry·extracted
SHA-256 중 어떤 검증이 실패했는지 확인하고, 유효하지 않은 dictionary를
통합 테스트에 넘기지 않는다. 네트워크가 차단된 환경에서는 이 단계만 실패할
수 있으며 기본 `test`는 계속 PASS해야 한다. 준비 task와 `sudachiTest`는
test-mutex를 공유해 다른 real-IO 검사와 병렬 실행하지 않는다.

- [ ] **Step 3: `sudachiTest`를 단독 실행한다**

```bash
./gradlew :kotlin-text-processing:sudachiTest --no-build-cache --no-daemon --console=plain
```

예상 결과: preparation이 up-to-date 또는 검증 완료되고
`sudachi-integration` test가 PASS한다. 준비된 dictionary 파일을 같은 크기의
변조 내용으로 교체한 뒤 `sudachiTest`가 input fingerprint와 digest 차이로
재실행하는지 확인하고, symlink dictionary·symlink 부모 디렉터리 fixture가
task를 실패시키는지 확인한다. 실제 실행 report에서 A/B/C surfaces와 C POS가
비어 있지 않은지 확인한다.

## Task 5: README 두 locale를 같은 계약으로 갱신한다

**Files:**
- Modify: `kotlin/text-processing/README.md`
- Modify: `kotlin/text-processing/README.ko.md`

- [ ] **Step 1: 두 README에 같은 위치와 항목으로 비교 예제를 추가한다**

다국어 검색 섹션 뒤에 `Japanese tokenizer backend comparison`/`일본어 tokenizer backend 비교` 섹션을 추가한다. 양쪽에 다음 내용을 같은 순서로 둔다.

1. `runJapaneseBackendComparisons()`를 우선 사용해
   `選挙管理委員会`, `東京都へ行く`, `外国人参政権`을 한 dictionary session에서
   비교하고, 단일 입력이 필요할 때만 `runJapaneseBackendComparison(text)`를
   사용한다. 두 helper 모두 Kuromoji IPADic과 Sudachi JVM의
   surface/POS/A/B/C 차이를 observation으로 남긴다는 설명.
2. `bluetape4k.sudachi.system-dictionary` property가 없으면 기본 `test`에서 Sudachi가 `UNAVAILABLE`이며 `prepareSudachiDictionary` 안내를 반환한다는 설명.
3. 다음 명령:

```bash
./gradlew :kotlin-text-processing:test
./gradlew :kotlin-text-processing:sudachiTest
```

4. dependency cache가 준비된 경우 기본 test를 `./gradlew --offline :kotlin-text-processing:test`로 실행할 수 있고, `sudachiTest`는 72 MB archive 다운로드와 217 MB 추출을 포함하는 local/manual-only 검증이라는 설명. hosted 기본 CI가 이를 실행한다고 암시하지 않는다.
5. 공식 `SudachiDict v20260428 core` URL, Apache-2.0 license, archive SHA-256/크기, extracted `system_core.dic` 크기/SHA-256이 모듈 기준
   `kotlin/text-processing/build/sudachi-dictionary/v20260428`에만 저장되고
   binary는 commit하지 않는다는 설명. 저장 위치를 저장소 root의
   `build/`와 혼동하지 않도록 모듈 기준임을 함께 적는다.
6. Sudachi `Tokenizer.SplitMode.A/B/C`와 C mode의 첫 broad POS field를 관찰값으로만 기록하며 정확도·latency 우위를 주장하지 않는다는 migration 경계.

- [ ] **Step 2: dependency snippet과 locale parity를 검증한다**

English README에는 `implementation(libs.sudachi)`를, Korean README에는 동일 alias를 포함한다. headings, 명령, URL, 숫자, hash, API identifier를 대조하고 Korean 자연스러움 감사와 `git diff --check`를 실행한다. 새 시각 자료는 cognitive load를 줄이지 않으므로 추가하지 않는다.

## Task 6: 모듈 회귀·정적 검증을 실행한다

- [ ] **Step 1: 기본 text-processing test와 build를 실행한다**

```bash
./gradlew :kotlin-text-processing:test --no-build-cache --no-daemon --console=plain
./gradlew :kotlin-text-processing:build --no-daemon --console=plain
```

기본 `test` 결과에는 Sudachi dictionary 다운로드/추출 로그가 없어야 하고, 기존 검색·탐지·redaction 테스트와 새 offline 테스트가 모두 PASS해야 한다. dependency cache가 없는 환경에서만 Maven resolution이 네트워크를 요구할 수 있으므로 “archive download 없이”라는 범위를 유지한다.

- [ ] **Step 2: diff·binary·문서 parity를 확인한다**

```bash
git diff --check
git status --short
git ls-files 'kotlin/text-processing/build/sudachi-dictionary/**'
```

마지막 명령은 빈 결과여야 하며, tracked binary 또는 `build/` dictionary가 없어야 한다. README 두 파일의 대응 섹션과 계획/설계의 URL·hash·size가 일치하는지 다시 읽는다.

- [ ] **Step 3: detekt를 변경 module 범위로 실행한다**

```bash
./gradlew :kotlin-text-processing:detekt --no-daemon --console=plain
```

Kotlin import/order, long line, exception boundary, internal API visibility 경고를 모두 해결한다. 실패를 단순 skip하지 않고 source 또는 검증 명령을 조정한다.

- [ ] **Step 4: 기존 hosted smoke 회귀 경계를 연결한다**

`.github/workflows/Examples.yml`의 `push`와 `pull_request` `paths`에
`kotlin/text-processing/**`를 추가하고, smoke job의 대표 기본 test 명령과
JUnit XML/report artifact 목록에 `:kotlin-text-processing:test` 및
`kotlin/text-processing/build/test-results/test/`,
`kotlin/text-processing/build/reports/tests/test/`를 추가한다. workflow
변경 자체는 기존 `changes` 분류로 `examples=true`를 활성화한다.
`scripts/smoke-validate.sh all-smoke`에도 같은 기본 test를 추가하고,
`sudachiTest`는 217 MB dictionary와 외부 archive가 필요하므로 이 hosted
경계에는 추가하지 않는다. workflow YAML과 shell syntax 검증 및
`./scripts/smoke-validate.sh all-smoke`에서 명령 목록을 read-back한다.

## Task 7: lesson과 review evidence를 작성한다

**File:** `docs/lessons/2026-09-03-issue-861-sudachi-japanese-comparison.md`

- [ ] **Step 1: Korean lesson에 context/decision/outcome/evidence를 기록한다**

다음 항목을 실제 결과로 채운다.

- Context: 기존 Kuromoji 예제와 Issue #861, upstream `bluetape4k-text` 근거.
- Decision: offline 기본 `test`와 별도 `sudachiTest`를 분리하고 공식 dictionary를 build-only로 준비한 이유.
- Outcome: 변경 파일, 선택된 `0.8.0`, 세 corpus, A/B/C 결과, README parity.
- Verification: 각 Gradle 명령, 통과한 test 수/결과, dependency insight, archive 검증값, `git diff --check`.
- Miss/surprise: dependency cache가 준비되지 않은 네트워크 없는 환경에서는 Maven resolution도 별도 전제라는 점, `sudachiTest`가 준비 단계에서 중단될 수 있다는 점, dictionary가 217 MB라는 운영 비용.
- Future guard: URL/archive hash/extracted hash/size/entry를 변경할 때 설계·task·README·통합 fixture를 함께 갱신하고 binary를 commit하지 않는 규칙. `sudachiTest`는 local/manual-only 경계로 유지한다.

- [ ] **Step 2: writer SPW-01..05와 Korean KO-01..07을 수행한다**

lesson을 source·test·official release와 대조해 용어 감사(`audit-korean-terms.mjs`), `git diff --check`, 최종 read-back을 수행한다. 근거 없는 정확도·latency 주장을 넣지 않는다.

## Task 8: Type-A 최종 검증과 delivery 전 체크

**Review artifact:** `docs/review/2026-09-03-issue-861-sudachi-japanese-comparison-plan-review.md`

- [ ] **Step 1: 여섯 관점 review에서 P0/P1=0을 확인한다**

성능은 tokenizer hot path와 dictionary 준비 비용, 안정성은 `use`/부분 archive/
오프라인 경계, 보안은 HTTPS redirect allowlist·archive/extracted SHA/ZIP
entry·path traversal·symlink, 운영은 build ownership·재실행·rollback·zero
test guard·workflow/smoke 연결, API는 internal contract와 기존 Kuromoji
호환성, 사용자 관점은 README 명령·모듈 기준 경로·오류 안내·misuse 경계를
검토한다. review artifact에는 여섯 lane의 실제 evidence와 P0/P1/P2/P3
처분을 표로 남기고 P0/P1=0일 때만 다음 단계로 진행한다. P2/P3는 수정,
문서화 또는 후속 이슈 중 하나로 처분한다.

- [ ] **Step 2: main verifier가 전체 DoD를 확인한다**

`test`, `sudachiTest`, `build`, `detekt`, dependency resolution, README parity,
workflow/smoke path, lesson, binary absence, `git diff --check` 결과를 exact
commit에 연결한다. `settings.gradle.kts`/module matrix/Kover aggregation
변경이 N/A이고, Examples workflow path 및 smoke 명령만 갱신했다는 근거도
확인한다.

- [ ] **Step 3: PR 전에 Korean commit과 branch 상태를 정리한다**

설계 커밋 `d888bfb5` 이후 구현·문서·lesson을 의미별로 작은 커밋으로 만들고 Lore trailers를 유지한다. PR 본문에는 Issue #861 링크, offline/integration 실행법, 공식 dictionary 검증값, 전체 DoD와 known gap을 Korean으로 기록한다. PR 생성 후에는 exact head의 hosted CI와 review thread를 다시 확인하며 merge는 별도 fresh `승인`을 받은 뒤에만 수행한다.

## Rollback과 재실행

- dependency resolution이 `com.worksap.nlp:sudachi:0.8.0`을 선택하지 않으면
  local external catalog alias와 cache를 먼저 확인한다. `io.github.bluetape4k`
  모듈의 version pinning이나 개별 text BOM import로 우회하지 않는다. 중앙
  BOM constraint가 필요하면 dependencies repository 후속 변경으로 분리한다.
- archive 검증이 실패하면 `.part`와 invalid output을 테스트 입력으로 사용하지 않고 preparation을 재실행한다.
- 기본 offline test가 실패하면 Sudachi 실행을 기존 Kuromoji 경로와 분리한 뒤 다시 실행한다. 기존 `MultilingualSearchIndex` API·fixture를 되돌려 수정하지 않는다.
- integration fixture가 공식 release와 달라지면 dictionary version/hash/size와 함께 fixture·README·lesson을 같은 변경으로 갱신한다.
- 사용자 승인 전에는 이 계획과 설계 문서만 커밋하고 source, test, Gradle, README를 수정하지 않는다.

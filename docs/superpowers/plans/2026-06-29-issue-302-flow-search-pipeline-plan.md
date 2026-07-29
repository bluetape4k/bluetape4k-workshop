# Flow 검색 파이프라인 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 이슈 #302를 새로운 인메모리 Flow search/autocomplete 워크숍 예시로 빌드합니다.

**아키텍처:** 버스트 버퍼링, 최신 설정, 세션 중지 및 디버그 로깅을 위한 Bluetape4k Flow 확장을 구성하는 집중 `SearchPipeline`을 사용하여 `kotlin/flow-extensions-search-pipeline`을 추가합니다. Bluetape4k `flatMapDrop`/`flatMapFirst`는 작업이 바쁜 동안 의도적으로 새로운 요청을 삭제하므로 진정한 자동 완성 대체 의미 체계를 위해 Kotlin 표준 `flatMapLatest`을 사용하세요.

**기술 스택:** Kotlin/JVM, Java 21, `bluetape4k-coroutines`, `bluetape4k-junit5`, `bluetape4k-assertions`, `kotlinx-coroutines-test`, CairoSVG-렌더링된 README 다이어그램.

---

## 파일 구조

- `kotlin/flow-extensions-search-pipeline/build.gradle.kts` 생성: 형제 Flow 모듈과 일치하는 종속성.
- `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchDomain.kt` 생성: 직렬화 가능한 도메인 값 및 열거형; 생성된 `copy(...)`이 유효성 검사를 우회하는 value/regular 클래스를 사용하세요.
- `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchAdapter.kt` 만들기: 명시적인 일시 중지 어댑터 경계.
- `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/FakeSearchAdapter.kt` 생성: examples/tests에 대한 결정적 일시 중지 검색 어댑터.
- `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchPipeline.kt`: Flow 확장 체인을 생성합니다.
- `kotlin/flow-extensions-search-pipeline/src/test/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchPipelineTest.kt` 생성: 승인 테스트.
- 테스트 리소스 `junit-platform.properties` 및 `logback-test.xml`를 생성합니다.
- `kotlin/flow-extensions-search-pipeline/README.md` 및 `README.ko.md`를 생성합니다.
- 루트 `README.md` 및 `README.ko.md` 비동기 및 반응형 테이블을 수정합니다.
- `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-*.svg/png` 아래에 README 다이어그램 자산을 만듭니다.
- 새 architecture/sequence 다이어그램이 추가되면 다이어그램 유효성 검사기 허용 목록을 수정합니다.
- `.github/workflows/Examples.yml` 및 `scripts/smoke-validate.sh`을 수정하여 이 테스트 베어링 인메모리 Flow 모듈이 연기 예제 적용 범위에 합류하도록 합니다.
- `docs/review/2026-06-29-issue-302-flow-search-pipeline-review.md`를 생성합니다.
- `docs/lessons/2026-06-29-issue-302-flow-search-pipeline.md`를 생성합니다.

## 작업 1: 모듈 뼈대 및 도메인

**복잡성:** 중간
**적용:** `$bluetape4k-code-patterns`

**파일:**
- 생성: `kotlin/flow-extensions-search-pipeline/build.gradle.kts`
- 생성: `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchDomain.kt`
- 생성: `kotlin/flow-extensions-search-pipeline/src/test/resources/junit-platform.properties`
- 생성: `kotlin/flow-extensions-search-pipeline/src/test/resources/logback-test.xml`

- [ ] Gradle 종속성을 추가합니다.

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.logback.lib)
}
```

- [ ] 생성자 수준 유효성 검사 및 수정된 문자열 렌더링을 사용하여 직렬화 가능한 도메인 모델을 추가합니다.

```kotlin
package io.bluetape4k.workshop.flow.search.pipeline

import java.io.Serializable
import java.util.Collections
import java.util.Locale
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank

enum class SearchMode {
    PREFIX,
    FUZZY,
    EXACT,
}

class SearchQuery private constructor(
    val text: String,
): Serializable {
    override fun toString(): String = "SearchQuery(text=<redacted>, length=${text.length})"

    override fun equals(other: Any?): Boolean =
        this === other || other is SearchQuery && text == other.text

    override fun hashCode(): Int = text.hashCode()

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(text: String): SearchQuery {
            val normalized = text.trim()
            normalized.requireNotBlank("text")
            normalized.length.requireInRange(1, 64, "text.length")
            return SearchQuery(normalized)
        }
    }
}

class SearchSettings private constructor(
    val tenantId: String,
    val locale: Locale,
    val mode: SearchMode,
    val featureFlags: Set<String>,
    val resultLimit: Int,
): Serializable {
    override fun toString(): String =
        "SearchSettings(tenant=<redacted>, locale=$locale, mode=$mode, flags=${featureFlags.size}, resultLimit=$resultLimit)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SearchSettings &&
            tenantId == other.tenantId &&
            locale == other.locale &&
            mode == other.mode &&
            featureFlags == other.featureFlags &&
            resultLimit == other.resultLimit

    override fun hashCode(): Int =
        listOf(tenantId, locale, mode, featureFlags, resultLimit).hashCode()

    companion object {
        private const val serialVersionUID: Long = 1L
        private val featureFlagPattern = Regex("[a-z][a-z0-9-]{1,31}")

        operator fun invoke(
            tenantId: String,
            locale: Locale,
            mode: SearchMode,
            featureFlags: Set<String>,
            resultLimit: Int,
        ): SearchSettings {
            val normalizedTenantId = tenantId.trim()
            val normalizedFlags = featureFlags.map { it.trim() }.toSet()

            normalizedTenantId.requireNotBlank("tenantId")
            normalizedTenantId.length.requireInRange(1, 64, "tenantId.length")
            resultLimit.requireInRange(1, 20, "resultLimit")
            require(normalizedFlags.size <= 8) { "featureFlags must contain at most 8 values" }
            require(normalizedFlags.all { it.matches(featureFlagPattern) }) {
                "featureFlags must be lowercase kebab-case names"
            }

            return SearchSettings(
                tenantId = normalizedTenantId,
                locale = locale,
                mode = mode,
                featureFlags = Collections.unmodifiableSet(normalizedFlags),
                resultLimit = resultLimit,
            )
        }
    }
}

data class SearchRequest(
    val query: SearchQuery,
    val settings: SearchSettings,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class SearchHit(
    val id: String,
    val title: String,
    val score: Int,
): Serializable {
    override fun toString(): String = "SearchHit(id=$id, title=<redacted>, score=$score)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class SearchResult(
    val request: SearchRequest,
    val hits: List<SearchHit>,
    val source: String,
): Serializable {
    override fun toString(): String =
        "SearchResult(query=<redacted>, tenant=<redacted>, hits=${hits.size}, source=<redacted>)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] 정규화를 공개 구성 경로의 일부로 만듭니다.
  - `SearchQuery` 및 `SearchSettings`에 대한 전용 생성자와 동반 `operator fun invoke(...)` 팩토리가 있는 일반 직렬화 가능 클래스를 사용하십시오. 생성된 `copy(...)`는 유효성 검사를 우회할 수 있으므로 이 두 가지 유형에는 `data class`을 사용하지 마세요.
  - 저장된 `SearchQuery.text` 및 `SearchSettings.tenantId` 값은 잘린 표준 값입니다.
  - `SearchSettings.featureFlags`은 정규화된 플래그의 방어적 불변 복사본이므로 호출자 소유의 변경 가능 세트를 변경해도 구성 후 설정을 변경할 수 없습니다.
  - README 예제는 유효한 제한과 플래그를 사용하여 `SearchSettings(tenantId = "demo-tenant", ...)`을 생성합니다.
- [ ] 여기에 `Serializable`이 저장소 규칙이라는 메모를 추가합니다. 이 예제에서는 신뢰할 수 없는 바이트를 역직렬화하지 않으며 `ObjectInputStream`을 가르치지 않습니다.
- [ ] 작은 Flow 모듈 패턴을 복사하여 표준 테스트 리소스를 추가합니다.
- [ ] 모듈이 존재하면 모듈 등록 확인을 실행합니다.

```bash
./gradlew projects --console=plain
```

예상 증거: `:kotlin-flow-extensions-search-pipeline`이 나타납니다.

## 작업 2: 파이프라인 테스트 실패

**복잡성:** 높음
**적용:** `$bluetape4k-code-patterns`, `$test-driven-development`

**파일:**
- 생성: `kotlin/flow-extensions-search-pipeline/src/test/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchPipelineTest.kt`

- [ ] 구현 전에 테스트를 추가합니다. 테스트 이름:
  - ``buffering debounce emits latest query from a typing burst``
  - ``large burst still starts one search``
  - ``latest settings are joined with each debounced query``
  - ``query before first settings emission is not searched``
  - ``query before settings is dropped permanently and next query uses settings snapshot``
  - ``settings flow failure propagates downstream``
  - ``newer query cancels stale in-flight search``
  - ``cancelled stale request cannot emit or fail downstream later``
  - ``session close cancels in-flight search``
  - ``session close source is collected once and shared across stop observers``
  - ``collector cancellation cleans up active search and stop observer``
  - ``upstream failure is propagated after buffered query handling``
  - ``blank input is ignored before search starts``
  - ``domain values reject invalid query settings and limits``
  - ``domain construction stores trimmed query and tenant values``
  - ``domain construction cannot bypass validation through copy``
  - ``settings defensively copy caller owned feature flags``
  - ``settings expose unmodifiable feature flags``
  - ``debounce duration must be positive``
  - ``regex looking query is treated as literal text``
  - ``debug string rendering hides query tenant flags and hit titles``
  - ``result limit caps hit materialization before returning hits``

- [ ] 가상 시간 친화적인 곳에서는 `io.bluetape4k.junit5.coroutines.runSuspendTest`을 사용하세요.
- [ ] `io.bluetape4k.assertions.assertFailsWith`, `shouldBeEqualTo`, `shouldHaveSize` 및 도트 호출 null/boolean 매처를 사용하세요.
- [ ] JUnit, AssertJ, Kluent 또는 `kotlin.test` 어설션을 사용하지 마세요.
- [ ] 취소 증거의 경우 절전 기반 어설션 대신 어댑터 로컬 `AtomicInteger`/`AtomicBoolean` 후크와 완료 게이트를 사용하세요. 승인 대상은 스트레스나 경합 테스트가 아니라 운영자가 하나의 파이프라인 컬렉션을 취소하는 것이므로 이 모듈에는 `SuspendedJobTester`가 필요하지 않습니다.
- [ ] 대규모 증거의 경우 하나의 디바운스 창에서 1,000개의 빠른 쿼리 값을 제공하고 절전 기반 타이밍 없이 정확히 하나의 어댑터request/result를 어설션합니다.
- [ ] 가상 시간 버스트 테스트의 경우 시간을 진행하기 전에 모든 버스트 값을 내보내고 넉넉한 디바운스 창을 사용한 다음 `advanceTimeBy` 및 `advanceUntilIdle`을 호출합니다. 벽시계 대기 또는 시간 제한 기반 어설션을 사용하지 마세요.
- [ ] 설정 우선 동작의 경우 첫 번째 설정 전에 쿼리를 내보낸 다음 설정을 내보내고 0 어댑터 호출을 어설션한 다음 새 쿼리를 내보내고 해당 설정 스냅샷을 사용하여 정확히 하나의 요청을 어설션합니다. README/KDoc에 시드 설정이 포함된 안전 설정을 표시합니다.
- [ ] 오래된 검색을 취소하려면 각 요청에 자체 어댑터 게이트를 제공하세요. 요청 A를 일시 중단하고, 요청 B를 시작하고, B가 방출되기 전에 A가 취소되었다고 검증한 다음, A를 재개하거나 실패하고 오래된 결과나 오래된 예외가 수집기에 도달하지 않는지 확인합니다.

## 작업 3: 어댑터 및 파이프라인 구현

**복잡성:** 높음
**적용:** `$bluetape4k-code-patterns`

**파일:**
- 생성: `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchAdapter.kt`
- 생성: `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/FakeSearchAdapter.kt`
- 생성: `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchPipeline.kt`

- [ ] `fun interface SearchAdapter { suspend fun search(request: SearchRequest): SearchResult }`을 정의합니다.
- [ ] `search(request: SearchRequest): SearchResult`을 일시 중지하여 `FakeSearchAdapter`을 구현합니다.
- [ ] `FakeSearchAdapter`은 고정된 인메모리 카탈로그와 일반 경계 문자열 일치만 사용합니다. 원시 입력, SQL과 유사한 DSL, 리플렉션, 스크립팅 또는 표현식 평가에서 컴파일된 정규식은 없습니다.
- [ ] `FakeSearchAdapter`은 `matches.asSequence().take(resultLimit).map(...).toList()` 또는 동등한 구체화 카운터 후크와 같은 제한된 시퀀스 경로를 사용하여 반환된 적중을 구체화하기 전에 `request.settings.resultLimit`을 적용합니다.
- [ ] `FakeSearchAdapter`은 시뮬레이션된 대기 시간에 코루틴 `delay`을 사용하며 `Thread.sleep`은 절대 사용하지 않으며 향후 차단 작업이 `withContext(Dispatchers.IO)` 뒤에 속하는 문서를 사용합니다.
- [ ] catch 블록이 필요한 경우 광범위한 예외 처리 전에 `CancellationException`을 다시 던집니다.
- [ ] `SearchPipeline`를 `class SearchPipeline(private val adapter: SearchAdapter)`로 구성합니다.
- [ ] 공개 도메인 유형인 `SearchAdapter`, `FakeSearchAdapter` 및 `SearchPipeline.search(...)`에 대한 영어 KDoc을 추가합니다(설정 우선 방출 및 취소 계약 포함).
- [ ] `SearchPipeline.search(...)` 구현:

```kotlin
fun search(
    queries: Flow<String>,
    settings: Flow<SearchSettings>,
    sessionClosed: Flow<Unit>,
    debounce: Duration,
): Flow<SearchResult>
```

- [ ] API 경계에서 `debounce`을 확인합니다.
  - `debounce`은(는) 양수여야 합니다.
  - 요청 마감일이 범위를 벗어났습니다. 세션 닫기 및 수집기 취소가 유일한 취소 경계입니다.
- [ ] 어댑터 레이스와 외부 터미널 가드 모두에서 사용하기 전에 컬렉션당 한 번씩 `sessionClosed`을 공유 중지 신호로 정규화합니다.
  - 콜드 `sessionClosed` 소스는 한 번만 수집해야 합니다.
  - 단일 닫기 이벤트는 활성 검색을 취소하고 어느 관찰자도 놓치지 않고 다운스트림을 종료해야 합니다.
  - 구현에서는 `channelFlow`/`shareIn`/구조화된 하위 코루틴을 사용할 수 있지만 파이프라인 컬렉션당 하나의 `sessionClosed` 컬렉션을 유지해야 합니다.
- [ ] 체인 연산자는 개념적으로 다음 순서로 되어 있습니다.

```kotlin
queries
    .map(String::trim)
    .filter(String::isNotBlank)
    .bufferingDebounce(debounce)
    .mapNotNull { burst -> burst.lastOrNull()?.let(::SearchQuery) }
    .withLatestFrom(settings) { query, latestSettings -> SearchRequest(query, latestSettings) }
    .flatMapLatest { request -> searchUntilSessionClosed(request, sharedSessionClosed) }
    .takeUntil(sharedSessionClosed)
    .log("search-pipeline")
```

- [ ] `io.bluetape4k.coroutines.flow.extensions`에서 Bluetape4k 확장을 가져옵니다.
- [ ] Kotlin 표준 `flatMapLatest`을 의도적으로 사용하고 KDoc/README에 그 이유를 설명하세요.
- [ ] 명시적으로 구조화된 `coroutineScope`/`select` 취소 인식 경주를 사용하여 `searchUntilSessionClosed(request, sharedSessionClosed)`을 구현합니다.
  - 하위 코루틴에서 `adapter.search(request)`을 시작합니다.
  - 형제자매의 `sharedSessionClosed.first()`을 관찰하세요. 이 도우미는 원본 `sessionClosed` Flow을 캡처하거나 수집해서는 안 됩니다.
  - `sessionClosed`이(가) 이기면 하위 검색이 반환되기 전에 취소하고 결과를 내보내지 않고 완료합니다.
  - 검색이 성공하면 결과를 내보내고 세션 관찰자를 취소합니다.
  - 어댑터 검색이 실패하면 원래 실패를 전파하고 세션 관찰자를 취소합니다.
  - 검색이 일시 중지된 동안 수집기가 취소하면 하위 검색과 세션 관찰자가 모두 취소됩니다.
  - `try/finally`을 사용하여 잃어버린 자식을 취소하고 반환하기 전에 패자 완료를 기다립니다.
  - `CancellationException` 다시 던지기; 일시 중지된 호출에는 `runCatching`을 사용하지 마세요.
- [ ] 이미 일시 중단된 어댑터 호출이 취소되었다는 증거가 아니라 `takeUntil(sharedSessionClosed)`을 외부 터미널 가드로 유지하십시오.
- [ ] `toString()` 출력이 수정된 값에 대해서만 결과 생성 후에 `Flow.log("search-pipeline")`을 유지합니다. 디바운스 전이나 원시 쿼리 스트림으로 이동하지 말고 원시 쿼리 텍스트, 테넌트 ID, 기능 플래그, 소스 메타데이터 또는 적중 제목을 기록하지 마세요.
- [ ] README는 `Flow.log()`이 demo/debug 계측이므로 핫 프로덕션 경로에서 제거되거나 보호되어야 하며 대기 시간 비교에서 제외되어야 함을 참고합니다.

## 작업 4: README 쌍 및 루트 인덱스

**복잡성:** 중간
**적용:** `$bluetape4k-blog`, `$bluetape4k-diagram`

**파일:**
- 생성: `kotlin/flow-extensions-search-pipeline/README.md`
- 생성: `kotlin/flow-extensions-search-pipeline/README.ko.md`
- 수정: `README.md`
- 수정: `README.ko.md`

- [ ] README.md는 `# Flow Extensions Search Pipeline` 및 `English | [한국어](README.ko.md)` 언어 전환으로 시작됩니다.
- [ ] README.ko.md는 `# Flow Extensions Search Pipeline` 및 `[English](README.md) | 한국어` 언어 전환으로 시작됩니다.
- [ ] 섹션 포함:
  - 대본
  - 범위 및 지원되지 않는 기능
  - 이전: 수동 `MutableSharedFlow` 및 `Job` 취소, 안티패턴 대비로 명시적으로 레이블 지정
  - 이후: Flow 확장 체인
  - 건축학
  - 도메인 모델
  - 시퀀스 모델
  - 왜 `flatMapDrop`이 아니고 `flatMapLatest`인가요?
  - 설정 스트림 전제조건
  - 진단 및 작동 참고사항
  - 사용된 Bluetape4k 기능
  - 빌드 및 테스트
  - 참고자료
- [ ] 사용된 기능 테이블에는 기능, 아티팩트, 코드 참조, 이점 열이 있습니다.
- [ ] 범위 섹션 상태: HTTP/WebSocket 없음, DB/cache 없음, auth/authz 없음, 프로덕션 순위 없음, 분산 취소 프로토콜 없음, 가짜 어댑터만 및 신뢰할 수 없는 개체 역직렬화 없음.
- [ ] Before/After 대조는 수동 접근 위험을 명명합니다: 변경 가능한 설정 경쟁, 수동 취소 누출, 취소를 삼키는 광범위한 캐치 블록.
- [ ] 설정 섹션에는 `MutableStateFlow(initialSettings)`이 표시되고 시드되지 않은 설정으로 인해 `withLatestFrom`이(가) 초기 쿼리를 삭제하게 되는 이유가 설명되어 있습니다.
- [ ] 진단 섹션에서는 `search-pipeline` 로그 태그, 수정된 로그 값, 예상되는 취소 증거, 디버그 로깅 오버헤드 및 리소스 소유권 없음 롤백을 다룹니다.
- [ ] 빌드 및 테스트 섹션에는 정확한 명령이 포함됩니다.

```bash
./gradlew :kotlin-flow-extensions-search-pipeline:test --rerun-tasks --console=plain
./gradlew :kotlin-flow-extensions-search-pipeline:compileKotlin :kotlin-flow-extensions-search-pipeline:compileTestKotlin --console=plain
./gradlew projects --console=plain
```

- [ ] 루트 Async & Reactive 테이블을 기본, 인메모리, `coroutines` + `junit5`로 등록합니다.
- [ ] 우리말 README는 원천동등하고 자연스러운 우리말 기술산문이다.

## 작업 5: 다이어그램 자산

**복잡성:** 높음
**적용:** `$bluetape4k-diagram`

**파일:**
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-scenario-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-architecture-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-erd-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-sequence-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-contact-sheet-01.png`
- 수정: `scripts/validate-readme-architecture-diagrams.mjs`
- 수정: `scripts/validate-sequence-diagrams.mjs`

- [ ] 영어 라벨과 소스 기반 domain/operator 이름을 사용하세요.
- [ ] 기술 아이콘이 필요한 경우 wiki/catalog 아이콘을 사용하세요. Kotlin의 경우 `bluetape4k-wiki`보다 `docs/icons/languages/kotlin.svg`를 선호합니다.
- [ ] 다음을 사용하여 모든 SVG을 렌더링합니다.

```bash
~/.local/bin/cairosvg <svg> -o <png> -s 2
```

- [ ] SVG XML 구문 분석을 실행합니다.
- [ ] 아키텍처 및 시퀀스 다이어그램에 대한 기하학 및 엔드포인트 감사를 실행합니다.
- [ ] `view_image`을 사용하여 터치된 모든 전체 크기 PNG를 검사합니다.
- [ ] 전체 크기 검사 후 접착 시트를 검사합니다.
- [ ] 저장소 유효성 검사기를 실행합니다.

```bash
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
```

## 작업 6: 워크플로 적용 범위

**복잡성:** 낮음
**적용:** `$bluetape4k-code-patterns`

**파일:**
- 수정: `.github/workflows/Examples.yml`
- 수정: `scripts/smoke-validate.sh`

- [ ] 달리다:

```bash
rg "flow-extensions-search-pipeline|:kotlin-flow-extensions-search-pipeline:test" .github/workflows/
```

- [ ] 예제 워크플로 범위 추가:
  - 경로 필터 `kotlin/flow-extensions-search-pipeline/**`
  - Gradle 연기 작업 `:kotlin-flow-extensions-search-pipeline:test`
  - 테스트 결과에 대한 아티팩트 경로
  - `scripts/smoke-validate.sh`에서 연기 작업 일치
- [ ] 달리다:

```bash
actionlint .github/workflows/Examples.yml
rg -n "\\\\'" .github/workflows
```

## 작업 7: 검증

**복잡성:** 중간
**적용:** `$bluetape4k-code-patterns`, `$verification-before-completion`

**파일:**
- 터치된 모든 파일.

- [ ] 모듈 테스트를 실행합니다.

```bash
./gradlew :kotlin-flow-extensions-search-pipeline:test --rerun-tasks --console=plain
```

- [ ] 컴파일 검사를 실행합니다:

```bash
./gradlew :kotlin-flow-extensions-search-pipeline:compileKotlin :kotlin-flow-extensions-search-pipeline:compileTestKotlin --console=plain
```

- [ ] 등록 실행:

```bash
./gradlew projects --console=plain
```

- [ ] README 유효성 검사기를 실행합니다.

```bash
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
```

- [ ] `git diff --check`를 실행하세요.

## 작업 8: 검토, 강의, 커밋, PR

**복잡성:** 중간
**적용:** `$bluetape4k-workflow`

**파일:**
- 생성: `docs/review/2026-06-29-issue-302-flow-search-pipeline-review.md`
- 생성: `docs/lessons/2026-06-29-issue-302-flow-search-pipeline.md`

- [ ] 구현 차이점에 대해 6-R 단계 local/native 7계층 검토를 실행하고 P0/P1 = 0을 기록합니다.
- [ ] 최소한 다음 내용을 포함하여 수업 노트를 작성하세요.
  - `flatMapDrop` 대 `flatMapLatest` 의미 불일치
  - 자동 완성 예제에 대한 결정적 취소 테스트
- [ ] Lore 예고편으로 구현을 커밋합니다.
- [ ] 분기를 푸시합니다.
- [ ] PR 만들기:

```bash
gh pr create --title "feat: add Flow search pipeline workshop" --body-file /tmp/pr-body.md --assignee debop
```

- [ ] 미러 이슈 #302 마일스톤 및 PR의 라벨.
- [ ] 라이브 PR 본문 최종 `##` 섹션이 `## DoD Status`인지 확인합니다.
- [ ] CI 및 예제 확인을 기다립니다. CI 다음에 PR 본문 DoD을 업데이트하세요.

## 자체 검토

- 사양 범위: 모든 #302 허용 기준은 작업 2-7에 매핑됩니다.
- 빈 마커 스캔: 해결되지 않은 마커가 남아 있지 않습니다.
- 유형 일관성: `SearchQuery`, `SearchSettings`, `SearchRequest`, `SearchHit`, `SearchResult`, `FakeSearchAdapter` 및 `SearchPipeline.search(...)` 이름은 작업 전체에서 일관됩니다.
- 위험 처리: 의미 체계 대체, 취소, 첫 번째 실행 동작 설정 및 다이어그램 QA이 명시적입니다.

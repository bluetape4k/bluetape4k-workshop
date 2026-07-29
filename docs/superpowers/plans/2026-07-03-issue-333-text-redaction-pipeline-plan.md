# 텍스트 교정 파이프라인 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 이슈 #333에 대해 결정적이고 감사에 안전한 민감한 텍스트 수정 파이프라인을 사용하여 `kotlin/text-processing`을 확장합니다.

**아키텍처:** 기존 텍스트 처리 모듈 내에 집중된 `io.bluetape4k.workshop.text.redaction` 패키지를 추가합니다. 파이프라인은 입력을 검증하고, 유니코드를 NFC로 정규화하고, 텍스트 처리 언어 감지 및 Aho-Corasick 유틸리티를 재사용하고, 민감한 범위를 결정론적으로 병합하고, 원시 값이 없는 내부 메타데이터를 반환하고, README diagrams/docs 및 CI 연기 범위를 업데이트합니다.

**기술 스택:** Kotlin/JVM, bluetape4k-text search/Lingua, bluetape4k 유효성 검사 도우미, bluetape4k 로깅, JUnit 5, bluetape4k-assertions, bluetape4k-junit5 `MultithreadingTester`, Logback `ListAppender`, CairoSVG, repo-local 다이어그램 QA, GitHub 작업 예시 워크플로.

---

## 파일 맵

- `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/redaction/SensitiveTextRedactionPipeline.kt` 생성: 공개 값 유형, 정책 컴파일, 범위 감지, 수정 및 안전한 로깅.
- 수정, 메타데이터 안전, 중복 동작, 유효성 검사, 로깅 및 스레드 안전을 위한 `kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/redaction/SensitiveTextRedactionPipelineTest.kt`: TDD 테스트를 만듭니다.
- `kotlin/text-processing/build.gradle.kts` 수정: `testRuntimeOnly(libs.logback.lib)`을 `testImplementation(libs.logback.lib)`로 변경하여 Logback `ListAppender` 테스트 코드를 컴파일합니다.
- `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/detection/LanguageDetectionService.kt` 수정: 디버그 로그에서 원시 소스 텍스트를 제거합니다.
- `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/normalize/TextNormalizer.kt` 수정: 디버그 로그에서 원시 source/normalized text/keyword 값을 제거합니다.
- `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/filter/AbuseWordFilter.kt` 수정: 디버그 로그에서 원시 source/filtered 텍스트를 제거합니다.
- `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/search/MultilingualSearchIndex.kt` 수정: 동일한 모듈의 디버그 로그에서 원시 query/text 값을 제거합니다.
- `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/search/CoroutineMultilingualSearchIndex.kt` 수정: 코루틴 검색 디버그 로그에서 원시 query/text 값을 제거하여 동기화 및 코루틴 예제가 동일한 감사 안전 로깅 규칙을 따르도록 합니다.
- `kotlin/text-processing/README.md` 및 `README.ko.md` 수정: 수정 파이프라인 사용, 제한, 메타데이터 지침 및 종속성 참고 사항을 추가합니다.
- `docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.svg/png` 및 `docs/images/readme-diagrams/kotlin-text-processing-scenario-01.svg/png` 수정: 수정 경로를 추가합니다.
- `.github/workflows/Examples.yml` 수정: `kotlin/text-processing/**` 경로 필터, `:kotlin-text-processing:test` 및 아티팩트 경로를 추가합니다.
- `scripts/smoke-validate.sh` 수정: `all-smoke`에 `:kotlin-text-processing:test`을 추가합니다.
- `docs/review/2026-07-03-issue-333-text-redaction-pipeline-code-review.md` 작성: 6-R단계 증거를 검토합니다.
- `docs/lessons/2026-07-03-issue-333-text-redaction-pipeline.md` 만들기: PR 전에 7단계 수업 증거.

## 작업 1: 빨간색으로 실패한 수정 테스트 추가

**복잡성:** 높음
**스킬:** `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-testing`

**파일:**

- 수정: `kotlin/text-processing/build.gradle.kts`
- 생성: `kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/redaction/SensitiveTextRedactionPipelineTest.kt`

- [ ] **1단계: 로그백 캡처를 위한 테스트 컴파일 종속성 추가**

테스트 파일을 작성하기 전에 기존 테스트 종속성을 런타임 전용에서 컴파일 가능으로 변경합니다.

```kotlin
testImplementation(libs.logback.lib)
```

예상: `SensitiveTextRedactionPipelineTest`은 `Logger`, `ILoggingEvent` 및 `ListAppender`을 가져올 수 있으므로 첫 번째 RED 증명은 누락된 Logback 클래스 대신 누락된 수정 API에서 실패합니다.

- [ ] **2단계: 테스트 클래스 및 고정 장치 상수 만들기**

`@TestInstance(TestInstance.Lifecycle.PER_CLASS)`, `bluetape4k-assertions` 및 reserved/synthetic 입력만 사용하세요.

```kotlin
package io.bluetape4k.workshop.text.redaction

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.measureTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SensitiveTextRedactionPipelineTest {
    private val pipeline = SensitiveTextRedactionPipeline.default()

    private val email = "user@example.test"
    private val phone = "555-010-1234"
    private val token = "token=demo_token_value_123456"
    private val keyword = "account number"
}
```

- [ ] **3단계: 결정적 수정 테스트 추가**

```kotlin
@Test
fun `redacts email phone token and configured keyword deterministically`() {
    val input = "Contact $email at $phone with $token for $keyword review."
    val expected = "Contact ${"*".repeat(email.length)} at ${"*".repeat(phone.length)} " +
        "with ${"*".repeat(token.length)} for ${"*".repeat(keyword.length)} review."

    val result = pipeline.redact(input)
    val repeated = pipeline.redact(input)

    result.redactedText.length shouldBeEqualTo input.length
    result.redactedText shouldBeEqualTo expected
    result.redactedText.startsWith("Contact ").shouldBeTrue()
    result.redactedText.endsWith(" review.").shouldBeTrue()
    repeated shouldBeEqualTo result
    result.redactedText shouldNotContain email
    result.redactedText shouldNotContain phone
    result.redactedText shouldNotContain token
    result.redactedText shouldNotContain keyword
    result.spans.map { it.range.startInclusive } shouldBeEqualTo result.spans.map { it.range.startInclusive }.sorted()
    result.spans.map { it.category } shouldBeEqualTo listOf("contact", "contact", "secret", "keyword")
    result.spans.map { it.ruleIds.single() } shouldBeEqualTo listOf("email", "phone", "token", "support-keyword")
    result.spans.map { it.matchedLength } shouldBeEqualTo listOf(email.length, phone.length, token.length, keyword.length)
    result.spans.map { it.range.startInclusive } shouldBeEqualTo listOf(
        input.indexOf(email),
        input.indexOf(phone),
        input.indexOf(token),
        input.indexOf(keyword),
    )
    result.spans.map { it.range.endExclusive } shouldBeEqualTo listOf(
        input.indexOf(email) + email.length,
        input.indexOf(phone) + phone.length,
        input.indexOf(token) + token.length,
        input.indexOf(keyword) + keyword.length,
    )
}
```

- [ ] **4단계: 메타데이터 추가 및 `toString()` 안전성 테스트**

```kotlin
@Test
fun `metadata and toString do not expose raw sensitive values`() {
    val keywordRule = SensitiveRedactionRule.keyword("keyword.safe", "keyword", keyword)
    val keywordPolicy = SensitiveRedactionPolicy.of(rules = listOf(keywordRule))
    val result = pipeline.redact("Support note $email $token")
    val rendered = result.toString() +
        result.spans.joinToString() +
        keywordRule.toString() +
        keywordPolicy.toString()

    rendered shouldNotContain email
    rendered shouldNotContain token
    rendered shouldNotContain keyword
    result.spans.forEach { span ->
        span.matchedLength shouldBeEqualTo (span.range.endExclusive - span.range.startInclusive)
        span.ruleIds.any { it.contains("@") }.shouldBeFalse()
    }
}
```

- [ ] **5단계: 중첩 및 오프셋 동작 테스트 추가**

```kotlin
@Test
fun `overlapping spans merge and adjacent spans stay separate`() {
    val policy = SensitiveRedactionPolicy.of(
        rules = listOf(
            SensitiveRedactionRule.keyword("keyword.low", "keyword", "account", priority = 30),
            SensitiveRedactionRule.keyword("keyword.high", "keyword", "account number", priority = 10),
            SensitiveRedactionRule.keyword("keyword.next", "keyword", "review", priority = 30),
        )
    )
    val localPipeline = SensitiveTextRedactionPipeline.of(policy)

    val result = localPipeline.redact("account number review")

    result.spans shouldHaveSize 2
    result.spans.first().range.startInclusive shouldBeEqualTo 0
    result.spans.first().range.endExclusive shouldBeEqualTo "account number".length
    result.spans.first().ruleIds shouldBeEqualTo listOf("keyword.high", "keyword.low")
}
```

또한 결정론적 엣지 케이스에 대한 명명된 테스트를 추가합니다.

- `adjacent keyword spans do not merge`: `abcdef` 입력과 함께 키워드 규칙 `abc` 및 `def`을 사용합니다. 두 개의 범위 `[0, 3)` 및 `[3, 6)`를 검증문합니다.
- `equal priority overlaps choose category by rule id then category`: 우선순위는 동일하고 다른 ids/categories을 가진 중복 키워드 규칙을 사용합니다. 하나의 병합된 범위와 예상되는 category/rule-id 순서를 검증문합니다.
- `keyword detector inclusive end converts to half open range`: 하나의 Aho-Corasick 키워드 규칙을 사용하고 `endExclusive == startInclusive + keyword.length`을 검증문합니다.

- [ ] **6단계: 유니코드, 유효성 검사, ReDoS, 로그 캡처 및 스레드 안전성 테스트 추가**

테스트 이름은 다음을 포함해야 합니다.

- `preserves original offsets when source contains decomposed Unicode`
- `rejects blank text without echoing caller input`
- `rejects over limit text without echoing caller input`
- `rejects unsafe rule ids and categories`
- `rejects invalid ranges masks empty rules and unsafe regex sources`
- `handles long non matching token candidates without catastrophic regex behavior`
- `returns language metadata for multilingual Korean and English input`
- `debug logs include safe metadata and exclude synthetic sensitive values`
- `policy snapshots do not change when caller mutates original rule list`
- `shared pipeline is stable under MultithreadingTester`

검증 테스트에는 다음이 포함되어야 합니다.

- `SensitiveTextRange.of(-1, 2)`, `SensitiveTextRange.of(2, 2)` 및 역방향 범위;
- 빈 규칙 컬렉션;
- 공백 또는 ISO-제어 마스크 문자;
- 규칙 id/category 대문자, 공백, 슬래시, 이메일, 전화번호, 토큰, 고객 ID, 티켓 ID 및 구성된 키워드 포함;
- `(a)\\1`과 같은 역참조, `(a+)+`과 같은 중첩된 무제한 수량자, `.*secret.*`와 같은 무제한 점별표를 포함하는 안전하지 않은 정규식 소스.

유니코드 테스트는 composed/NFC 키워드 규칙과 함께 NFD 소스를 사용한 다음 원본 코드 단위 `startInclusive`를 검증문해야 합니다.
`endExclusive`, `matchedLength`, 동일한 길이의 수정된 텍스트, 일치하는 범위에 마스크되지 않은 결합 조각이 없습니다.

ReDoS 테스트는 `SensitiveRedactionPolicy.DEFAULT_MAX_TEXT_LENGTH` 근처에서 적대적인 불일치 입력을 사용해야 합니다.
이메일, 전화 및 토큰 형태의 경우. `measureTime { ... }`으로 블록을 측정하고 검증문
`elapsed.inWholeMilliseconds shouldBeLessThan 300L`과 `bluetape4k-assertions`; JUnit을 사용하지 마십시오
`assertTimeoutPreemptively`과 같은 검증 API.

다음 로그백 패턴을 사용하세요.

```kotlin
private fun captureWorkshopLogs(block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger("io.bluetape4k.workshop.text") as Logger
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    val previousLevel = logger.level
    val previousAdditive = logger.isAdditive
    logger.level = ch.qos.logback.classic.Level.DEBUG
    logger.isAdditive = true
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
        logger.level = previousLevel
        logger.isAdditive = previousAdditive
        appender.stop()
    }
    return appender.list.map { it.formattedMessage }
}
```

로그 테스트는 파이프라인과 `LanguageDetectionService`, `TextNormalizer`, `AbuseWordFilter`를 실행해야 합니다.
`MultilingualSearchIndex` 및 `CoroutineMultilingualSearchIndex`. 터치할 때마다 하나 이상의 안전한 이벤트를 확인합니다.
collaborator/stage에는 로그 제외 `user@example.test`를 검증문하기 전에 length/count 메타데이터가 포함됩니다.
`555-010-1234`, `demo_token_value`, `account number`, 원시 쿼리 문자열, 정규화된 텍스트, 수정된 텍스트 및 키워드 목록.

다음 동시성 패턴을 사용하세요.

```kotlin
@Test
fun `shared pipeline is stable under MultithreadingTester`() {
    val baseline = pipeline.redact("Contact $email with $token")
    val outputs = ConcurrentLinkedQueue<SensitiveRedactionResult>()

    MultithreadingTester()
        .workers(8)
        .rounds(16)
        .add {
            outputs += pipeline.redact("Contact $email with $token")
        }
        .run()

    outputs shouldHaveSize 128
    outputs.forEach { result ->
        result shouldBeEqualTo baseline
        result.redactedText shouldNotContain email
        result.redactedText shouldNotContain token
    }
}
```

- [ ] **7단계: 집중 테스트 실행 및 RED 확인**

```bash
./gradlew :kotlin-text-processing:test --tests '*SensitiveTextRedactionPipelineTest' --console=plain
```

예상: 수정 패키지가 아직 존재하지 않기 때문에 컴파일이 실패합니다. 이것이 TDD 적색 증명이다.

## 작업 2: 수정 값 유형 및 파이프라인 구현

**복잡성:** 높음
**스킬:** `bluetape4k-code-patterns`, `ecc-kotlin-patterns`

**파일:**

- 생성: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/redaction/SensitiveTextRedactionPipeline.kt`

- [ ] **1단계: 검증된 직렬화 가능 값 유형 정의**

데이터 클래스가 `@ConsistentCopyVisibility`을 사용하지 않는 한 전용 생성자와 동반 팩토리를 사용하여 집중된 일반 클래스로 구현합니다.

필수 유형:

- `SensitiveTextRange`
- `SensitiveRedactionRule`
- `SensitiveRedactionPolicy`
- `SensitiveSpan`
- `SensitiveRedactionResult`
- `SensitiveTextRedactionPipeline`

검증 요구 사항:

- `requireNotBlank`, `requireNotEmpty` 및 `requireInRange`와 같은 bluetape4k 유효성 검사 도우미를 사용합니다.
- no `!!`;
- 예외 메시지에는 원시 민감한 값이 없습니다.
- 모든 공개 클래스에는 계약을 소개할 때 현실적인 사용 예가 포함된 영어 KDoc이 있습니다.
- 모든 값 유형은 `Serializable`을 구현하고 `serialVersionUID`을 정의합니다.
- 호출자 텍스트, 키워드 샘플, 정규식 소스, 정규화된 텍스트, 수정된 텍스트 또는 일치하는 값을 전달할 수 있는 모든 공개 모델은 유형 이름, 개수, 길이, 규칙 ids/categories 및 범위만 인쇄하는 명시적 안전 `toString()`을 구현합니다.

슬러그 계약:

- 규칙 ID 및 카테고리는 `^[a-z0-9._-]{1,64}규칙 ID 및 카테고리는 과 일치해야 합니다.
- 대문자, 공백, 슬래시, 이메일, 전화, 토큰형 값, 고객 ID, 티켓 ID 및 구성된 키워드 샘플 또는 정규식 고정 샘플과 같거나 포함하는 모든 id/category을 거부합니다.
- 예외 메시지는 필드와 규칙 종류만 명명해야 하며 거부된 원시 값은 명명하지 않아야 합니다.

정규식 계약:

- 기본 정규식은 미리 컴파일된 비공개 상수입니다.
- `SensitiveRedactionRule.regex(...)`은 컴파일하기 전에 정규식 소스의 유효성을 검사합니다.
- 역참조, 중첩된 무제한 수량자 및 무제한 `.*...*` 형식을 거부합니다.
- 이 검증 없이는 호출자가 제공한 원시 정규식은 허용되지 않습니다.
- `redact(...)`는 `Regex(...)`, `toRegex()`, `AhoCorasickAutomaton.builder()` 또는 `LanguageDetectionService()`를 호출하지 않습니다.

최대 입력 계약:

- `SensitiveRedactionPolicy.DEFAULT_MAX_TEXT_LENGTH` 정의;
- 정규식 스캔 전에 제한 초과 입력을 거부합니다.
- 한계 성공 및 한계 초과 안전 실패를 테스트합니다.
- 두 README 파일 모두에 제한을 문서화하세요.

- [ ] **2단계: 정책 상태를 한 번 컴파일**

`SensitiveRedactionPolicy.of(...)`은(는) 방어적으로 규칙을 복사하고 정렬해야 합니다. `SensitiveTextRedactionPipeline.of(...)`은(는) 다음을 빌드해야 합니다.

- 하나의 구성된 용어 Aho-Corasick 자동 장치;
- 하나의 불변 정규식 규칙 목록;
- 하나는 재사용 가능 `LanguageDetectionService`;
- 개인 잠금 장치로 보호된 감지기 도우미 1개.

감지된 언어 및 최고 신뢰도를 포함하여 공유 `LanguageDetectionService`에 대한 모든 호출은
개인 잠금 장치로 보호된 도우미 한 명을 거쳐야 합니다. 도우미는 `redact(...)`마다 한 번씩 잠금을 획득합니다.
해당 임계 섹션에서 두 값을 모두 호출하고 반환합니다. 정규식 검색을 수행하면 안 됩니다. Aho-Corasick
잠금을 유지하는 동안 일치하거나 렌더링할 수 있습니다. 외부에서는 감지기 액세스가 발생하지 않습니다. 논쟁을 기록하라
6-R단계의 이론적 근거.
`redact(...)` 내부에는 정규식, 탐지기 또는 Aho-Corasick 구성이 허용되지 않습니다.

- [ ] **3단계: 수정 흐름 구현**

`redact(text: String): SensitiveRedactionResult`은(는) 다음을 수행해야 합니다.

1. 공백이 아닌 텍스트와 최대 길이를 확인합니다.
2. 탐지를 위해 NFC으로 정규화합니다.
3. 정규화된 길이의 메타데이터를 보려면 `TextNormalizer.normalize`을 호출하세요.
4. 언어와 최고의 신뢰도를 감지합니다.
5. 정규식 및 키워드 일치 항목을 수집합니다.
6. Aho-Corasick 포함 끝을 `SensitiveTextRange` 반 개방 끝으로 변환합니다.
7. 사양 타이 브레이커를 사용하여 범위를 정렬하고 병합합니다.
8. 마스크되지 않은 텍스트를 변경하지 않고 마스크 출력을 렌더링합니다.
9. 원시 값이 없는 범위와 안전한 결과 객체를 반환합니다.

- [ ] **4단계: 컴파일 및 부분 집중 테스트 실행**

```bash
./gradlew :kotlin-text-processing:compileKotlin :kotlin-text-processing:compileTestKotlin --console=plain
```

예상: 컴파일 패스. 작업 3에서 기존 협력자를 정리할 때까지 로그 캡처 테스트를 통과하도록 요구하지 마세요.

## 작업 3: 기존 텍스트 처리 디버그 로그를 정리하고 집중적으로 완료 GREEN

**복잡성:** 중간
**스킬:** `bluetape4k-code-patterns`

**파일:**

- 수정: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/detection/LanguageDetectionService.kt`
- 수정: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/normalize/TextNormalizer.kt`
- 수정: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/filter/AbuseWordFilter.kt`
- 수정: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/search/MultilingualSearchIndex.kt`
- 수정: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/search/CoroutineMultilingualSearchIndex.kt`

- [ ] **1단계: 원시 텍스트 로그를 length/count 메타데이터로 교체**

허용되는 예:

```kotlin
log.debug { "detectLanguage length=${text.length} -> $detected" }
log.debug { "normalize length=${text.length} -> normalizedLength=${normalized.length}" }
log.debug { "filterText length=${text.length} -> filteredLength=${filtered.length}" }
log.debug { "search length=${query.length} terms=${queryTerms.size} -> hits=${hits.size}" }
```

금지된 예:

```kotlin
log.debug { "text='${text.take(40)}'" }
log.debug { "query='${query.take(40)}'" }
log.debug { "keywords=$keywords" }
```

- [ ] **2단계: 기존 텍스트 처리 테스트 실행**

```bash
./gradlew :kotlin-text-processing:test --tests '*LanguageDetectionServiceTest' --tests '*TextNormalizerTest' --tests '*AbuseWordFilterTest' --tests '*MultilingualSearchIndexTest' --tests '*CoroutineMultilingualSearchIndexTest' --console=plain
```

예상: 기존 동작은 녹색으로 유지됩니다.

- [ ] **3단계: 전체 편집 중심 테스트 실행 및 수정 GREEN**

```bash
./gradlew :kotlin-text-processing:test --tests '*SensitiveTextRedactionPipelineTest' --console=plain
```

예상: 공동작업자 로그 삭제 후 모든 수정 테스트가 통과됩니다.

## 작업 4: README 쌍 및 다이어그램 업데이트

**복잡성:** 높음
**스킬:** `bluetape4k-blog`, `bluetape4k-diagram`

**파일:**

- 수정: `kotlin/text-processing/README.md`
- 수정: `kotlin/text-processing/README.ko.md`
- 수정: `docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.svg`
- 수정: `docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png`
- 수정: `docs/images/readme-diagrams/kotlin-text-processing-scenario-01.svg`
- 수정: `docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png`

- [ ] **1단계: README 구성요소 및 사용법 업데이트**

두 로캘 파일의 구성 요소 테이블에 `SensitiveTextRedactionPipeline`을 추가합니다. 텍스트 정규화 후 또는 다국어 검색 전에 사용 섹션을 추가합니다.

```kotlin
val pipeline = SensitiveTextRedactionPipeline.default()
val result = pipeline.redact(
    "Support note: user@example.test called 555-010-1234 with token=demo_token_value_123456"
)

result.redactedText   // masks the email, phone, and synthetic token
result.spans          // internal raw-value-free metadata
```

두 README 파일 모두 다음을 명시해야 합니다.

- 이는 DLP/legal compliance/ML NER가 아닌 경험적 워크샵 정책입니다.
- 기본 정책은 생산 PII 적용 범위가 아닌 예시 전용 고정 정책입니다.
- `SensitiveSpan` 메타데이터는 public/anonymous이 아니라 내부적이고 원시 값이 없습니다.
- 오프셋, 길이, 카테고리 및 규칙 ID는 여전히 구조를 드러낼 수 있으며 명시적인 제품 결정 없이 최종 사용자나 광범위한 운영 로그에 노출되어서는 안 됩니다.
- 오프셋은 원래 입력에 대해 반쯤 열린 범위입니다.
- regexes/Aho-Corasick/detector 상태는 한 번 빌드되므로 파이프라인을 구성하고 재사용합니다.
- `SensitiveRedactionPolicy.DEFAULT_MAX_TEXT_LENGTH`은 정규식 작업을 제한하는 데 사용되는 워크샵 입력 경계입니다.
- 규제된 데이터 또는 철저한 PII 범위를 위해서는 더 강력한 감지기가 필요합니다.

두 README 파일 모두에 사용자 정의 정책 조각을 추가합니다.

```kotlin
val policy = SensitiveRedactionPolicy.of(
    rules = listOf(
        SensitiveRedactionRule.keyword("keyword.account", "keyword", "account number")
    )
)
val customPipeline = SensitiveTextRedactionPipeline.of(policy)
```

코드 조각은 안전 규칙 ids/categories과 민감하지 않은 고정 용어만 사용해야 합니다.

- [ ] **2단계: 아키텍처 다이어그램 업데이트**

편집하기 전에 `$bluetape4k-diagram` 현재 체크리스트를 사용하세요. 아키텍처 다이어그램에는 다음이 표시되어야 합니다.

- 텍스트 처리 유틸리티 레이어;
- `Redaction Pipeline` 경로;
- 경험적 규칙;
- 내부 감사 메타데이터와 별도로 수정된 출력
- HTTP, ML, DLP 또는 규정 준수 의미가 없습니다.

- [ ] **3단계: 처리 흐름도 업데이트**

Flow에는 다음이 표시되어야 합니다.

`Input -> NFC normalize -> language confidence -> regex + keyword spans -> merge overlaps -> redactedText + internal metadata`

메타데이터 node/callout은(는) `original half-open offsets + length + rule ids/category, no raw values`이어야 합니다.
둥근 직교 커넥터, 일관된 카드 정렬, 스타일이 다른 경우 명확한 선 스타일 범례, SVG/PNG 마커 패리티 및 전체 크기 눈 검사를 사용합니다.

- [ ] **4단계: 다이어그램 렌더링 및 유효성 검사**

```bash
xmllint --noout docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.svg
xmllint --noout docs/images/readme-diagrams/kotlin-text-processing-scenario-01.svg
~/.local/bin/cairosvg docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.svg -o docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png -s 2
~/.local/bin/cairosvg docs/images/readme-diagrams/kotlin-text-processing-scenario-01.svg -o docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png -s 2
./scripts/smoke-validate.sh diagram-qa
```

예상: XML 구문 분석, 렌더링, 다이어그램 QA 및 전체 크기 PNG 육안 검사 통과.

## 작업 5: CI 및 연기 적용 범위 추가

**복잡성:** 중간
**스킬:** `bluetape4k-code-patterns`

**파일:**

- 수정: `.github/workflows/Examples.yml`
- 수정: `scripts/smoke-validate.sh`

- [ ] **1단계: 예제 경로 필터 추가**

다른 Kotlin 항목 근처의 `push.paths` 및 `pull_request.paths` 아래에 이 경로를 추가합니다.

```yaml
      - 'kotlin/text-processing/**'
```

- [ ] **2단계: 예제 연기 작업 추가**

`:kotlin-text-processing:test`을 `Run H2/default examples`에 추가합니다.

- [ ] **3단계: 예제 아티팩트 경로 추가**

추가하다:

```yaml
            kotlin/text-processing/build/test-results/test/*.xml
            kotlin/text-processing/build/reports/tests/test/
```

- [ ] **4단계: 전체 연기 작업 추가**

`:kotlin-text-processing:test`을 `scripts/smoke-validate.sh` `all-smoke`에 추가합니다.

- [ ] **5단계: 워크플로 편집 내용 확인**

```bash
actionlint .github/workflows/Examples.yml
rg -n "\\\\'" .github/workflows
```

예상: `actionlint` 전달 및 이스케이프된 GitHub 표현식 따옴표가 없습니다.

## 작업 6: 확인, 증거 검토, 교훈 및 커밋

**복잡성:** 높음
**스킬:** `verification-before-completion`, `bluetape4k-code-patterns`, `bluetape4k-diagram`

**파일:**

- 생성: `docs/review/2026-07-03-issue-333-text-redaction-pipeline-code-review.md`
- 생성: `docs/lessons/2026-07-03-issue-333-text-redaction-pipeline.md`

- [ ] **1단계: 대상 컴파일 및 테스트 실행**

```bash
./gradlew :kotlin-text-processing:compileKotlin :kotlin-text-processing:compileTestKotlin --warning-mode all --console=plain
./gradlew :kotlin-text-processing:cleanTest :kotlin-text-processing:test --no-build-cache --warning-mode all --console=plain
```

예상: compile/test 통과하고 최종 보고서에 테스트 횟수가 기록됩니다.

- [ ] **2단계: 연기, 워크플로, 차이점 확인 실행**

```bash
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh all-smoke
actionlint .github/workflows/Examples.yml
rg -n "\\\\'" .github/workflows
git diff --check
```

예상: 모두 통과. `all-smoke`이 너무 느리면 PR 전에 한 번 실행하고 지속 시간을 기록하세요. 사양에 전체 연기 적용 범위가 추가되었으므로 이를 대상 테스트로 대체하지 마십시오.

- [ ] **3단계: README/source 일관성 검사 실행**

```bash
rg -n "SensitiveTextRedactionPipeline|SensitiveRedactionPolicy|SensitiveTextRange|SensitiveSpan" kotlin/text-processing/README.md kotlin/text-processing/README.ko.md kotlin/text-processing/src/main/kotlin
rg -n "user@example\\.test|555-010|demo_token_value|account number" kotlin/text-processing/README.md kotlin/text-processing/README.ko.md kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/redaction
rg --pcre2 -n "sk-|AKIA|AIza|xox[baprs]-|gh[pousr]_|eyJ[A-Za-z0-9_-]{10,}|[A-Za-z0-9._%+-]+@(?!example\\.test\\b)[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\b(?!555[-.\\s]?010[-.\\s]?)[0-9]{3}[-.\\s]?[0-9]{3}[-.\\s]?[0-9]{4}\\b" kotlin/text-processing/README.md kotlin/text-processing/README.ko.md kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/redaction docs/images/readme-diagrams/kotlin-text-processing-*.svg
rg -n "Regex\\(|toRegex\\(|AhoCorasickAutomaton\\.builder|LanguageDetectionService\\(" kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/redaction
```

예상: API 이름이 소스 및 README에 존재합니다. 합성 고정물은 메타데이터 검증문이나 로그가 아닌 README/test 입력 컨텍스트에만 나타납니다. 공급자 현실적인 토큰 접두사, 실제 JWT/API-key 예, 예약되지 않은 도메인 및 555가 아닌 전화 샘플이 변경된 docs/tests/diagram 소스에 없습니다. 정책 구성은 `redact(...)` 외부에서 발생합니다.

6-R단계에서 `redact(...)` 본문도 구체적으로 검사하고 해당 함수 본문에 다음이 포함되어 있으면 검토에 실패합니다.
`Regex(`, `toRegex()`, `AhoCorasickAutomaton.builder` 또는 `LanguageDetectionService(`. 패키지 수준 `rg`
위의 내용은 공사가 발생한 위치를 뒷받침하는 증거이지 그 자체로는 충분한 증거가 아닙니다.

- [ ] **4단계: 6~R단계 7계층 검토 실행**

6가지 관점으로 `origin/develop`에 대해 구현된 차이점을 검토합니다.

- 성능: 사전 컴파일된 규칙, 핫 경로 할당, 스레드 안전성 테스트
- 안정성: 탐지기 보호, 불변 정책 스냅샷, 범위 경계;
- 보안: 원시 값 누출, 안전한 정규식, 메타데이터 슬러그 유효성 검사, 로그
- 연산자: Examples/all-smoke 적용 범위, 로깅 지침, 롤백;
- developer/API: KDoc, 유효성 검사 도우미, 직렬화 가능한 값, 검증문;
- user/caller: README 명확성, 한국어 패리티, 다이어그램.

계속하기 전에 통합된 아티팩트를 `P0=0`, `P1=0`와 함께 `docs/review/2026-07-03-issue-333-text-redaction-pipeline-code-review.md`에 저장하세요.
검토에서는 탐지기 잠금 경합 근거, regex-source/static 구성 증거 및 롤백 범위를 기록해야 합니다.
일반 되돌리기는 수정 package/tests, README 섹션, 다이어그램 업데이트, workflow/smoke 추가 및 로그백 테스트 종속성을 제거합니다. DB 없음, 외부 서비스, 컨테이너 또는 런타임 정리가 필요하지 않습니다.

- [ ] **5단계: 강의 작성**

다음을 사용하여 `docs/lessons/2026-07-03-issue-333-text-redaction-pipeline.md`를 만듭니다.

- 수정 예시에서 기존 원시 디버그 로그가 중요한 이유
- 감사 안전이 원시 값이 없는 내부 메타데이터만을 의미하는 이유는 무엇입니까?
- 정확한 검증 명령 및 다이어그램 QA 증거;
- PII/logging 위험이 있는 텍스트 예제에 대한 미래의 보호.

- [ ] **6단계: 구현, 문서, 다이어그램, 검토 및 강의 커밋**

Lore 커밋 프로토콜을 사용합니다.

```bash
git add kotlin/text-processing \
  docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.svg \
  docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png \
  docs/images/readme-diagrams/kotlin-text-processing-scenario-01.svg \
  docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png \
  .github/workflows/Examples.yml \
  scripts/smoke-validate.sh \
  docs/review/2026-07-03-issue-333-text-redaction-pipeline-code-review.md \
  docs/lessons/2026-07-03-issue-333-text-redaction-pipeline.md
git commit -m "feat: add audit-safe text redaction pipeline"
```

작업 1이 시작되기 전에 3단계 spec/plan 커밋이 이미 존재해야 합니다. 포함하지 않음
`docs/superpowers/specs/2026-07-03-issue-333-text-redaction-pipeline-design.md`
또는 `docs/superpowers/plans/2026-07-03-issue-333-text-redaction-pipeline-plan.md`
이 구현에서는 3-R단계에서 이후 계획 복구를 강제하지 않는 한 커밋합니다.

커밋 본문에는 다음이 포함되어야 합니다.

```text
Constraint: Issue #333 requires deterministic redaction, raw-value-free metadata, README limitation guidance, and root BOM-only dependencies.
Rejected: new kotlin/text-redaction-pipeline module | existing kotlin/text-processing already owns normalization, language detection, and text-search examples.
Confidence: high
Scope-risk: moderate
Directive: Keep future text examples from logging raw caller text when PII or support-ticket workflows are in scope.
Tested: <commands that passed>
Not-tested: <explicit gaps, or none>
```

## 작업 7: PR, 사후 PR 검토, CI 및 최종 DoD

**복잡성:** 높음
**스킬:** `verification-before-completion`, `bluetape4k-workflow`

**파일:**

- PR 본문 임시 파일: `/tmp/issue-333-redaction-pr.md`

- [ ] **1단계: PR 푸시 및 생성**

PR을 만들기 전에 이슈 메타데이터를 실시간으로 읽어보세요.

```bash
gh issue view 333 --json assignees,labels,milestone,state,url
```

PR 메타데이터:

- 제목: `feat: add audit-safe text redaction pipeline`
- 기본: `develop`
- 담당자: `debop`
- 이정표: `1.3.1`
- GitHub에서 지원하는 이슈 #333에서 복사된 라벨
- 본문 마지막 섹션: `## DoD Status`

생성 후 본문 확인:

```bash
gh pr view <number> --json body,milestone,assignees,labels,state,url
```

예상: 마지막 `##` 제목은 `## DoD Status`입니다.

- [ ] **2단계: 7-R단계 PR 검토 실행**

실제 PR 차이점에 대해 사후 PR 검토를 실행합니다. P0/P1=0을 기록하고 review/CI 상태가 변경된 경우 PR 본문 DoD을 업데이트합니다.

- [ ] **3단계: CI 게이트 대기**

```bash
gh pr view <number> --json statusCheckRollup
```

예상: 필수 확인 사항은 `SUCCESS` 또는 `SKIPPED`입니다. 실패하면 6/implementation 작업으로 돌아갑니다.

- [ ] **4단계: CI 아티팩트 콘텐츠 확인**

smoke/example CI 실행이 성공한 후 `smoke-example-test-results` 아티팩트를 검사하거나 다운로드하고 다음이 포함되어 있는지 확인합니다.

```text
kotlin/text-processing/build/test-results/test/*.xml
kotlin/text-processing/build/reports/tests/test/
```

최종 DoD에 아티팩트 command/output을 기록합니다. 아티팩트 다운로드를 사용할 수 없는 경우 GitHub 실행 URL과 아티팩트를 사용할 수 없는 정확한 이유를 기록하세요.

- [ ] **5단계: 최종 9단계 DoD 보고서**

다음을 포함하여 `Step | Status | Evidence` 테이블을 사용하여 보고합니다.

- 발행 #333 메타데이터;
- 사양 및 계획 경로;
- 지역 compile/test/smoke/actionlint/diff 증거;
- 다이어그램 체크리스트 및 육안 검사 증거;
- `kotlin/text-processing` 테스트 XML/report 경로에 대한 CI 아티팩트 콘텐츠 증거;
- 단계 6-R 및 단계 7-R P0/P1=0;
- PR number/body metadata/CI;
- 최종 상태 `DONE - PR #<number> pending merge`.

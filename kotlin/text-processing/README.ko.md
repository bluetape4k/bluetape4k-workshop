# kotlin/text-processing

[English](README.md) | 한국어

이 모듈은 `bluetape4k-text`와 작은 Kotlin helper로 애플리케이션 내부에서 실행하는
텍스트 처리 유틸리티를 보여줍니다. 예제에서 바로 확인할 수 있는 작업은 다섯 가지입니다.
금칙어 필터링, 언어 감지, 검색/색인 전 텍스트 정규화, highlight 결과를 반환하는
동기/코루틴 다국어 검색 인덱스, 그리고 audit-safe span metadata를 반환하는 작은
sensitive text redaction pipeline입니다.

## 아키텍처

![kotlin/text-processing architecture](../../docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png)

## 처리 시퀀스

![kotlin/text-processing flow](../../docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png)

## Redaction pipeline

![kotlin/text-processing redaction pipeline](../../docs/images/readme-diagrams/kotlin-text-processing-redaction-pipeline-01.png)

## 구성 요소

| class | 사용 API | 계약 |
|---|---|---|
| `AbuseWordFilter` | `text-search`의 `AhoCorasickAutomaton` | 대소문자 무시, NFC 정규화, overlap 허용; match span을 `*`로 masking |
| `LanguageDetectionService` | `bluetape4k-text-lingua`의 Lingua detector | detector를 한 번 만들고 재사용하며, blank/unknown text는 `null` 반환 |
| `CoroutineLanguageDetectionService` | `LanguageDetectionService`, `Mutex`, `Dispatchers.Default` | 여러 coroutine caller가 공유해도 detector 접근을 직렬화 |
| `TextNormalizer` | pure Kotlin object | 소문자 변환, 공백 정리, 중복 제거 keyword extraction |
| `MultilingualSearchIndex` | `LanguageDetectionService`, `KoreanProcessor`, `JapaneseProcessor`, `TextNormalizer`, `AhoCorasickAutomaton` | 문서와 query의 언어를 감지하고, inverted term index를 만든 뒤, match 점수와 source-span highlight를 반환 |
| `CoroutineMultilingualSearchIndex` | `CoroutineLanguageDetectionService`, immutable index snapshot, `AhoCorasickAutomaton` | coroutine service에서 사용할 suspend `indexOf`/`search` API 제공 |
| `SensitiveTextRedactionPipeline` | `LanguageDetectionService`, `TextNormalizer`, regex rules, `AhoCorasickAutomaton` | 작은 policy를 검증하고 email/phone/token/keyword span을 찾은 뒤, overlap merge, same-length masking, safe metadata 반환 |

## 사용 예

### 금칙어 필터링

```kotlin
val filter = AbuseWordFilter(listOf("spam", "abuse", "badword"))

filter.containsAbuse("There is some spam here")   // true
filter.filterText("No spam allowed")              // "No **** allowed"
filter.findMatches("spam and abuse")              // AhoCorasickMatch list
```

Automaton은 keyword collection으로 한 번 구성됩니다. 이후 match는 입력 text를 한 번 훑고,
match 개수만큼만 추가 비용이 듭니다.

### 언어 감지

```kotlin
val service = LanguageDetectionService()

service.detectLanguage("Hello, World!")      // Language.ENGLISH
service.detectLanguage("안녕하세요.")         // Language.KOREAN
service.detectLanguage("東京は首都です。")      // Language.JAPANESE

val scores = service.computeConfidenceValues("This is English text.")
```

Language detector 생성 비용이 크므로 가능하면 `LanguageDetectionService` instance를 재사용합니다.

### 텍스트 정규화

```kotlin
TextNormalizer.normalize("  Hello   WORLD  ")                 // "hello world"
TextNormalizer.extractKeywords("the quick brown fox")          // ["the", "quick", "brown", "fox"]
TextNormalizer.extractKeywords("a b quick", minKeywordLength = 4) // ["quick"]
```

### 민감 텍스트 Redaction

```kotlin
val pipeline = SensitiveTextRedactionPipeline.default()

val result = pipeline.redact(
    "Contact user@example.test at 555-010-1234 with token=demo_token_value_123456."
)

result.redactedText
// "Contact ***************** at ************ with *****************************."

result.spans.map { it.category }
// ["contact", "contact", "secret"]
```

기본 policy는 의도적으로 작고 fixture 중심입니다. 이 예제에서 중요한 부분은
애플리케이션 로그나 support ticket에서 자주 필요한 처리 경계입니다.

- rule id와 category는 safe metadata slug만 허용합니다.
- regex rule은 backreference, 중첩 unbounded quantifier, unbounded `.*` pattern을 거부합니다.
- keyword rule은 NFC-aware Aho-Corasick matching을 사용하므로 원문 offset을 보존합니다.
- overlap span은 priority 기준으로 merge하지만, 서로 붙어만 있는 adjacent span은 분리합니다.
- `toString()`, debug log, exception, span metadata는 원문 민감값을 담지 않습니다.

지역별 식별자, 자유 형식 주소/이름, OCR 결과, fixture rule을 넘어서는 다국어 PII,
컴플라이언스 목적의 DLP 요구가 있다면 더 강한 detector를 사용해야 합니다. 이 pipeline은
학습용 예제이지 완전한 classifier가 아닙니다.

### 다국어 검색 인덱스

```kotlin
val index = MultilingualSearchIndex.indexOf(
    listOf(
        SearchDocument.of(
            id = "ko-1",
            title = "Korean indexing",
            text = "코틀린 검색 인덱스는 한국어 명사를 토큰으로 사용합니다.",
        ),
        SearchDocument.of(
            id = "ja-1",
            title = "Japanese indexing",
            text = "検索インデックスは日本語の名詞をトークンとして保存します。",
        ),
    ),
)

val hits = index.search("한국어 검색")
hits.first().highlightedText   // 원문에 <mark>...</mark> fragment를 삽입한 text
hits.first().matches           // match term의 원문 start/end offset
```

이 예제는 언어 처리 흐름을 쉽게 따라갈 수 있도록 의도적으로 in-memory index만 사용합니다.

- 한국어는 `KoreanProcessor.normalize`와 명사 token을 사용합니다.
- 일본어는 `JapaneseProcessor.tokenize` 후 명사 token만 사용합니다.
- 영어와 unknown text는 `TextNormalizer.extractKeywords`를 사용합니다.
- `SearchDocument.id`는 index key이므로 하나의 index 안에서 고유해야 합니다.
- Lingua detector 생성 비용이 크므로 문서와 query 모두 같은 `LanguageDetectionService`를 재사용합니다.
- Highlighting은 원문에 대한 literal term matching입니다. stemming, semantic search, typo-tolerant search는 이 예제의 범위가 아닙니다.
- Overlap match는 `SearchHighlightHit.matches`에 보존합니다. 다만 `highlightedText`는 Aho-Corasick tokenization의 deterministic non-overlap fragment를 사용하므로 중첩 `<mark>` tag는 만들지 않습니다.

### Coroutine-safe 검색 인덱스

여러 coroutine worker가 같은 index를 동시에 조회해야 한다면 coroutine variant를 사용합니다.

```kotlin
val detection = CoroutineLanguageDetectionService()
val index = CoroutineMultilingualSearchIndex.indexOf(
    documents = documents,
    detectionService = detection,
)

val hits = index.search("서울 카페")
```

Coroutine index는 동기 API와 일부러 분리했습니다. `MultilingualSearchIndex`는 단일 thread
예제를 이해하기 쉽게 유지하고, `CoroutineMultilingualSearchIndex`는 guarded detector wrapper,
immutable index snapshot, `Dispatchers.Default` 기반 suspend API를 추가합니다.

## 의존성

이 모듈은 repository version catalog alias를 사용합니다.

```kotlin
dependencies {
    implementation(libs.bluetape4k.text.search)
    implementation(libs.bluetape4k.text.lingua)
    implementation(libs.bluetape4k.text.korean)
    implementation(libs.bluetape4k.text.japanese)
    implementation(libs.kotlinx.coroutines.core.lib)
}
```

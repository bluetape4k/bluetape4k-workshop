# kotlin/text-processing

[English](README.md) | 한국어

이 모듈은 `bluetape4k-text`와 작은 Kotlin helper로 애플리케이션 내부에서 실행하는
텍스트 처리 유틸리티를 보여줍니다. 금칙어 필터링, 언어 감지, 검색/색인 전 텍스트 정규화,
시작 단계 사전 준비 상태, highlight 결과를 반환하는 동기/코루틴 다국어 검색 인덱스,
전체 index generation 원자 교체, 그리고 audit-safe span metadata를 반환하는 작은
sensitive text redaction pipeline을 다룹니다.

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
| `CoroutineMultilingualSearchIndex` | `CoroutineLanguageDetectionService`, immutable index 기준 상태, `AhoCorasickAutomaton` | coroutine service에서 사용할 suspend `indexOf`/`search` API 제공 |
| `TokenizerDictionaryReadiness` | `KoreanProcessor.preload`, `JapaneseProcessor.preload`, `Mutex` | suspend preload attempt 하나를 공유하고 두 dictionary 준비 전에는 요청 작업을 거절 |
| `VersionedMultilingualSearchIndex` | `VersionedDictionary`, `DictionarySnapshot`, `MultilingualSearchIndex` | 완성된 전체 index generation만 공개하고 각 검색이 사용한 정확한 revision을 반환 |
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
immutable index 기준 상태, `Dispatchers.Default` 기반 suspend API를 추가합니다.

### Dictionary preload와 readiness

Index를 만들거나 요청을 받기 전에 startup 단계에서 두 tokenizer dictionary를 preload합니다.

```kotlin
val dictionaries = TokenizerDictionaryReadiness()
dictionaries.preload()

val index = CoroutineMultilingualSearchIndex.indexOf(documents)
val result = dictionaries.runWhenReady { index.search("서울 카페") }

when (result) {
    is DictionaryReadyResult.Ready -> result.value
    is DictionaryReadyResult.NotReady -> emptyList()
}
```

초기 상태는 `NOT_READY`이고 실행 중인 attempt는 `LOADING`을 공개합니다. 두
`KoreanProcessor.preload()`와 `JapaneseProcessor.preload()`가 모두 끝난 뒤에만 `READY`가
보입니다. 동시에 시작한 caller는 attempt 하나를 공유합니다. 실패나 취소가 발생하면 상태를
`NOT_READY`로 되돌린 뒤 원래 예외를 다시 던지므로 다음 caller가 새 attempt 번호로 재시도할
수 있습니다. 두 loader 뒤의 취소 검사로 잘못된 loader가 취소를 삼켜도 `READY`를 공개하지
않습니다.

`runWhenReady`는 startup이 끝나지 않았을 때 block을 실행하지 않고 `NotReady`를 반환하므로
부분 초기화된 요청 결과를 만들지 않습니다. 기존 동기 tokenizer facade와는 호환되지만 startup
preload를 생략하면 첫 호출이 blocking될 수 있습니다. 예측 가능한 요청 latency를 위해 위 순서인
preload, 기존 index 생성, readiness gate가 있는 search 경로 공개를 따릅니다.

### 일본어 tokenizer backend 비교

승인된 corpus(`選挙管理委員会`, `東京都へ行く`, `外国人参政権`)를 하나의 dictionary session에서
처리해 기존 Kuromoji IPADic 경로와 Sudachi JVM을 비교합니다.

```kotlin
val reports = runJapaneseBackendComparisons()
val oneReport = runJapaneseBackendComparison("選挙管理委員会")
```

두 helper는 Kuromoji와 Sudachi의 surface 및 broad POS observation을 남깁니다. Sudachi는
`Tokenizer.SplitMode.A/B/C`별 surface도 기록하므로 정확도나 latency 우위를 주장하지 않고,
마이그레이션 검토에서 mode별 segmentation 차이를 확인할 수 있습니다. 여러 입력을 받는
helper는 corpus 전체에서 dictionary/tokenizer session을 하나만 열며, 단일 fixture가 필요할 때만
단일 입력 helper를 사용합니다.

기본 `test` task는 dictionary를 다운로드하지 않습니다. `bluetape4k.sudachi.system-dictionary`
system property가 없으면 candidate를 `UNAVAILABLE`로 기록하고
`prepareSudachiDictionary` 복구 안내를 반환합니다. 로컬에서 다운로드가 허용될 때 실제
dictionary-backed 예제를 명시적으로 실행합니다.

```bash
./gradlew :kotlin-text-processing:test
./gradlew :kotlin-text-processing:sudachiTest
```

`sudachiTest`는 공식 72,238,136-byte `SudachiDict v20260428 core` archive를 다운로드하고
217,374,303-byte `system_core.dic`를 추출하므로 local/manual-only 검증입니다. archive URL은
`https://github.com/WorksApplications/SudachiDict/releases/download/v20260428/sudachi-dictionary-20260428-core.zip`이며
라이선스는 Apache-2.0입니다. archive SHA-256은
`40c8ffc095283f07aa06cae922e7b8147bf2919ec8830567b0b3f7a7efa3239f`, 추출 dictionary SHA-256은
`6c1d5adc8a2389875713056e7b39bbcd0073d6122ffd509866e1d3a196f8608e`로 고정합니다. 두 파일은
`kotlin/text-processing/build/sudachi-dictionary/v20260428` 아래의 build-only output으로만 유지하며
commit하지 않습니다.

## 의존성

이 모듈은 repository version catalog alias를 사용하며 bluetape4k module version은 단일
`bluetape4k-dependencies:2.0.0` BOM으로 해석합니다.

```kotlin
dependencies {
    implementation(libs.bluetape4k.text.search)
    implementation(libs.bluetape4k.text.lingua)
    implementation(libs.bluetape4k.text.korean)
    implementation(libs.bluetape4k.text.japanese)
    implementation(libs.sudachi)
    implementation(libs.kotlinx.coroutines.core.lib)
}
```

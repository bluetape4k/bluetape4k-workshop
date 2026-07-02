# kotlin/text-processing

[한국어](README.ko.md) | English

This module demonstrates in-process text utilities built on `bluetape4k-text` and small Kotlin
helpers. It covers five reader-visible tasks: abuse-word filtering, language detection, text
normalization before indexing, sync/coroutine multilingual search indexes with highlighted results,
and a small sensitive-text redaction pipeline with audit-safe span metadata.

## Architecture

![kotlin/text-processing architecture](../../docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png)

## Processing flow

![kotlin/text-processing flow](../../docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png)

## Redaction pipeline

![kotlin/text-processing redaction pipeline](../../docs/images/readme-diagrams/kotlin-text-processing-redaction-pipeline-01.png)

## Components

| class | backing API | contract |
|---|---|---|
| `AbuseWordFilter` | `AhoCorasickAutomaton` from `text-search` | Case-insensitive NFC matching with overlaps; masks matched spans with `*` |
| `LanguageDetectionService` | Lingua detector from `bluetape4k-text-lingua` | Reuses one detector and returns `null` for blank or unknown text |
| `CoroutineLanguageDetectionService` | `LanguageDetectionService`, `Mutex`, `Dispatchers.Default` | Serializes detector access for concurrent coroutine callers |
| `TextNormalizer` | pure Kotlin object | Lowercases text, collapses whitespace, extracts deduplicated keywords |
| `MultilingualSearchIndex` | `LanguageDetectionService`, `KoreanProcessor`, `JapaneseProcessor`, `TextNormalizer`, `AhoCorasickAutomaton` | Detects document/query language, builds an inverted term index, ranks matched documents, and emits source-span highlights |
| `CoroutineMultilingualSearchIndex` | `CoroutineLanguageDetectionService`, immutable index snapshot, `AhoCorasickAutomaton` | Provides suspend `indexOf` and `search` APIs for coroutine services while keeping detector access guarded |
| `SensitiveTextRedactionPipeline` | `LanguageDetectionService`, `TextNormalizer`, regex rules, `AhoCorasickAutomaton` | Validates a small policy, finds email/phone/token/keyword spans, merges overlaps, masks same-length output, and returns safe metadata |

## Usage

### Abuse-word filtering

```kotlin
val filter = AbuseWordFilter(listOf("spam", "abuse", "badword"))

filter.containsAbuse("There is some spam here")   // true
filter.filterText("No spam allowed")              // "No **** allowed"
filter.findMatches("spam and abuse")              // AhoCorasickMatch list
```

The automaton is built once from the keyword collection. After construction, matching is a single
pass over the input text plus the number of matches.

### Language detection

```kotlin
val service = LanguageDetectionService()

service.detectLanguage("Hello, World!")      // Language.ENGLISH
service.detectLanguage("\uC548\uB155\uD558\uC138\uC694.") // Language.KOREAN
service.detectLanguage("東京は首都です。")      // Language.JAPANESE

val scores = service.computeConfidenceValues("This is English text.")
```

Reuse one `LanguageDetectionService` instance when possible because language detector construction is
expensive.

### Text normalization

```kotlin
TextNormalizer.normalize("  Hello   WORLD  ")                 // "hello world"
TextNormalizer.extractKeywords("the quick brown fox")          // ["the", "quick", "brown", "fox"]
TextNormalizer.extractKeywords("a b quick", minKeywordLength = 4) // ["quick"]
```

### Sensitive text redaction

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

The default policy is deliberately small and fixture-oriented. It demonstrates the mechanics that
matter in application logs and support-ticket examples:

- rule ids and categories are validated as safe metadata slugs;
- regex rules reject backreferences, nested unbounded quantifiers, and unbounded `.*` patterns;
- keyword rules use NFC-aware Aho-Corasick matching, so original offsets are preserved;
- overlapping spans merge by priority, but adjacent spans remain separate;
- `toString()`, debug logs, exceptions, and span metadata avoid raw sensitive values.

Use a stronger detector when the input contains jurisdiction-specific identifiers, free-form
addresses, names, OCR output, multilingual PII beyond the fixture rules, or compliance-driven DLP
requirements. This workshop pipeline is a learning example, not a full classifier.

### Multilingual search index

```kotlin
val index = MultilingualSearchIndex.indexOf(
    listOf(
        SearchDocument.of(
            id = "ko-1",
            title = "Korean indexing",
            text = "\uCF54\uD2C0\uB9B0 \uAC80\uC0C9 \uC778\uB371\uC2A4\uB294 \uD55C\uAD6D\uC5B4 \uBA85\uC0AC\uB97C \uD1A0\uD070\uC73C\uB85C \uC0AC\uC6A9\uD569\uB2C8\uB2E4.",
        ),
        SearchDocument.of(
            id = "ja-1",
            title = "Japanese indexing",
            text = "検索インデックスは日本語の名詞をトークンとして保存します。",
        ),
    ),
)

val hits = index.search("\uD55C\uAD6D\uC5B4 \uAC80\uC0C9")
hits.first().highlightedText   // source text with <mark>...</mark> fragments
hits.first().matches           // original start/end offsets for matched terms
```

The example deliberately keeps the index in memory so the language-processing path is easy to
inspect:

- Korean text uses `KoreanProcessor.normalize` plus noun tokens.
- Japanese text uses `JapaneseProcessor.tokenize` plus noun filtering.
- English and unknown text use `TextNormalizer.extractKeywords`.
- `SearchDocument.id` is the index key and must be unique within one index.
- `LanguageDetectionService` is reused for every document and query because Lingua detector
  construction is expensive.
- Highlighting is literal term matching against the original source text. It is not stemming,
  semantic search, or typo-tolerant search.
- Overlapping matches are preserved in `SearchHighlightHit.matches`. The rendered
  `highlightedText` uses deterministic non-overlapping fragments from Aho-Corasick tokenization,
  so nested `<mark>` tags are intentionally avoided.

### Coroutine-safe search index

Use the coroutine variant when the same index is queried from many coroutine workers:

```kotlin
val detection = CoroutineLanguageDetectionService()
val index = CoroutineMultilingualSearchIndex.indexOf(
    documents = documents,
    detectionService = detection,
)

val hits = index.search("\uC11C\uC6B8 \uCE74\uD398")
```

The coroutine index keeps the synchronous API separate on purpose. `MultilingualSearchIndex` remains
small for single-threaded examples, while `CoroutineMultilingualSearchIndex` adds a guarded detector
wrapper, an immutable index snapshot, and suspend APIs that run CPU-bound text work on
`Dispatchers.Default`.

## Dependencies

The module uses the repository version catalog:

```kotlin
dependencies {
    implementation(libs.bluetape4k.text.search)
    implementation(libs.bluetape4k.text.lingua)
    implementation(libs.bluetape4k.text.korean)
    implementation(libs.bluetape4k.text.japanese)
    implementation(libs.kotlinx.coroutines.core.lib)
}
```

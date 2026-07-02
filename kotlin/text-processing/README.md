# kotlin/text-processing

[한국어](README.ko.md) | English

This module demonstrates in-process text utilities built on `bluetape4k-text` and small Kotlin
helpers. It covers four reader-visible tasks: abuse-word filtering, language detection, text
normalization before indexing, and a small multilingual search index with highlighted results.

## Architecture

![kotlin/text-processing architecture](../../docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png)

## Processing flow

![kotlin/text-processing flow](../../docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png)

## Components

| class | backing API | contract |
|---|---|---|
| `AbuseWordFilter` | `AhoCorasickAutomaton` from `text-search` | Case-insensitive NFC matching with overlaps; masks matched spans with `*` |
| `LanguageDetectionService` | Lingua detector from `bluetape4k-text-lingua` | Reuses one detector and returns `null` for blank or unknown text |
| `TextNormalizer` | pure Kotlin object | Lowercases text, collapses whitespace, extracts deduplicated keywords |
| `MultilingualSearchIndex` | `LanguageDetectionService`, `KoreanProcessor`, `JapaneseProcessor`, `TextNormalizer`, `AhoCorasickAutomaton` | Detects document/query language, builds an inverted term index, ranks matched documents, and emits source-span highlights |

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

### Multilingual search index

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

## Dependencies

The module uses the repository version catalog:

```kotlin
dependencies {
    implementation(libs.bluetape4k.text.search)
    implementation(libs.bluetape4k.text.lingua)
    implementation(libs.bluetape4k.text.korean)
    implementation(libs.bluetape4k.text.japanese)
}
```

# kotlin/text-processing

[한국어](README.ko.md) | English

This module demonstrates in-process text utilities built on `bluetape4k-text` and small Kotlin
helpers. It covers three reader-visible tasks: abuse-word filtering, language detection, and text
normalization before search or indexing.

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

## Dependencies

The module uses the repository version catalog:

```kotlin
dependencies {
    implementation(libs.bluetape4k.text.search)
    implementation(libs.bluetape4k.text.lingua)
    implementation(libs.bluetape4k.text.korean)
}
```

# kotlin/text-processing

[한국어](README.ko.md) | English

## Architecture Diagram

![kotlin/text-processing Graphviz architecture diagram](../../docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.kotlin` as the source of truth when comparing this README with the code.

## Flow Diagram

1. Prepare the local runtime required by `kotlin-text-processing`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Text processing workshop module using [bluetape4k-text](https://github.com/bluetape4k/bluetape4k-text) libraries.

Demonstrates three practical text-processing scenarios:

| Class | BT Module | Feature |
|---|---|---|
| `AbuseWordFilter` | `text-search` | Multi-keyword filtering via AhoCorasick automaton |
| `LanguageDetectionService` | `lingua` | Language detection for Korean / English / Japanese |
| `TextNormalizer` | (pure Kotlin) | Whitespace normalization and keyword extraction |

---

## Scenario

![3 Text Processing Scenarios](../../docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png)

---

## Core Features

### AbuseWordFilter — AhoCorasick multi-keyword search

Builds an immutable, thread-safe Aho-Corasick automaton at construction time. All subsequent
`containsAbuse`, `filterText`, and `findMatches` calls run in **O(text length + matches)** time,
regardless of how many registered keywords there are.

```kotlin
val filter = AbuseWordFilter(listOf("spam", "abuse", "badword"))

filter.containsAbuse("There is some spam here")   // true
filter.filterText("No spam allowed")              // "No **** allowed"
filter.findMatches("spam and abuse")              // 2 AhoCorasickMatch objects
```

**Before / After — why AhoCorasick beats naive `String.contains` loops:**

```kotlin
// Naive approach — O(keywords × text) — scans the text once per keyword
val words = listOf("spam", "abuse", "hate", /* ... 1000 more ... */)
val found = words.any { text.contains(it, ignoreCase = true) }

// AhoCorasick — O(text + matches) — one pass regardless of keyword count
val filter = AbuseWordFilter(words)
val found = filter.containsAbuse(text)
```

For 1 000 keywords on a 10 000-character message the naive approach makes up to
10 000 000 character comparisons. The automaton makes exactly 10 000.

### LanguageDetectionService — Lingua-backed language detection

Wraps the [Lingua](https://github.com/pemistahl/lingua) language detector. The underlying
language models are loaded once and reused across all calls.

```kotlin
val service = LanguageDetectionService()

service.detectLanguage("Hello, World!")     // Language.ENGLISH
service.detectLanguage("Hello.") // Language.KOREAN
service.detectLanguage("東京は首都です。")    // Language.JAPANESE

// Confidence values for all plausible languages
val scores = service.computeConfidenceValues("This is English text.")
// { ENGLISH -> 0.97, DUTCH -> 0.01, ... }
```

### TextNormalizer — lightweight text normalisation

Stateless `object` for pre-processing text before indexing or search:

```kotlin
TextNormalizer.normalize("  Hello   WORLD  ")      // "hello world"
TextNormalizer.extractKeywords("the quick brown fox")   // ["the", "quick", "brown", "fox"]
TextNormalizer.extractKeywords("a b quick", minKeywordLength = 4) // ["quick"]
```

---

## Configuration

No external services required. All processing runs in-process.

The `bluetape4k-text` libraries are independently versioned under `io.github.bluetape4k.text`:

| Dependency | Version |
|---|---|
| `io.github.bluetape4k.text:text-search` | 0.1.2 |
| `io.github.bluetape4k.text:lingua` | 0.1.2 |

---

## Dependency Instructions

Add to `build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.bluetape4k.text.search)
    implementation(libs.bluetape4k.text.lingua)
}
```

Ensure the version catalog entry exists:

```toml
[versions]
bluetape4k-text = "0.1.2"

[libraries]
bluetape4k-text-search = { module = "io.github.bluetape4k.text:text-search", version.ref = "bluetape4k-text" }
bluetape4k-text-lingua = { module = "io.github.bluetape4k.text:lingua", version.ref = "bluetape4k-text" }
```

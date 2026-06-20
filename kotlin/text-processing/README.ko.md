# kotlin/text-processing

[English](README.md) | 한국어

이 모듈은 `bluetape4k-text`와 작은 Kotlin helper로 in-process text utility를 보여줍니다.
독자가 바로 확인할 작업은 세 가지입니다. abuse-word filtering, language detection,
그리고 search/indexing 전 text normalization입니다.

## 아키텍처

![kotlin/text-processing architecture](../../docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png)

## 처리 시퀀스

![kotlin/text-processing flow](../../docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png)

## Components

| class | backing API | 계약 |
|---|---|---|
| `AbuseWordFilter` | `text-search`의 `AhoCorasickAutomaton` | 대소문자 무시, NFC 정규화, overlap 허용; match span을 `*`로 masking |
| `LanguageDetectionService` | `bluetape4k-text-lingua`의 Lingua detector | detector를 한 번 만들고 재사용하며, blank/unknown text는 `null` 반환 |
| `TextNormalizer` | pure Kotlin object | lowercase, whitespace collapse, deduplicated keyword extraction |

## 사용 예

### Abuse-word filtering

```kotlin
val filter = AbuseWordFilter(listOf("spam", "abuse", "badword"))

filter.containsAbuse("There is some spam here")   // true
filter.filterText("No spam allowed")              // "No **** allowed"
filter.findMatches("spam and abuse")              // AhoCorasickMatch list
```

Automaton은 keyword collection으로 한 번 구성됩니다. 이후 match는 입력 text를 한 번 훑고,
match 개수만큼만 추가 비용이 듭니다.

### Language detection

```kotlin
val service = LanguageDetectionService()

service.detectLanguage("Hello, World!")      // Language.ENGLISH
service.detectLanguage("안녕하세요.")         // Language.KOREAN
service.detectLanguage("東京は首都です。")      // Language.JAPANESE

val scores = service.computeConfidenceValues("This is English text.")
```

Language detector 생성 비용이 크므로 가능하면 `LanguageDetectionService` instance를 재사용합니다.

### Text normalization

```kotlin
TextNormalizer.normalize("  Hello   WORLD  ")                 // "hello world"
TextNormalizer.extractKeywords("the quick brown fox")          // ["the", "quick", "brown", "fox"]
TextNormalizer.extractKeywords("a b quick", minKeywordLength = 4) // ["quick"]
```

## Dependencies

이 모듈은 repository version catalog alias를 사용합니다.

```kotlin
dependencies {
    implementation(libs.bluetape4k.text.search)
    implementation(libs.bluetape4k.text.lingua)
    implementation(libs.bluetape4k.text.korean)
}
```

# kotlin/text-processing

[English](README.md) | 한국어

## 아키텍처 다이어그램

![kotlin/text-processing Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png)

모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.kotlin` 패키지 아래의 구현을 기준으로 삼습니다.

## 흐름 다이어그램

1. `kotlin-text-processing`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여주며, 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

[bluetape4k-text](https://github.com/bluetape4k/bluetape4k-text) 라이브러리를 사용하는 텍스트 처리 워크샵 모듈입니다.

세 가지 실용적인 텍스트 처리 시나리오를 보여줍니다.

| Class | BT Module | Feature |
|---|---|---|
| `AbuseWordFilter` | `text-search` | AhoCorasick automaton을 통한 다중 키워드 필터링 |
| `LanguageDetectionService` | `lingua` | 한국어 / 영어 / 일본어 언어 감지 |
| `TextNormalizer` | (pure Kotlin) | 공백 정규화와 키워드 추출 |

---

## 시나리오

![3 Text Processing Scenarios](../../docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png)

---

## 핵심 기능

### AbuseWordFilter — AhoCorasick 다중 키워드 검색

생성 시점에 불변이고 스레드 안전한 Aho-Corasick automaton을 구성합니다. 이후 모든
`containsAbuse`, `filterText`, `findMatches` 호출은 등록된 키워드 수와 관계없이
**O(text length + matches)** 시간에 실행됩니다.

```kotlin
val filter = AbuseWordFilter(listOf("spam", "abuse", "badword"))

filter.containsAbuse("There is some spam here")   // true
filter.filterText("No spam allowed")              // "No **** allowed"
filter.findMatches("spam and abuse")              // 2 AhoCorasickMatch objects
```

**Before / After — AhoCorasick이 단순한 `String.contains` 반복보다 나은 이유:**

```kotlin
// Naive approach — O(keywords × text) — scans the text once per keyword
val words = listOf("spam", "abuse", "hate", /* ... 1000 more ... */)
val found = words.any { text.contains(it, ignoreCase = true) }

// AhoCorasick — O(text + matches) — one pass regardless of keyword count
val filter = AbuseWordFilter(words)
val found = filter.containsAbuse(text)
```

10,000자 메시지에 키워드 1,000개가 있으면 단순한 방식은 최대
10,000,000번의 문자 비교를 수행합니다. automaton은 정확히 10,000번만 수행합니다.

### LanguageDetectionService — Lingua 기반 언어 감지

[Lingua](https://github.com/pemistahl/lingua) 언어 감지기를 감쌉니다. 내부
언어 모델은 한 번 로드된 뒤 모든 호출에서 재사용됩니다.

```kotlin
val service = LanguageDetectionService()

service.detectLanguage("Hello, World!")     // Language.ENGLISH
service.detectLanguage("Hello.") // Language.KOREAN
service.detectLanguage("東京は首都です。")    // Language.JAPANESE

// Confidence values for all plausible languages
val scores = service.computeConfidenceValues("This is English text.")
// { ENGLISH -> 0.97, DUTCH -> 0.01, ... }
```

### TextNormalizer — 가벼운 텍스트 정규화

색인 또는 검색 전에 텍스트를 전처리하기 위한 상태 없는 `object`입니다.

```kotlin
TextNormalizer.normalize("  Hello   WORLD  ")      // "hello world"
TextNormalizer.extractKeywords("the quick brown fox")   // ["the", "quick", "brown", "fox"]
TextNormalizer.extractKeywords("a b quick", minKeywordLength = 4) // ["quick"]
```

---

## 구성

외부 서비스는 필요하지 않습니다. 모든 처리는 프로세스 안에서 실행됩니다.

`bluetape4k-text` 라이브러리는 `io.github.bluetape4k.text` 아래에서 독립적으로 버전이 관리됩니다.

| Dependency | Version |
|---|---|
| `io.github.bluetape4k.text:text-search` | 0.1.2 |
| `io.github.bluetape4k.text:lingua` | 0.1.2 |

---

## 의존성 설정

`build.gradle.kts`에 추가합니다.

```kotlin
dependencies {
    implementation(libs.bluetape4k.text.search)
    implementation(libs.bluetape4k.text.lingua)
}
```

version catalog 항목이 있는지 확인합니다.

```toml
[versions]
bluetape4k-text = "0.1.2"

[libraries]
bluetape4k-text-search = { module = "io.github.bluetape4k.text:text-search", version.ref = "bluetape4k-text" }
bluetape4k-text-lingua = { module = "io.github.bluetape4k.text:lingua", version.ref = "bluetape4k-text" }
```

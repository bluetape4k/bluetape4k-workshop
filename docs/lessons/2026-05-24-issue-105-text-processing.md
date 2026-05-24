# Issue #105 — kotlin/text-processing 모듈 구현 회고

날짜: 2026-05-24  
브랜치: `feat/issue-105-text-processing`  
관련 이슈: [#105](https://github.com/bluetape4k/bluetape4k-workshop/issues/105)

---

## 목표

`bluetape4k-text` 라이브러리를 활용한 텍스트 처리 예제 모듈 `kotlin/text-processing` 생성.

구현 대상:
1. `AbuseWordFilter` — AhoCorasick 기반 금칙어 필터
2. `LanguageDetectionService` — Lingua 기반 언어 감지
3. `TextNormalizer` — 기본 텍스트 정규화 / 키워드 추출

---

## 주요 발견 사항

### bluetape4k-text 는 독립 버저닝

`bluetape4k-text` 라이브러리는 메인 `bluetape4k` BOM과 별개로 `io.github.bluetape4k.text` 그룹으로
독립 배포된다. 버전도 별도 관리 (`0.1.x`).

**workshop `libs.versions.toml`에 이미 선언되어 있었으나 버전 참조가 없었다:**

```toml
# 기존 — 버전 없음 (FAILED)
bluetape4k-text-search = { module = "io.github.bluetape4k.text:text-search" }
```

`bluetape4k-text = "0.1.2"` 버전 항목을 추가하고 기존 선언에 `version.ref`를 보완하여 해결.

### 최신 릴리즈 버전 확인 방법

```
https://repo1.maven.org/maven2/io/github/bluetape4k/text/text-search/maven-metadata.xml
```

- SNAPSHOT 최신: `0.1.3-SNAPSHOT` (central snapshots)
- 릴리즈 최신: `0.1.2` (Maven Central)

workshop에서는 안정 릴리즈인 `0.1.2` 사용.

### AhoCorasick DSL API

```kotlin
val automaton = ahoCorasick<String> {
    ignoreCase = true
    allowOverlaps = true
    normalization = NormalizationForm.NFC
    keyword("spam", "spam")
}
automaton.containsMatch(text)          // 존재 여부 (조기 종료)
automaton.parseText(text)              // List<AhoCorasickMatch<V>>
automaton.replaceAll(text) { "***" }   // 치환
```

`ahoCorasickOf(vararg keywords)` 헬퍼도 있으나, 커스텀 SearchOptions가 필요할 때는
`ahoCorasick<V> { }` DSL이 더 깔끔하다.

### Lingua API

`LanguageDetector` 생성은 비용이 크다 — 반드시 **하나의 인스턴스를 재사용**해야 한다.

```kotlin
val detector = allLanguageDetector {
    withMinimumRelativeDistance(0.0)
    withLowAccuracyMode()
}
detector.detectLanguageOf(text)                    // Language enum
detector.computeLanguageConfidenceValues(text)     // Map<Language, Double>
```

테스트에서는 `@TestInstance(PER_CLASS)`를 활용해 detector를 클래스 수준에서 한 번만 생성.

### bluetape4k-assertions 임포트

workshop 모듈에서 `kluent` (org.amshove.kluent)는 사용하지 않는다.
`bluetape4k-assertions` 의 import 경로는 `io.bluetape4k.assertions.*`.

```kotlin
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeTrue
```

---

## 실수 / 주의사항

1. **toml 중복 선언**: 내가 추가하기 전에 이미 `bluetape4k-text-search`, `bluetape4k-text-lingua`
   항목이 존재했다. 중복 추가하여 `TOML catalog definition` 오류가 발생. 기존 항목에 `version.ref`만
   추가하는 방식으로 수정.

2. **버전 오판**: `gradle.properties`의 `baseVersion=0.1.3`을 보고 `0.1.3`을 넣었다가
   Maven Central에는 아직 `0.1.2`까지만 릴리즈된 것을 확인. 릴리즈 여부는 반드시
   `maven-metadata.xml`로 확인해야 한다.

3. **Lingua 모델 로딩 시간**: `withLowAccuracyMode()`를 사용하면 모델 로딩이 빨라진다.
   `withPreloadedLanguageModels()`는 정확도는 높지만 첫 빌드/테스트가 느려진다.

---

## 테스트 결과

```
26 tests passing (1.3s)
- AbuseWordFilterTest:        9 tests
- LanguageDetectionServiceTest: 7 tests
- TextNormalizerTest:         10 tests
```

명령어: `./gradlew :kotlin-text-processing:test`

---

## 참고 링크

- [bluetape4k-text GitHub](https://github.com/bluetape4k/bluetape4k-text)
- [Lingua 언어 감지](https://github.com/pemistahl/lingua)
- [Aho-Corasick 알고리즘 위키](https://en.wikipedia.org/wiki/Aho%E2%80%93Corasick_algorithm)

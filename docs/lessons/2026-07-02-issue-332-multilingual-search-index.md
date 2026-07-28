# Issue 332 Multilingual Search Index

## 배경

Issue #332는 multilingual search indexing과 highlighting을 위한 `kotlin/text-processing`
workshop extension을 추가한다. 목표는 기존 text-processing example을 대체하지 않고
bluetape4k text ecosystem을 재사용하면서 learner clarity를 제공하는 것이다.

## 결정

- learner가 database 또는 service setup 없이 text-processing pipeline을 검사할 수 있도록 example을
  in memory로 유지한다.
- Lingua detector construction은 비용이 크므로 document와 query에 `LanguageDetectionService`를
  재사용한다.
- language-specific tokenizer를 사용한다.
  - Korean: `KoreanProcessor.normalize` plus noun tokens.
  - Japanese: `JapaneseProcessor.tokenize` plus noun filtering.
  - English/unknown: `TextNormalizer.extractKeywords`.
- deterministic source-span match와 `<mark>` rendering에는 Aho-Corasick을 사용한다.
- id가 index key이므로 duplicate `SearchDocument.id` value를 거부한다.
- 단순 예제에는 synchronous `MultilingualSearchIndex`를 유지하고, coroutine caller를 위해
  별도 `CoroutineMultilingualSearchIndex`를 추가한다. coroutine variant는
  `CoroutineLanguageDetectionService`로 detector access를 serialize하고 immutable index
  snapshot을 사용해 shared mutable state를 피한다.
- coroutine example을 finalize하기 전에 `bluetape4k-code-patterns`를 적용한다.
  coroutine-heavy code에는 `KLoggingChannel`, exception assertion에는 `assertFailsWith`,
  deterministic stress-test input, same-typed public API example에는 named argument를 사용한다.

## 결과

module은 이제 `MultilingualSearchIndex`, `CoroutineMultilingualSearchIndex`,
`CoroutineLanguageDetectionService`, `SearchDocument`, `IndexedDocument`,
`SearchHighlightMatch`, `SearchHighlightHit`를 포함한다. English, Korean, Japanese,
no-match, case normalization, overlap, language recording, duplicate id, concurrent suspend
search에 대한 test도 포함한다.

## 검증

- `:kotlin-text-processing:compileKotlin`
- `:kotlin-text-processing:compileTestKotlin`
- `:kotlin-text-processing:test` with 37 passing tests
- `SuspendedJobTester` coroutine stress test for shared-index concurrent search
- Code-pattern repair grep checks for assertion, logging, deterministic concurrency, and same-typed argument call sites
- README stale/image checks
- Full bluetape4k diagram checklist plus rendered PNG eye check

## 향후 참고

이 example이 in-memory search를 넘어 확장되더라도 먼저 같은 visible contract를 보존한다.
계약은 language detection reuse, tokenizer choice, source offset, explicit highlight
limitation이다.

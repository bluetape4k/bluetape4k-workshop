# Issue 332 Multilingual Search Index Code Review

날짜: 2026-07-02
범위: `kotlin/text-processing` multilingual search index example, coroutine-safe search variant, README parity, README diagram.

## 7-Tier Findings

남은 P0/P1: 없음.

PR 전에 해결됨:

- 중복 `SearchDocument.id` 값은 `indexedDocuments`와 `documentsById`를 서로 다르게 만들 수 있었다. `MultilingualSearchIndex.indexOf(...)`에서 duplicate id를 거부하고 `rejects duplicate document ids`를 추가하여 수정했다.
- 원래 synchronous search index는 read-mostly이지만 shared detector access에 대한 명시적 coroutine/thread-safety contract가 없었다. 이를 그대로 유지하고 immutable index snapshot과 `Mutex`-guarded detector access를 사용하는 `CoroutineMultilingualSearchIndex` 및 `CoroutineLanguageDetectionService`를 추가했다.
- follow-up code-pattern repair는 ad hoc suspend exception try/catch를 `io.bluetape4k.assertions.assertFailsWith`로 교체하고, coroutine-heavy logging을 `KLoggingChannel`로 바꾸었으며, concurrency stress test에서 random query selection을 제거하고, touched `SearchDocument.of(...)` example/test를 named argument로 변경했다.

## 근거

- `./gradlew :kotlin-text-processing:compileKotlin :kotlin-text-processing:compileTestKotlin :kotlin-text-processing:cleanTest :kotlin-text-processing:test --no-build-cache --warning-mode all --console=plain`
  - 결과: BUILD SUCCESSFUL
  - 결과: 8개 `MultilingualSearchIndexTest` 테스트와 3개 `CoroutineMultilingualSearchIndexTest` 테스트를 포함해 37 tests executed.
  - Coroutine 근거: `SuspendedJobTester` stress test는 하나의 shared coroutine index와 guarded detector wrapper에 대해 concurrent suspend `search(...)` call을 실행한다.
- Code-pattern grep check: touched coroutine search file에는 ad hoc `try/catch` exception assertion, `queries.random()`, repeated `shouldNotBeNull()`, `KLogging()`이 없다. `kotlin/text-processing`에는 `SearchDocument.of("...")` positional string example이 남아 있지 않다.
- `git diff --check`: PASS
- `./scripts/smoke-validate.sh stale-check`: PASS, 100 active modules, stale ref 없음, broken README image link 없음.
- Diagram checklist:
  - `xmllint --noout`: PASS
  - `node scripts/validate-readme-architecture-diagrams.mjs`: PASS
  - `node scripts/validate-sequence-diagrams.mjs`: PASS
  - `node scripts/validate-readme-diagram-qa.mjs`: PASS
  - `diagram-geometry-audit.py`: PASS, geometry failures 0
  - `diagram-endpoint-audit.py`: PASS
  - `diagram-mixed-corner-audit.py`: PASS
  - `diagram-connector-audit.py`: PASS, 8 architecture connectors and 6 scenario connectors with 0 intrusions/crossings.
- Rendered PNG eye check:
  - `kotlin-text-processing-readme-architecture-01.png`: sync/coroutine API labeling을 추가하고 centered text, arrow direction, card alignment, broken render 없음 확인 후 PASS.
  - `kotlin-text-processing-scenario-01.png`: step 7이 lane 안에 머물도록 search lane을 확장한 뒤 PASS.

## 잔여 위험

- 이는 in-memory teaching example이며 production search engine이 아니다. README는 literal highlighting만 지원하며 stemming, semantic search, typo tolerance가 없다는 점을 명시한다.

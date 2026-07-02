# Issue 332 Multilingual Search Index Code Review

Date: 2026-07-02
Scope: `kotlin/text-processing` multilingual search index example, coroutine-safe search variant, README parity, and README diagrams.

## 7-Tier Findings

P0/P1: none remaining.

Resolved before PR:

- Duplicate `SearchDocument.id` values could make `indexedDocuments` and `documentsById` disagree. Fixed by rejecting duplicate ids during `MultilingualSearchIndex.indexOf(...)` and adding `rejects duplicate document ids`.
- The original synchronous search index is read-mostly but does not provide an explicit coroutine/thread-safety contract for shared detector access. Kept it unchanged and added `CoroutineMultilingualSearchIndex` plus `CoroutineLanguageDetectionService` with an immutable index snapshot and `Mutex`-guarded detector access.
- Follow-up code-pattern repair replaced ad hoc suspend exception try/catch with `io.bluetape4k.assertions.assertFailsWith`, switched coroutine-heavy logging to `KLoggingChannel`, removed random query selection from the concurrency stress test, and changed touched `SearchDocument.of(...)` examples/tests to named arguments.

## Evidence

- `./gradlew :kotlin-text-processing:compileKotlin :kotlin-text-processing:compileTestKotlin :kotlin-text-processing:cleanTest :kotlin-text-processing:test --no-build-cache --warning-mode all --console=plain`
  - Result: BUILD SUCCESSFUL
  - Result: 37 tests executed, including 8 `MultilingualSearchIndexTest` tests and 3 `CoroutineMultilingualSearchIndexTest` tests.
  - Coroutine evidence: `SuspendedJobTester` stress test runs concurrent suspend `search(...)` calls against one shared coroutine index and guarded detector wrapper.
- Code-pattern grep checks: no touched coroutine search files contain ad hoc `try/catch` exception assertions, `queries.random()`, repeated `shouldNotBeNull()`, or `KLogging()`; no `SearchDocument.of("...")` positional string examples remain in `kotlin/text-processing`.
- `git diff --check`: PASS
- `./scripts/smoke-validate.sh stale-check`: PASS, 100 active modules, no stale refs, no broken README image links.
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
  - `kotlin-text-processing-readme-architecture-01.png`: PASS after adding sync/coroutine API labeling and verifying centered text, arrow direction, card alignment, and no broken render.
  - `kotlin-text-processing-scenario-01.png`: PASS after expanding the search lane so step 7 stays inside the lane.

## Residual Risk

- This is an in-memory teaching example, not a production search engine. README explicitly calls out literal highlighting and no stemming, semantic search, or typo tolerance.

# Issue 332 Multilingual Search Index

## Context

Issue #332 adds a `kotlin/text-processing` workshop extension for multilingual search indexing and highlighting. The goal is learner clarity while reusing the bluetape4k text ecosystem instead of replacing the existing text-processing example.

## Decision

- Keep the example in memory so learners can inspect the text-processing pipeline without database or service setup.
- Reuse `LanguageDetectionService` for documents and queries because Lingua detector construction is expensive.
- Use language-specific tokenizers:
  - Korean: `KoreanProcessor.normalize` plus noun tokens.
  - Japanese: `JapaneseProcessor.tokenize` plus noun filtering.
  - English/unknown: `TextNormalizer.extractKeywords`.
- Use Aho-Corasick for deterministic source-span matches and `<mark>` rendering.
- Reject duplicate `SearchDocument.id` values because ids are the index key.
- Keep the synchronous `MultilingualSearchIndex` for simple examples and add a separate `CoroutineMultilingualSearchIndex` for coroutine callers. The coroutine variant uses `CoroutineLanguageDetectionService` to serialize detector access and immutable index snapshots to avoid shared mutable state.
- Apply `bluetape4k-code-patterns` before finalizing coroutine examples: use `KLoggingChannel` for coroutine-heavy code, `assertFailsWith` for exception assertions, deterministic stress-test inputs, and named arguments for same-typed public API examples.

## Outcome

The module now includes `MultilingualSearchIndex`, `CoroutineMultilingualSearchIndex`, `CoroutineLanguageDetectionService`, `SearchDocument`, `IndexedDocument`, `SearchHighlightMatch`, and `SearchHighlightHit`, with tests for English, Korean, Japanese, no-match, case normalization, overlaps, language recording, duplicate ids, and concurrent suspend search.

## Verification

- `:kotlin-text-processing:compileKotlin`
- `:kotlin-text-processing:compileTestKotlin`
- `:kotlin-text-processing:test` with 37 passing tests
- `SuspendedJobTester` coroutine stress test for shared-index concurrent search
- Code-pattern repair grep checks for assertion, logging, deterministic concurrency, and same-typed argument call sites
- README stale/image checks
- Full bluetape4k diagram checklist plus rendered PNG eye check

## Future Notes

If this example grows beyond in-memory search, preserve the same visible contracts first: language detection reuse, tokenizer choice, source offsets, and explicit highlight limitations.

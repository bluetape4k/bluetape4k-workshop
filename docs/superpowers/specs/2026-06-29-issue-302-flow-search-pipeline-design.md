# Issue 302 Flow Search Pipeline Design

## Problem

Issue #302 asks for a workshop example that teaches a real-time search/autocomplete pipeline with `bluetape4k-coroutines` Flow extensions. The example should help learners replace mutable query state, ad hoc `Job` cancellation, external setting reads, and broad `try/catch` blocks with a readable Flow composition.

The example is in-memory. It should not add HTTP, database, cache, or Testcontainers infrastructure. The learner-facing surface is a small module under `kotlin/`, bilingual README files, source-backed diagrams, and deterministic coroutine tests.

## Source Evidence

- Existing Flow workshop modules live under `kotlin/flow-extensions-*` and use small in-memory domains, module-local README pairs, JUnit 5 coroutine tests, and root README registration.
- `bufferingDebounce(timeout)` batches items during a debounce window and emits the buffered list. If upstream fails, it emits the buffered values before propagating the original failure.
- `withLatestFrom(other)` combines a source value with the latest item from another Flow. It emits nothing until `other` has emitted at least once.
- `takeUntil(other)` stops the source Flow when a notifier Flow emits.
- `Flow<T>.log(tag)` adds debug lifecycle logging without changing the Flow value contract. Values that pass through `log` must have redacted string rendering because learners often copy debug hooks into real services.
- `flatMapDrop` and `flatMapFirst` are not supersede operators. Their current contract is to ignore new upstream values while an inner Flow is running.
- Kotlin standard `flatMapLatest` already appears in `kotlin/coroutines` examples and has the desired autocomplete supersede contract: a newer query cancels the previous in-flight search.

## Design

Create `kotlin/flow-extensions-search-pipeline` as an in-memory Kotlin module.

The main type is `SearchPipeline`. It accepts:

- `queries: Flow<String>`
- `settings: Flow<SearchSettings>`
- `sessionClosed: Flow<Unit>`

The `settings` Flow is a caller-owned hot/state-like Flow that has emitted an initial value before query events are collected. The README must show `MutableStateFlow(initialSettings)` as the safe setup and explain that `withLatestFrom(settings)` drops query values until the first settings value exists.

The `sessionClosed` Flow may be cold at the API boundary, but `SearchPipeline` must normalize it once per collection into a single shared stop signal. The adapter cancellation race and the outer terminal guard both observe that shared signal so a one-shot close is not double-collected or missed.

The pipeline performs:

1. Trim and filter blank query input.
2. Use `bufferingDebounce` to collect fast typing bursts.
3. Select the latest query from each non-empty burst.
4. Use `withLatestFrom(settings)` so every search uses the latest tenant, locale, mode, result limit, and feature flags.
5. Use `flatMapLatest` for autocomplete supersede semantics. A newer query cancels the previous in-flight search.
6. Race each suspend adapter search against `sessionClosed` so session closure cancels the active search before it can return.
7. Use `takeUntil(sessionClosed)` as the outer terminal guard for request/result flow after the cancellation-aware adapter race.
8. Use `Flow.log()` as a teachable debug hook only after domain values provide redacted `toString()` output; raw query text, tenant ids, feature flags, source metadata, and result contents must not appear in debug lifecycle logs.

The search adapter boundary is explicit: `SearchPipeline` depends on `SearchAdapter`, while `FakeSearchAdapter` is the example implementation. The fake adapter is suspend-aware, uses `delay` for simulated latency, never blocks with `Thread.sleep`, and exposes deterministic hooks for tests to prove cancellation and failure behavior without real external services. It searches a fixed in-memory catalog with bounded plain string matching only; it must not compile learner-controlled regex, evaluate SQL-like expressions, use reflection, run scripts, or build dynamic query DSLs from raw input.

## Domain Model

- `SearchQuery`: normalized non-blank query text, trimmed through the public factory and capped at 64 characters.
- `SearchSettings`: tenant id, locale, search mode, feature flags, result limit.
- `SearchRequest`: query plus settings at the time the request starts.
- `SearchResult`: query text, settings snapshot, ranked hit list, and source metadata.
- `SearchHit`: id, title, and score.
- `SearchMode`: `PREFIX`, `FUZZY`, `EXACT`.

Caller-visible invariants:

- `SearchQuery.text` is trimmed, non-blank, and at most 64 characters. Public construction stores the canonical trimmed value.
- `SearchSettings.tenantId` is trimmed, non-blank, and at most 64 characters. Public construction stores the canonical trimmed value.
- `SearchSettings.resultLimit` is in `1..20`; the fake adapter applies this limit before materializing returned hits.
- `SearchSettings.featureFlags` contains at most 8 lowercase kebab-case names matching `[a-z][a-z0-9-]{1,31}`.
- `SearchSettings.mode` controls fixed catalog matching only: prefix, exact, or bounded fuzzy containment.

Serializable domain classes implement `java.io.Serializable` and define an explicit `serialVersionUID`. `SearchQuery` and `SearchSettings` use construction patterns that cannot bypass normalization through generated `copy(...)` methods. Serializable is for repo convention only; this example does not demonstrate persistence or untrusted object deserialization. Same-typed fields remain named properties inside domain types rather than positional method parameters.

## Rejected Approaches

1. Use `flatMapDrop` or `flatMapFirst` for stale request handling.
   - Rejected because the actual contract drops newer queries while the current search runs. That teaches exhaust/drop semantics, not autocomplete supersede.
2. Implement manual `MutableSharedFlow` plus external mutable `SearchSettings`.
   - Rejected because it reproduces the boilerplate the issue wants to replace.
3. Add HTTP/WebSocket or database infrastructure.
   - Rejected because the learning goal is Flow composition, not integration plumbing.

## Risks And Mitigations

- **Supersede semantics drift**: README and tests explicitly distinguish `flatMapLatest` from `flatMapDrop`.
- **Timing-flaky tests**: tests use coroutine test helpers and deterministic adapter hooks; no sleep-based assertions for cancellation.
- **Cancellation swallowed by broad handlers**: adapter and pipeline do not wrap suspend calls in `runCatching`; tests cancel real jobs and verify cancellation evidence.
- **Settings race confusion**: tests prove a query combines with the latest emitted settings, prove no search starts before the first settings emission, and README names the `withLatestFrom` first-emission behavior.
- **Sensitive diagnostic logs**: domain `toString()` output is redacted before `Flow.log()` is used; tests assert raw query, tenant id, feature flags, source metadata, and result titles are absent from rendered log values.
- **Burst allocation drift**: a lightweight large-burst stress test verifies 1,000 rapid inputs produce one adapter request/result without sleep-based timing.
- **Diagram drift**: diagrams are generated as SVG+PNG, audited with XML, geometry, endpoint, validator, contact sheet, and full-size visual inspection.

## Acceptance Criteria Mapping

| Issue criterion | Design response |
|---|---|
| Runnable/testable example uses Bluetape4k Flow extensions | `SearchPipeline` uses `bufferingDebounce`, `withLatestFrom`, `takeUntil`, and redaction-safe `Flow.log()` in the happy path |
| Before/After manual vs extension chain | README.md and README.ko.md include a short anti-pattern baseline for manual `MutableSharedFlow`/`Job` cancellation and contrast it with the Bluetape4k-first chain |
| Burst input | Test verifies burst queries collapse to the latest query in a debounce batch |
| Large burst/backpressure | Test verifies 1,000 rapid inputs still produce one adapter request/result |
| Latest settings composition | Test verifies request uses the latest settings emitted before the query |
| Settings first emission | Test verifies no search starts before the first settings emission and README shows seeded settings |
| Session stop | Test verifies the shared session stop cancels the active in-flight search through the adapter race, and `takeUntil(sharedSessionClosed)` terminates the outer result flow |
| Upstream failure propagation | Test verifies original upstream failure propagates after buffered values are handled |
| Cancellation | Test verifies `flatMapLatest` cancels an in-flight search when a newer query arrives |
| Safe input contract | Tests verify query/settings validation, literal matching of regex-looking input, result limit capping, and redacted debug rendering |
| README feature table | README files include feature, artifact, code reference, and benefit |
| Validation commands | README files include module test and compile commands |

## Documentation And Diagrams

README language set:

- `kotlin/flow-extensions-search-pipeline/README.md`
- `kotlin/flow-extensions-search-pipeline/README.ko.md`

Diagram assets:

- Scenario: typing burst, live settings, session closure.
- Architecture: UI query stream, Flow pipeline, fake search adapter, result stream.
- Domain model: query/settings/request/result/hit.
- Sequence: burst -> debounce -> settings join -> supersede search -> stop.

Generated diagram labels use English. Korean README prose should be natural technical Korean, not a literal translation.

Both READMEs include a scope note: no HTTP/WebSocket, no database/cache, no auth/authz, no production ranking, no distributed cancellation protocol, and no untrusted object deserialization. The manual baseline is labeled as an anti-pattern contrast, not as the copy target. Both READMEs also include diagnostics/operational notes for the `search-pipeline` log tag, cancellation evidence, and the no-resource-ownership rollback model.

## Validation Plan

- `./gradlew :kotlin-flow-extensions-search-pipeline:test --rerun-tasks --console=plain`
- `./gradlew :kotlin-flow-extensions-search-pipeline:compileKotlin :kotlin-flow-extensions-search-pipeline:compileTestKotlin --console=plain`
- `./gradlew projects --console=plain`
- README validators: parity, language, architecture diagram validator, sequence diagram validator.
- Diagram validation: SVG XML parse, CairoSVG render, geometry audit, endpoint audit, contact sheet, full-size PNG visual inspection.
- `actionlint .github/workflows/Examples.yml` if workflow is changed.
- `git diff --check`

## DoD

- Module is registered by existing `settings.gradle.kts` auto-include.
- Tests cover the issue acceptance paths, safe input/logging boundaries, settings first-emission behavior, in-flight session cancellation, large-burst stress evidence, and the `flatMapDrop`/`flatMapLatest` semantic decision in docs.
- README.md and README.ko.md are source-equivalent and include a used-features table.
- Diagrams are source-backed, readable, and pass the current `$bluetape4k-diagram` checklist.
- Examples smoke workflow includes `:kotlin-flow-extensions-search-pipeline:test`.
- Step 6-R and Step 7-R reviews converge with P0 = 0 and P1 = 0.

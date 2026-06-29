# Flow Search Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build issue #302 as a new in-memory Flow search/autocomplete workshop example.

**Architecture:** Add `kotlin/flow-extensions-search-pipeline` with a focused `SearchPipeline` that composes Bluetape4k Flow extensions for burst buffering, latest settings, session stop, and debug logging. Use Kotlin standard `flatMapLatest` for true autocomplete supersede semantics because Bluetape4k `flatMapDrop`/`flatMapFirst` intentionally drops newer requests while work is busy.

**Tech Stack:** Kotlin/JVM, Java 21, `bluetape4k-coroutines`, `bluetape4k-junit5`, `bluetape4k-assertions`, `kotlinx-coroutines-test`, CairoSVG-rendered README diagrams.

---

## File Structure

- Create `kotlin/flow-extensions-search-pipeline/build.gradle.kts`: dependencies matching sibling Flow modules.
- Create `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchDomain.kt`: serializable domain values and enums; use value/regular classes where generated `copy(...)` would bypass validation.
- Create `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchAdapter.kt`: explicit suspend adapter boundary.
- Create `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/FakeSearchAdapter.kt`: deterministic suspend search adapter for examples/tests.
- Create `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchPipeline.kt`: Flow extension chain.
- Create `kotlin/flow-extensions-search-pipeline/src/test/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchPipelineTest.kt`: acceptance tests.
- Create test resources `junit-platform.properties` and `logback-test.xml`.
- Create `kotlin/flow-extensions-search-pipeline/README.md` and `README.ko.md`.
- Modify root `README.md` and `README.ko.md` Async & Reactive tables.
- Create README diagram assets under `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-*.svg/png`.
- Modify diagram validator allowlists when new architecture/sequence diagrams are added.
- Modify `.github/workflows/Examples.yml` and `scripts/smoke-validate.sh` so this test-bearing in-memory Flow module joins smoke example coverage.
- Create `docs/review/2026-06-29-issue-302-flow-search-pipeline-review.md`.
- Create `docs/lessons/2026-06-29-issue-302-flow-search-pipeline.md`.

## Task 1: Module Skeleton And Domain

**Complexity:** medium  
**Applies:** `$bluetape4k-code-patterns`

**Files:**
- Create: `kotlin/flow-extensions-search-pipeline/build.gradle.kts`
- Create: `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchDomain.kt`
- Create: `kotlin/flow-extensions-search-pipeline/src/test/resources/junit-platform.properties`
- Create: `kotlin/flow-extensions-search-pipeline/src/test/resources/logback-test.xml`

- [ ] Add Gradle dependencies:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.logback.lib)
}
```

- [ ] Add serializable domain model with constructor-level validation and redacted string rendering:

```kotlin
package io.bluetape4k.workshop.flow.search.pipeline

import java.io.Serializable
import java.util.Collections
import java.util.Locale
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank

enum class SearchMode {
    PREFIX,
    FUZZY,
    EXACT,
}

class SearchQuery private constructor(
    val text: String,
): Serializable {
    override fun toString(): String = "SearchQuery(text=<redacted>, length=${text.length})"

    override fun equals(other: Any?): Boolean =
        this === other || other is SearchQuery && text == other.text

    override fun hashCode(): Int = text.hashCode()

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(text: String): SearchQuery {
            val normalized = text.trim()
            normalized.requireNotBlank("text")
            normalized.length.requireInRange(1, 64, "text.length")
            return SearchQuery(normalized)
        }
    }
}

class SearchSettings private constructor(
    val tenantId: String,
    val locale: Locale,
    val mode: SearchMode,
    val featureFlags: Set<String>,
    val resultLimit: Int,
): Serializable {
    override fun toString(): String =
        "SearchSettings(tenant=<redacted>, locale=$locale, mode=$mode, flags=${featureFlags.size}, resultLimit=$resultLimit)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SearchSettings &&
            tenantId == other.tenantId &&
            locale == other.locale &&
            mode == other.mode &&
            featureFlags == other.featureFlags &&
            resultLimit == other.resultLimit

    override fun hashCode(): Int =
        listOf(tenantId, locale, mode, featureFlags, resultLimit).hashCode()

    companion object {
        private const val serialVersionUID: Long = 1L
        private val featureFlagPattern = Regex("[a-z][a-z0-9-]{1,31}")

        operator fun invoke(
            tenantId: String,
            locale: Locale,
            mode: SearchMode,
            featureFlags: Set<String>,
            resultLimit: Int,
        ): SearchSettings {
            val normalizedTenantId = tenantId.trim()
            val normalizedFlags = featureFlags.map { it.trim() }.toSet()

            normalizedTenantId.requireNotBlank("tenantId")
            normalizedTenantId.length.requireInRange(1, 64, "tenantId.length")
            resultLimit.requireInRange(1, 20, "resultLimit")
            require(normalizedFlags.size <= 8) { "featureFlags must contain at most 8 values" }
            require(normalizedFlags.all { it.matches(featureFlagPattern) }) {
                "featureFlags must be lowercase kebab-case names"
            }

            return SearchSettings(
                tenantId = normalizedTenantId,
                locale = locale,
                mode = mode,
                featureFlags = Collections.unmodifiableSet(normalizedFlags),
                resultLimit = resultLimit,
            )
        }
    }
}

data class SearchRequest(
    val query: SearchQuery,
    val settings: SearchSettings,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class SearchHit(
    val id: String,
    val title: String,
    val score: Int,
): Serializable {
    override fun toString(): String = "SearchHit(id=$id, title=<redacted>, score=$score)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class SearchResult(
    val request: SearchRequest,
    val hits: List<SearchHit>,
    val source: String,
): Serializable {
    override fun toString(): String =
        "SearchResult(query=<redacted>, tenant=<redacted>, hits=${hits.size}, source=<redacted>)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] Make normalization part of the public construction path:
  - Use regular serializable classes with private constructors plus companion `operator fun invoke(...)` factories for `SearchQuery` and `SearchSettings`; do not use `data class` for these two types because generated `copy(...)` can bypass validation.
  - The stored `SearchQuery.text` and `SearchSettings.tenantId` values are the trimmed canonical values.
  - `SearchSettings.featureFlags` is a defensive immutable copy of normalized flags, so mutating a caller-owned mutable set cannot change settings after construction.
  - README examples create `SearchSettings(tenantId = "demo-tenant", ...)` with valid limits and flags.
- [ ] Add a note that `Serializable` is a repository convention here; the example does not deserialize untrusted bytes and does not teach `ObjectInputStream`.
- [ ] Add standard test resources by copying the small Flow module patterns.
- [ ] Run module registration check after the module exists:

```bash
./gradlew projects --console=plain
```

Expected evidence: `:kotlin-flow-extensions-search-pipeline` appears.

## Task 2: Failing Pipeline Tests

**Complexity:** high  
**Applies:** `$bluetape4k-code-patterns`, `$test-driven-development`

**Files:**
- Create: `kotlin/flow-extensions-search-pipeline/src/test/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchPipelineTest.kt`

- [ ] Add tests before implementation. Test names:
  - ``buffering debounce emits latest query from a typing burst``
  - ``large burst still starts one search``
  - ``latest settings are joined with each debounced query``
  - ``query before first settings emission is not searched``
  - ``query before settings is dropped permanently and next query uses settings snapshot``
  - ``settings flow failure propagates downstream``
  - ``newer query cancels stale in-flight search``
  - ``cancelled stale request cannot emit or fail downstream later``
  - ``session close cancels in-flight search``
  - ``session close source is collected once and shared across stop observers``
  - ``collector cancellation cleans up active search and stop observer``
  - ``upstream failure is propagated after buffered query handling``
  - ``blank input is ignored before search starts``
  - ``domain values reject invalid query settings and limits``
  - ``domain construction stores trimmed query and tenant values``
  - ``domain construction cannot bypass validation through copy``
  - ``settings defensively copy caller owned feature flags``
  - ``settings expose unmodifiable feature flags``
  - ``debounce duration must be positive``
  - ``regex looking query is treated as literal text``
  - ``debug string rendering hides query tenant flags and hit titles``
  - ``result limit caps hit materialization before returning hits``

- [ ] Use `io.bluetape4k.junit5.coroutines.runSuspendTest` where virtual-time friendly.
- [ ] Use `io.bluetape4k.assertions.assertFailsWith`, `shouldBeEqualTo`, `shouldHaveSize`, and dot-call null/boolean matchers.
- [ ] Do not use JUnit, AssertJ, Kluent, or `kotlin.test` assertions.
- [ ] For cancellation evidence, use adapter-local `AtomicInteger`/`AtomicBoolean` hooks and a completion gate rather than sleep-based assertions. `SuspendedJobTester` is not required for this module because the acceptance target is operator cancellation of one pipeline collection, not stress or race testing.
- [ ] For large-burst evidence, feed 1,000 rapid query values in one debounce window and assert exactly one adapter request/result without sleep-based timing.
- [ ] For virtual-time burst tests, emit every burst value before advancing time, use a generous debounce window, then call `advanceTimeBy` and `advanceUntilIdle`; do not use wall-clock waits or timeout-based assertions.
- [ ] For settings-first behavior, emit a query before first settings, then emit settings, assert zero adapter calls, then emit a new query and assert exactly one request with that settings snapshot. Show the safe setup with seeded settings in README/KDoc.
- [ ] For stale-search cancellation, give each request its own adapter gate. Force request A to suspend, start request B, assert A is cancelled before B emits, then resume or fail A and verify no stale result or stale exception reaches the collector.

## Task 3: Adapter And Pipeline Implementation

**Complexity:** high  
**Applies:** `$bluetape4k-code-patterns`

**Files:**
- Create: `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchAdapter.kt`
- Create: `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/FakeSearchAdapter.kt`
- Create: `kotlin/flow-extensions-search-pipeline/src/main/kotlin/io/bluetape4k/workshop/flow/search/pipeline/SearchPipeline.kt`

- [ ] Define `fun interface SearchAdapter { suspend fun search(request: SearchRequest): SearchResult }`.
- [ ] Implement `FakeSearchAdapter` with a suspend `search(request: SearchRequest): SearchResult`.
- [ ] `FakeSearchAdapter` uses a fixed in-memory catalog and plain bounded string matching only; no regex compiled from raw input, SQL-like DSL, reflection, scripting, or expression evaluation.
- [ ] `FakeSearchAdapter` applies `request.settings.resultLimit` before materializing returned hits with a bounded sequence path such as `matches.asSequence().take(resultLimit).map(...).toList()` or an equivalent materialization counter hook.
- [ ] `FakeSearchAdapter` uses coroutine `delay` for simulated latency, never `Thread.sleep`, and documents that future blocking work belongs behind `withContext(Dispatchers.IO)`.
- [ ] Re-throw `CancellationException` before broad exception handling if any catch block is needed.
- [ ] Construct `SearchPipeline` as `class SearchPipeline(private val adapter: SearchAdapter)`.
- [ ] Add English KDoc for public domain types, `SearchAdapter`, `FakeSearchAdapter`, and `SearchPipeline.search(...)`, including settings-first-emission and cancellation contracts.
- [ ] Implement `SearchPipeline.search(...)`:

```kotlin
fun search(
    queries: Flow<String>,
    settings: Flow<SearchSettings>,
    sessionClosed: Flow<Unit>,
    debounce: Duration,
): Flow<SearchResult>
```

- [ ] Validate `debounce` at the API boundary:
  - `debounce` must be positive.
  - Request deadlines are out of scope; session close and collector cancellation are the only cancellation boundaries.
- [ ] Normalize `sessionClosed` once per collection into a shared stop signal before using it in both the adapter race and outer terminal guard:
  - Cold `sessionClosed` sources must be collected once.
  - A single close event must cancel the active search and terminate downstream without being missed by either observer.
  - The implementation may use `channelFlow`/`shareIn`/structured child coroutines, but it must keep one collection of `sessionClosed` per pipeline collection.
- [ ] Chain operators conceptually in this order:

```kotlin
queries
    .map(String::trim)
    .filter(String::isNotBlank)
    .bufferingDebounce(debounce)
    .mapNotNull { burst -> burst.lastOrNull()?.let(::SearchQuery) }
    .withLatestFrom(settings) { query, latestSettings -> SearchRequest(query, latestSettings) }
    .flatMapLatest { request -> searchUntilSessionClosed(request, sharedSessionClosed) }
    .takeUntil(sharedSessionClosed)
    .log("search-pipeline")
```

- [ ] Import Bluetape4k extensions from `io.bluetape4k.coroutines.flow.extensions`.
- [ ] Use Kotlin standard `flatMapLatest` deliberately and explain the reason in KDoc/README.
- [ ] Implement `searchUntilSessionClosed(request, sharedSessionClosed)` with an explicit structured `coroutineScope`/`select` cancellation-aware race:
  - Start `adapter.search(request)` in a child coroutine.
  - Observe `sharedSessionClosed.first()` in a sibling child; this helper must not capture or collect the original `sessionClosed` Flow.
  - When `sessionClosed` wins, cancel the search child before it can return and complete without emitting a result.
  - When search wins, emit the result and cancel the session observer.
  - If adapter search fails, propagate the original failure and cancel the session observer.
  - If the collector cancels while search is suspended, cancel both the search child and the session observer.
  - Use `try/finally` to cancel the losing child and wait for loser completion before returning.
  - Re-throw `CancellationException`; do not use `runCatching` around suspend calls.
- [ ] Keep `takeUntil(sharedSessionClosed)` as the outer terminal guard, not as the proof that an already suspended adapter call is cancelled.
- [ ] Keep `Flow.log("search-pipeline")` after result production only, on values whose `toString()` output is redacted; never move it before debounce or onto raw query streams, and never log raw query text, tenant id, feature flags, source metadata, or hit titles.
- [ ] README notes that `Flow.log()` is demo/debug instrumentation and should be removed or guarded in hot production paths and excluded from latency comparisons.

## Task 4: README Pair And Root Index

**Complexity:** medium  
**Applies:** `$bluetape4k-blog`, `$bluetape4k-diagram`

**Files:**
- Create: `kotlin/flow-extensions-search-pipeline/README.md`
- Create: `kotlin/flow-extensions-search-pipeline/README.ko.md`
- Modify: `README.md`
- Modify: `README.ko.md`

- [ ] README.md starts with `# Flow Extensions Search Pipeline` and `English | [한국어](README.ko.md)` language switch.
- [ ] README.ko.md starts with `# Flow Extensions Search Pipeline` and `[English](README.md) | 한국어` language switch.
- [ ] Include sections:
  - Scenario
  - Scope and unsupported capabilities
  - Before: manual `MutableSharedFlow` and `Job` cancellation, explicitly labeled as an anti-pattern contrast
  - After: Flow extension chain
  - Architecture
  - Domain model
  - Sequence model
  - Why `flatMapLatest`, not `flatMapDrop`
  - Settings stream precondition
  - Diagnostics and operational notes
  - Used Bluetape4k features
  - Build and test
  - References
- [ ] Used features table has columns: Feature, Artifact, Code reference, Benefit.
- [ ] Scope section states: no HTTP/WebSocket, no DB/cache, no auth/authz, no production ranking, no distributed cancellation protocol, fake adapter only, and no untrusted object deserialization.
- [ ] Before/After contrast names the manual approach risks: mutable settings race, manual cancellation leaks, and broad catch blocks swallowing cancellation.
- [ ] Settings section shows `MutableStateFlow(initialSettings)` and explains unseeded settings cause `withLatestFrom` to drop early queries.
- [ ] Diagnostics section covers `search-pipeline` log tag, redacted log values, expected cancellation evidence, debug logging overhead, and no-resource-ownership rollback.
- [ ] Build and test section includes exact commands:

```bash
./gradlew :kotlin-flow-extensions-search-pipeline:test --rerun-tasks --console=plain
./gradlew :kotlin-flow-extensions-search-pipeline:compileKotlin :kotlin-flow-extensions-search-pipeline:compileTestKotlin --console=plain
./gradlew projects --console=plain
```

- [ ] Register root Async & Reactive table as Basic, in-memory, `coroutines` + `junit5`.
- [ ] Korean README is source-equivalent and natural Korean technical prose.

## Task 5: Diagram Assets

**Complexity:** high  
**Applies:** `$bluetape4k-diagram`

**Files:**
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-scenario-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-architecture-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-erd-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-sequence-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-contact-sheet-01.png`
- Modify: `scripts/validate-readme-architecture-diagrams.mjs`
- Modify: `scripts/validate-sequence-diagrams.mjs`

- [ ] Use English labels and source-backed domain/operator names.
- [ ] Use wiki/catalog icons when technology icons are needed. For Kotlin, prefer `docs/icons/languages/kotlin.svg` from `bluetape4k-wiki`.
- [ ] Render every SVG with:

```bash
~/.local/bin/cairosvg <svg> -o <png> -s 2
```

- [ ] Run SVG XML parse.
- [ ] Run geometry and endpoint audits for architecture and sequence diagrams.
- [ ] Inspect every touched full-size PNG with `view_image`.
- [ ] Inspect contact sheet after full-size inspection.
- [ ] Run repo validators:

```bash
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
```

## Task 6: Workflow Coverage

**Complexity:** low  
**Applies:** `$bluetape4k-code-patterns`

**Files:**
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`

- [ ] Run:

```bash
rg "flow-extensions-search-pipeline|:kotlin-flow-extensions-search-pipeline:test" .github/workflows/
```

- [ ] Add Examples workflow coverage:
  - path filter `kotlin/flow-extensions-search-pipeline/**`
  - Gradle smoke task `:kotlin-flow-extensions-search-pipeline:test`
  - artifact paths for its test results
  - matching smoke task in `scripts/smoke-validate.sh`
- [ ] Run:

```bash
actionlint .github/workflows/Examples.yml
rg -n "\\\\'" .github/workflows
```

## Task 7: Validation

**Complexity:** medium  
**Applies:** `$bluetape4k-code-patterns`, `$verification-before-completion`

**Files:**
- All touched files.

- [ ] Run module tests:

```bash
./gradlew :kotlin-flow-extensions-search-pipeline:test --rerun-tasks --console=plain
```

- [ ] Run compile checks:

```bash
./gradlew :kotlin-flow-extensions-search-pipeline:compileKotlin :kotlin-flow-extensions-search-pipeline:compileTestKotlin --console=plain
```

- [ ] Run registration:

```bash
./gradlew projects --console=plain
```

- [ ] Run README validators:

```bash
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
```

- [ ] Run `git diff --check`.

## Task 8: Review, Lessons, Commit, PR

**Complexity:** medium  
**Applies:** `$bluetape4k-workflow`

**Files:**
- Create: `docs/review/2026-06-29-issue-302-flow-search-pipeline-review.md`
- Create: `docs/lessons/2026-06-29-issue-302-flow-search-pipeline.md`

- [ ] Run Step 6-R local/native 7-tier review over the implementation diff and record P0/P1 = 0.
- [ ] Create lessons note with at least:
  - `flatMapDrop` vs `flatMapLatest` semantic mismatch
  - deterministic cancellation tests for autocomplete examples
- [ ] Commit implementation with Lore trailers.
- [ ] Push branch.
- [ ] Create PR:

```bash
gh pr create --title "feat: add Flow search pipeline workshop" --body-file /tmp/pr-body.md --assignee debop
```

- [ ] Mirror issue #302 milestone and labels on PR.
- [ ] Verify live PR body final `##` section is `## DoD Status`.
- [ ] Wait for CI and Examples checks; update PR body DoD after CI.

## Self-Review

- Spec coverage: every #302 acceptance criterion maps to Tasks 2-7.
- Empty-marker scan: no unresolved markers remain.
- Type consistency: `SearchQuery`, `SearchSettings`, `SearchRequest`, `SearchHit`, `SearchResult`, `FakeSearchAdapter`, and `SearchPipeline.search(...)` names are consistent across tasks.
- Risk handling: supersede semantics, cancellation, settings first-emission behavior, and diagram QA are explicit.

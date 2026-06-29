# Flow Extensions Search Pipeline

[한국어](README.ko.md) | English

This example demonstrates a realtime search/autocomplete pipeline built with bluetape4k Flow extensions.

Use this pattern when query input arrives faster than the backend can search, user settings can change while the session is active, stale searches must be cancelled, and diagnostic logs must not expose query or tenant text.

## Scenario

![Scenario](../../docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-scenario-01.png)

A user types search text in short bursts. The pipeline keeps the last query from each burst, combines it with the latest settings, runs one adapter request at a time, cancels stale work when a newer query arrives, and stops immediately when the session closes.

This example intentionally stays in-memory. It does not implement a search index, ranking engine, HTTP endpoint, or UI widget. The goal is to teach Flow operator semantics for a realistic autocomplete boundary.

## Before: manual MutableSharedFlow and Job wiring

```kotlin
private var searchJob: Job? = null

fun onQuery(text: String) {
    searchJob?.cancel()
    searchJob = scope.launch {
        delay(150)
        val settings = currentSettings()
        val result = adapter.search(SearchRequest(SearchQuery(text), settings))
        render(result)
    }
}
```

This works for a demo, but the real code still has to normalize input, debounce bursts, snapshot settings, cancel in-flight adapter calls, stop on session close, and keep logs redacted.

## After: Flow extension chain

```kotlin
val results = SearchPipeline(adapter).search(
    queries = queryText,
    settings = settingsState,
    sessionClosed = sessionClosed,
    debounce = 150.milliseconds,
)
```

`SearchPipeline` keeps the lifecycle rules in one stream:

- `bufferingDebounce` groups rapid input and keeps the latest query in the burst.
- `withLatestFrom` snapshots the most recent settings when a debounced query is ready.
- Kotlin `flatMapLatest` cancels stale adapter work when a newer query arrives.
- `takeUntil` stops downstream output when the session closes.
- `Flow<T>.log()` records stream events while domain `toString()` methods redact sensitive text.

## Architecture

![Architecture](../../docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-architecture-01.png)

The architecture is layered from input to pipeline to adapter/output. The session stop signal is shared once so a cold `sessionClosed` flow is not collected twice.

## Domain model

![Domain model](../../docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-erd-01.png)

`SearchQuery` and `SearchSettings` are regular serializable classes with private constructors. They are intentionally not data classes because generated `copy` functions would bypass validation. Result value classes are data classes and also implement `Serializable`.

## Sequence model

![Sequence](../../docs/images/readme-diagrams/kotlin-flow-extensions-search-pipeline-readme-sequence-01.png)

The adapter call races the shared session stop signal. If the session closes first, the suspended adapter job is cancelled instead of merely suppressing a late result.

## Why `flatMapLatest`, not `flatMapDrop`

Autocomplete should keep the newest query and cancel older work. `flatMapDrop` and `flatMapFirst` intentionally drop newer values while an inner flow is still busy, so they are useful for "ignore re-entry" workflows, not for search supersession.

This example still references those operators because the contrast is important: choosing a Flow operator is a business contract, not just a syntax choice.

## Settings stream precondition

`settings` should be a seeded hot/state-like flow, such as `MutableStateFlow(initialSettings)`. A query emitted before the first settings value is intentionally dropped by `withLatestFrom` because the pipeline cannot build a complete request yet.

## Diagnostics and operational notes

- Blank input is ignored before debounce.
- Query text and tenant id are trimmed and validated.
- Feature flags are normalized into an unmodifiable lowercase kebab-case set.
- Result limits are bounded so a fake adapter cannot materialize unbounded output.
- `CancellationException` is rethrown by the adapter so coroutine cancellation remains cooperative.
- Redacted `toString()` output keeps `Flow<T>.log()` safe for learner-visible logs.

## Used Bluetape4k features

| Feature | Artifact | Code reference | Benefit |
|---|---|---|---|
| `bufferingDebounce` | Query burst handling | `SearchPipeline.search` | Keeps the latest query after fast typing |
| `withLatestFrom` | Settings snapshot | `SearchPipeline.search` | Combines debounced input with current settings |
| `takeUntil` | Session stop | `SearchPipeline.search` | Stops result emission on close |
| `Flow<T>.log()` | Diagnostics | `SearchPipeline.search` | Logs lifecycle events without leaking redacted domain text |
| Validation helpers | Domain model | `SearchQuery`, `SearchSettings` | Rejects blank, oversized, or malformed input early |

## Build and test

```bash
./gradlew :kotlin-flow-extensions-search-pipeline:test --rerun-tasks --console=plain
./gradlew :kotlin-flow-extensions-search-pipeline:compileKotlin :kotlin-flow-extensions-search-pipeline:compileTestKotlin --console=plain
./gradlew projects --console=plain
```

## References

- [bufferingDebounce](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/bufferingDebounce.kt)
- [withLatestFrom](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/withLatestFrom.kt)
- [takeUntil](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/takeUntil.kt)
- [Flow logging](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/logger.kt)
- [flatMapDrop](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/flatMapDrop.kt)

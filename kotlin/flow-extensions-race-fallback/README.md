# Flow Extensions Race and Fallback

[한국어](README.ko.md) | English

This example demonstrates multi-source catalog reads with bluetape4k Flow source-composition operators.

Use these operators when source lifetime and delivery semantics matter more than hand-written `async` and `select` plumbing.

## Scenario

![Scenario](../../docs/images/readme-diagrams/kotlin-flow-extensions-race-fallback-readme-scenario-01.png)

A catalog service can read the same reference data from four places:

- an in-memory cache,
- a local replica,
- a remote API,
- a backup endpoint.

The right composition depends on the business question: fastest healthy answer, ordered fallback, eager fallback, all-source enrichment, or explicit error events.

## Before: manual async/select plumbing

```kotlin
suspend fun rawRace(cache: Deferred<SourceResult>, remote: Deferred<SourceResult>): SourceResult = select {
    cache.onAwait { it }
    remote.onAwait { it }
}
```

This is powerful, but the real code still has to cancel losers, preserve fallback order, keep original failures, and avoid reimplementing separate logic for race, fallback, and merge cases.

## After: Flow source composition

```kotlin
val catalog = RaceFallbackCatalog()
val winner = catalog.fastestHealthy(listOf(cacheFlow, replicaFlow, remoteFlow)).toList()
val fallback = catalog.orderedFallback(listOf(cacheFlow, replicaFlow, backupFlow)).toList()
val enriched = catalog.mergeContributions(cacheFlow, replicaFlow, remoteFlow).toList()
```

## Architecture

![Architecture](../../docs/images/readme-diagrams/kotlin-flow-extensions-race-fallback-readme-architecture-01.png)

`RaceFallbackCatalog` keeps the decision point explicit: choose one operator per source contract instead of hiding fallback behavior in nested `try/catch` blocks.

## Decision table

| Need | Operator | Contract |
|---|---|---|
| Lowest latency healthy answer | `race` / `amb` | First source to emit wins; losers are cancelled |
| Ordered fallback | `concat` | Sources run one after another in priority order |
| Eager fallback with ordered output | `concatArrayEager` | Sources start immediately; output remains source ordered |
| Dynamic eager fallback | `concatMapEager` | Mapped sources start eagerly; outer order controls output |
| Partial enrichment from every source | `merge` | All sources contribute by arrival order |
| Error-as-value explanation | `materialize` / `dematerialize` | Terminal signals become values and can be restored |

## Domain model

![Domain model](../../docs/images/readme-diagrams/kotlin-flow-extensions-race-fallback-readme-erd-01.png)

The model is intentionally small: `CatalogItem`, `CatalogSource`, `SourceResult`, and `SourceQuality`.

## Class model

![Class diagram](../../docs/images/readme-diagrams/kotlin-flow-extensions-race-fallback-readme-class-diagram-01.png)

## Sequence model

![Sequence](../../docs/images/readme-diagrams/kotlin-flow-extensions-race-fallback-readme-sequence-01.png)

## Error semantics

Use terminal errors when a source failure should stop the current composition. Use `materialize()` when the example needs to explain or route failure as data without losing the original exception.

## Used Bluetape4k features

| Feature | Usage |
|---|---|
| `race` / `amb` | fastest healthy source wins |
| `concat` | strict fallback order |
| `concatArrayEager` | eager source start with ordered output |
| `concatMapEager` | dynamic eager fallback mapping |
| `merge` | partial contributions from all sources |
| `materialize` / `dematerialize` | error-as-value and terminal-error conversion |

## Build and test

```bash
./gradlew :kotlin-flow-extensions-race-fallback:test
```

## References

- [amb](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/amb.kt)
- [race](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/race.kt)
- [concat](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/concat.kt)
- [concatArrayEager](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/concatArrayEager.kt)
- [concatMapEager](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/concatMapEager.kt)
- [merge](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/mergeFlows.kt)
- [materialize](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/materialize.kt)

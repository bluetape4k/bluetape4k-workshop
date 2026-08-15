# Flow Extensions Event Aggregation

[한국어](README.ko.md) | English

This example demonstrates how to build a small event aggregation pipeline with bluetape4k Flow extensions.

Use this pattern when a bounded replay of domain events must be summarized, grouped by aggregate id, projected into read models, collapsed into lifecycle runs, and logged without leaking sensitive fields.

## Scenario

An order service emits lifecycle events such as `OrderCreated`, `LineAdded`, `PaymentAuthorized`, `ShipmentStarted`, and `OrderCancelled`. The workshop pipeline consumes those events in memory and turns them into teaching-friendly projections.

This example intentionally avoids Kafka, databases, HTTP endpoints, and durable checkpoints. The goal is to explain Flow operator semantics before introducing infrastructure.

## Architecture

![Architecture](../../docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-architecture-01.png)

The architecture is top-to-bottom. Raw events enter a bounded Flow operator layer, then feed an immutable read-model projection, and finally branch into summaries, lifecycle transitions, and sanitized audit output.

`groupBy` is shown as a finite-stream tool. It materializes completed groups with `toGroupItems()`, so it is useful for replay windows, batch verification, and tests, not for unbounded hot ingestion.

## Operator sequence

![Sequence](../../docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-sequence-01.png)

![Count-or-time contract](../../docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-count-or-time-01.png)

The sequence highlights the important contracts:

- `chunked` emits bounded batches for batch-level activity summaries.
- `bufferTimeout` closes a non-empty batch at the count boundary, observed idle timeout, or normal completion.
- `windowed` emits overlapping windows when the learner needs rolling context.
- `groupBy` partitions a completed replay by `orderId`.
- `scanWith` creates immutable read-model snapshots.
- `bufferUntilChanged` collapses adjacent equal lifecycle states.
- `zipWithNext` turns status states into transitions.
- `Flow<T>.log()` observes sanitized audit entries.

## Before: manual mutable aggregation

```kotlin
val states = mutableMapOf<String, OrderState>()

for (event in events) {
    val current = states[event.orderId] ?: OrderState.empty(event.orderId)
    states[event.orderId] = current.apply(event)
}
```

Manual loops are easy to start with, but the code mixes validation, batching, grouping, projection, lifecycle collapse, and debug logging in one place.

## After: Flow extension chain

```kotlin
val pipeline = OrderEventAggregationPipeline()

val summaries = pipeline.chunkedActivity(events, chunkSize = 100)
val countOrTimeSummaries = pipeline.countOrTimeActivity(
    events,
    maxSize = 100,
    timeout = 250.milliseconds,
)
val readModels = pipeline.readModels(events)
val transitions = pipeline.transitions(events, orderId = "order-1")
```

Each public function teaches one aggregation boundary, so learners can run the tests and inspect the emitted values one operator at a time.

`countOrTimeActivity` emits full batches when `maxSize` wins and emits a non-empty partial batch when the upstream completes or the observed idle timeout wins. The workshop pins the behavior of bluetape4k-coroutines `1.12.1`: its current implementation re-registers the timeout after each received item, so the timeout is observed as an idle period from the latest item. Verify the dependency source again if a first-item wall-clock window is required.

## Domain model

Order events are regular serializable classes with private constructors. They are intentionally not data classes because generated `copy(...)` functions would bypass token normalization and safe rendering.

Projection values such as `OrderState`, `OrderReadModel`, `OrderActivitySummary`, `OrderStatusRun`, `OrderTransition`, and `OrderAuditEntry` are data classes and implement `Serializable`.

## Failure and cancellation contracts

- Invalid ids, amounts, quantities, and control characters fail before collection.
- `CancellationException` is rethrown so coroutine cancellation stays cooperative.
- `countOrTimeActivity` flushes its final partial batch only on normal completion; an upstream failure discards the in-flight partial batch and preserves the original exception.
- `groupBy` wraps upstream failures in `FlowOperationException`; the tests verify that the original cause remains reachable.
- Debug rendering redacts customer id, tracking number, and cancellation reason.

## Used Bluetape4k features

| Feature | Code reference | Lesson |
|---|---|---|
| `chunked` | `chunkedActivity` | Bound memory by batch size |
| `bufferTimeout` | `countOrTimeActivity` | Close a non-empty batch at count, observed idle timeout, or completion |
| `windowed` | `rollingActivity` | Emit overlapping rolling summaries |
| `groupBy` + `toGroupItems` | `groupedByOrder` | Partition a completed replay by aggregate id |
| `scanWith` | `readModels` | Emit immutable projection snapshots |
| `bufferUntilChanged` | `statusRuns` | Collapse adjacent equal lifecycle states |
| `zipWithNext` | `transitions` | Convert states into lifecycle transitions |
| `Flow<T>.log()` | `audit` | Observe sanitized stream values |

## Build and test

```bash
./gradlew :kotlin-flow-extensions-event-aggregation:test --rerun-tasks --console=plain
./gradlew :kotlin-flow-extensions-event-aggregation:compileKotlin :kotlin-flow-extensions-event-aggregation:compileTestKotlin --console=plain
./gradlew projects --console=plain
```

## References

- [chunked](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/chunked.kt)
- [bufferTimeout](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/bufferTimeout.kt)
- [windowed](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/windowed.kt)
- [groupBy](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/groupBy.kt)
- [scanWith](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/scanWith.kt)
- [bufferUntilChanged](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/bufferUntilChanged.kt)
- [zipWithNext](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/zipWithNext.kt)
- [Flow logging](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/logger.kt)

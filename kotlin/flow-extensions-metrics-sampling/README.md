# Flow Extensions Metrics Sampling

[한국어](README.ko.md) | English

This example demonstrates how to turn noisy, high-frequency metrics or sensor readings into learner-friendly Flow pipelines with bluetape4k Flow extensions.

Use this pattern when a service receives many values per second, but different consumers need different views: fast alert previews, stable dashboard samples, adjacent deltas, significant transitions, and a lifecycle stop signal.

## Scenario

A monitoring component receives values such as CPU utilization, queue depth, p95 latency, and sensor temperature. The workshop pipeline consumes those samples in memory and teaches how to reduce the stream without manual scheduler state.

The example intentionally avoids metrics backends, HTTP endpoints, databases, and queues. The goal is to make Flow operator semantics visible before attaching infrastructure.

## Architecture

![Architecture](../../docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-architecture-01.png)

The architecture is top-to-bottom. Raw metric samples enter the sampling layer, then flow into adjacent transition analysis, and finally branch into alert preview, dashboard, and bounded lifecycle outputs.

Each layer is code-only. There is no Redis, broker, database, or server in this module, so the diagram uses text cards instead of infrastructure icons.

## Operator sequence

![Sequence](../../docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.png)

The sequence highlights the important contracts:

- `throttleLeading` emits the first value in a window for quick alert previews.
- `throttleTrailing` emits the last value after a window settles for dashboards.
- `pairwise` compares adjacent samples without mutable previous-value state.
- `filter` keeps only trends that cross a configured absolute threshold.
- `takeUntil` stops collection when a lifecycle signal arrives.
- `Flow<T>.log()` observes teaching output without changing the stream.
- `mapResultCatching` preserves `CancellationException` instead of converting it to a domain failure.

## Before: manual scheduler and timestamp state

```kotlin
var lastPreviewAt = Instant.EPOCH
var previous: MetricSample? = null
val trends = mutableListOf<MetricTrend>()

for (sample in samples) {
    if (Duration.between(lastPreviewAt, sample.timestamp) >= previewWindow) {
        preview(sample)
        lastPreviewAt = sample.timestamp
    }

    previous?.let { before ->
        val delta = MetricDelta.from(before, sample)
        if (abs(delta.delta) >= threshold) {
            trends += MetricTrend.from(delta, threshold)
        }
    }
    previous = sample
}
```

Manual loops work for a short demo, but they mix timer policy, previous-value tracking, threshold filtering, lifecycle stop behavior, and logging in one place.

## After: Flow extension chain

```kotlin
val pipeline = MetricsSamplingPipeline()

val preview = pipeline.leadingPreview(samples, 500.milliseconds)
val dashboard = pipeline.dashboardSamples(samples, 500.milliseconds)
val trends = pipeline.significantChanges(samples, absoluteThreshold = 10.0)
val resultTrends = pipeline.significantChangeResults(deltaResults, absoluteThreshold = 10.0)
val bounded = pipeline.lifecycleBoundSamples(samples, stopSignal)
```

Each public function teaches one boundary. Learners can run the tests and inspect emitted values for one operator at a time.

## Leading vs trailing samples

| Consumer | Extension | Emits | Good for | Trade-off |
|---|---|---|---|---|
| Alert preview | `throttleLeading` | First value in each window | Fast feedback | May miss the final value in a burst |
| Dashboard tile | `throttleTrailing` | Last value in each window | Stable display value | Waits until the window closes |

## Domain model

`MetricSample` is a regular serializable class with a private constructor. It is intentionally not a data class because a generated `copy(...)` function would bypass name, unit, and finite-value validation.

`MetricDelta` and `MetricTrend` are data classes because they are derived values and have no validation bypass path after construction.

## Failure and cancellation contracts

- Blank names, control characters, non-finite values, and invalid thresholds fail before useful output is emitted.
- `takeUntil` represents normal lifecycle termination; the bounded stream completes with values observed before the stop signal.
- `significantChangeResults` uses `mapResultCatching` to show the library contract for cancellation-safe result mapping: `CancellationException` is rethrown instead of wrapped as `Result.failure`.

## Used Bluetape4k features

| Feature | Code reference | Lesson |
|---|---|---|
| `throttleLeading` | `leadingPreview` | Emit quick first-sample previews |
| `throttleTrailing` | `dashboardSamples` | Emit settled dashboard samples |
| `pairwise` | `deltas` | Compare adjacent samples without mutable state |
| `takeUntil` | `lifecycleBoundSamples` | End collection from a lifecycle stop signal |
| `Flow<T>.log()` | `leadingPreview`, `dashboardSamples`, `significantChanges`, `lifecycleBoundSamples` | Observe stream values without changing them |
| `mapResultCatching` | `significantChangeResults` | Preserve cooperative coroutine cancellation |

## Build and test

```bash
./gradlew :kotlin-flow-extensions-metrics-sampling:test --rerun-tasks --console=plain
./gradlew :kotlin-flow-extensions-metrics-sampling:compileKotlin :kotlin-flow-extensions-metrics-sampling:compileTestKotlin --console=plain
./gradlew projects --console=plain
```

## References

- [throttleLeading and throttleTrailing](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/throttle.kt)
- [pairwise](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/pairwise.kt)
- [takeUntil](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/takeUntil.kt)
- [Result mapping](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/results.kt)
- [Flow logging](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/logger.kt)

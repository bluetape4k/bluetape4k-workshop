# Issue 304 Flow Metrics Sampling Lesson

## Context

The metrics sampling example teaches Flow operator boundaries for noisy CPU, queue-depth, latency, and sensor streams without adding metrics storage or transport infrastructure.

## Decision

Use existing `bluetape4k-coroutines` Flow extensions directly: `throttleLeading` for alert previews, `throttleTrailing` for dashboards, `pairwise` for adjacent deltas, `takeUntil` for lifecycle stop, `Flow<T>.log()` for observation, and `mapResultCatching` for cancellation-safe Result mapping.

## Outcome

The module now separates fast preview sampling, stable dashboard sampling, transition analysis, lifecycle stop handling, and cancellation behavior into small testable functions with bilingual learner documentation.

## Future guidance

Do not turn channel-bound implementation details of a throttle operator into workshop acceptance tests. Test the user-facing sampling values for throttle examples, and test explicit cancellation contracts on the extension that owns them, such as `mapResultCatching`.

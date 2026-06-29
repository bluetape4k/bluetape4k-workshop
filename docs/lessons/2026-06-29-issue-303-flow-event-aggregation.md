# Issue 303 Flow Event Aggregation Lesson

## Context

The event aggregation example teaches Flow operator boundaries for bounded order-event replay without adding Kafka, storage, or HTTP infrastructure.

## Decision

Keep `groupBy` explicitly finite. `groupBy().toGroupItems()` materializes each completed group, so the example uses `flatMapMerge(concurrency = Int.MAX_VALUE)` and a high-cardinality timeout test to avoid hidden deadlocks when distinct order ids exceed a small concurrency cap.

## Outcome

The module now covers bounded activity summaries, rolling windows, finite grouping, immutable read-model projection, lifecycle run collapse, transitions, sanitized audit logging, and cooperative cancellation.

## Future guidance

Do not reuse this `groupBy` pattern for unbounded hot ingestion. Add durable partitioning, checkpoints, backpressure, and storage before turning it into a service pipeline.

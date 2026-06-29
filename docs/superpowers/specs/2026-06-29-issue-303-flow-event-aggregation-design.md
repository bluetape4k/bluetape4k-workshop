# Issue 303 Flow Event Aggregation Design

## Problem

Issue #303 asks for a workshop example that teaches learners how to turn a noisy order event stream into stable read-model updates with `bluetape4k-coroutines` Flow extensions. The example should replace mutable maps, timer flushes, and manual previous/current state checks with a readable Flow pipeline.

The example is in-memory. It must not add HTTP, database, Kafka, cache, or Testcontainers infrastructure. The learner-facing surface is a small module under `kotlin/`, bilingual README files, generated README diagrams, deterministic coroutine tests, and smoke workflow registration.

## Source Evidence

- Existing Flow workshop modules live under `kotlin/flow-extensions-*` and use small in-memory domains, module-local README pairs, JUnit 5 coroutine tests, and root README registration.
- GNO issue search returns issue #303 as the exact GitHub source. GNO docs search has no prior event-aggregation workshop design, so this spec relies on the live issue, current repo examples, and sibling `bluetape4k-projects` Flow extension source.
- `chunked(size, partialWindow)` delegates to `windowed(size, size, partialWindow)` and emits bounded count batches.
- `windowed(size, step, partialWindow)` validates `size > 0`, `step > 0`, and `size >= step`; partial rolling windows may emit tail windows.
- `groupBy { key }` emits `GroupedFlow<K, T>` per key; `toGroupItems()` consumes each group once and returns `GroupItem(key, values)`.
- `scanWith { initial }` creates a fresh accumulator per collection and emits the initial accumulator before accumulated states.
- `bufferUntilChanged { key }` groups adjacent equal-key runs only. It is correct for suppressing consecutive unchanged lifecycle statuses, not for global grouping by order id.
- `pairwise` and `zipWithNext` emit adjacent previous/current pairs and are suitable for transition reporting.
- `Flow<T>.log(tag)` is a transparent debug hook. Values that pass through it should have safe string rendering because learners may copy logging into real services.

## Design

Create `kotlin/flow-extensions-event-aggregation` as an in-memory Kotlin module.

The main type is `OrderEventAggregationPipeline`. It accepts `Flow<OrderEvent>` inputs and exposes small, separately testable pipeline functions:

1. `chunkedActivity(events, chunkSize)` uses `chunked` to create bounded batch summaries.
2. `rollingActivity(events, size, step)` uses `windowed` to create overlapping activity summaries.
3. `groupedByOrder(events)` returns `Flow<GroupItem<String, OrderEvent>>` and consumes groups immediately with `events.groupBy { it.orderId }.flatMapMerge { it.toGroupItems() }`. This is a finite completed-stream partitioning demo only, not the periodic aggregation hot path.
4. `readModels(events)` uses `scanWith` to accumulate an `OrderReadModel` map keyed by order id. `OrderReadModel.apply` intentionally returns immutable snapshots for teaching clarity; README names the per-event snapshot allocation cost.
5. `statusRuns(events, orderId)` returns `Flow<OrderStatusRun>`. It filters one order, applies `scanWith`, drops the initial `NEW` state, calls `bufferUntilChanged { it.status }`, and maps each emitted `List<OrderState>` run to one DTO with the run status, first/last version, first/last event time, and final state.
6. `transitions(events, orderId)` maps `statusRuns(...).map { it.finalState }` before `zipWithNext { previous, current -> OrderTransition(...) }`, then filters unchanged statuses.
7. `audit(events)` maps each event to sanitized `OrderAuditEntry` values first, then calls `Flow.log("order-event-aggregation")`. Raw `OrderEvent` values never pass through `Flow.log()`.

The README should teach that these functions compose into one event aggregation pipeline, but the source keeps them separate so learners can run focused tests and inspect one Flow extension at a time.

## Domain Model

- `OrderEvent`: sealed interface with `orderId`, `occurredAt`, and safe event type naming.
  - `OrderCreated(orderId, customerId, occurredAt)`
  - `LineAdded(orderId, sku, quantity, occurredAt)`
  - `PaymentAuthorized(orderId, amountCents, occurredAt)`
  - `ShipmentStarted(orderId, carrier, trackingNumber, occurredAt)`
  - `OrderCancelled(orderId, reason, occurredAt)`
- `OrderStatus`: `NEW`, `CREATED`, `PAID`, `SHIPPED`, `CANCELLED`.
- `OrderState`: serializable read model for one order id. It tracks status, line count, item quantity, authorized amount, last event time, and version.
- `OrderReadModel`: serializable aggregate map wrapper used by `scanWith`.
- `OrderActivitySummary`: serializable DTO for bounded batches and rolling windows. It includes event count, order ids, latest statuses, line count, item quantity, amount, and window start/end timestamps.
- `OrderStatusRun`: serializable DTO for one adjacent unchanged-status run. It stores the final state of the run so transitions can compare collapsed states.
- `OrderTransition`: serializable DTO for previous/current status changes.
- `OrderAuditEntry`: serializable DTO for readable test/debug output.

Caller-visible invariants:

- `orderId`, `customerId`, `sku`, `carrier`, `trackingNumber`, and cancellation `reason` are trimmed, non-blank, bounded, and reject control characters.
- Log-visible identifiers such as `orderId`, `sku`, and `carrier` use a printable ASCII token pattern before they are shown in summaries or audit output.
- `quantity` is positive.
- `amountCents` is positive.
- `occurredAt` is a caller-owned timestamp; the example does not assign wall-clock time internally.
- Input event types use private constructors plus companion factories or validated value-object patterns so construction, trimming, and safe string rendering cannot be bypassed through default data-class `copy(...)`. Any event or audit type that can appear in debug output has an explicit redacted or sanitized `toString()`.
- `OrderAuditEntry` allowed fields are `sequence`, `eventType`, `orderId`, `status`, counts, amount, version, and timestamp. It never stores raw `customerId`, `trackingNumber`, or cancellation `reason`.
- Serializable domain classes implement `java.io.Serializable` and define `serialVersionUID`. Serializable is a repo convention here; the example does not demonstrate persistence or untrusted object deserialization.

## State Transition Rules

| Current status | Event | Next status | Notes |
|---|---|---|---|
| `NEW` | `OrderCreated` | `CREATED` | First normal lifecycle event |
| `NEW` | `LineAdded` | `CREATED` | Teaches tolerant read-model building for partially replayed fixtures |
| `NEW` or `CREATED` | `PaymentAuthorized` | `PAID` | Duplicate payment events stay `PAID` and update amount/version |
| `NEW`, `CREATED`, or `PAID` | `ShipmentStarted` | `SHIPPED` | Out-of-order shipment before payment is accepted as a read-model projection event and tested explicitly |
| Any non-cancelled status | `OrderCancelled` | `CANCELLED` | Cancellation is terminal even after `SHIPPED` |
| `CANCELLED` | Any later non-cancel event | `CANCELLED` | Status stays terminal; version and last-event time advance for audit visibility |

This workshop does not reject out-of-order events. It models a projection that preserves audit visibility while converging to a stable latest read model. README must say that strict command-side ordering validation belongs before events enter the projection.

## Resource Ownership And Stream Lifetime

Callers own collection, cancellation, replay, and source lifetime. All tests and examples use finite or replay-bounded streams.

`groupedByOrder` is for completed finite streams because `groupBy` keeps active groups while `toGroupItems()` materializes each group's values into a `List`. Learners must not copy that function as an unbounded live-ingestion primitive without bounding, TTL/checkpointing, or a durable event store/outbox. Periodic aggregation should use bounded `chunked`, `windowed`, or replay checkpoints, not unbounded `toGroupItems()`.

Recovery semantics are intentionally process-local: Flow pipelines are cold, `scanWith` creates fresh state per collection, partial emissions before failure are not checkpointed, and recovery means re-collecting from a replayable source. Cancellation or failure leaves no retained singleton state in this module.

## Rejected Approaches

1. Use a mutable singleton map plus scheduled flush.
   - Rejected because it hides stream semantics, makes deterministic tests harder, and reproduces the conventional approach the issue wants to replace.
2. Add an event store, outbox, Kafka, Redis Stream, or Spring Integration layer.
   - Rejected because the issue asks for an in-memory Flow extension example. The README will explain when those durable components are still required.
3. Use `bufferUntilChanged { orderId }` as the order grouping primitive.
   - Rejected because the actual contract groups adjacent runs only. The correct global grouping example is `groupBy { orderId }`.
4. Merge all behavior into one large `aggregate(events)` function.
   - Rejected because learners need to see how each extension changes the stream and tests need focused failure evidence.

## Risks And Mitigations

- **Grouping confusion**: README and tests explicitly distinguish `groupBy` for order partitioning from `bufferUntilChanged` for adjacent unchanged-status suppression.
- **Initial `scanWith` emission confusion**: `readModels(events)` filters or documents the initial empty read model where needed, and tests assert the first meaningful update.
- **State regression after terminal events**: domain transition rules keep `CANCELLED` terminal; shipped orders do not return to paid/created in this example.
- **High-cardinality grouping cost**: `groupedByOrder` is documented as finite-stream only; tests include a deterministic finite high-cardinality sample to prove no dropped groups while avoiding an unbounded hot path.
- **Window and snapshot allocation**: README states that `windowed`/`chunked` allocate emitted lists and overlapping windows duplicate retained elements, while immutable read-model snapshots allocate per event for clarity.
- **Timing-flaky tests**: tests use finite `flowOf(...)` data and `runSuspendTest`, with no scheduler sleeps.
- **Failure and cancellation ambiguity**: tests cover upstream failure propagation, `groupedByOrder` exception shape, and collector cancellation without swallowing `CancellationException`.
- **Debug data exposure**: audit DTOs are sanitized before `Flow.log()`; debug-facing `toString()` output avoids customer ids, tracking numbers, and cancellation reason values; tests assert sensitive strings are absent from audit fields and rendered debug output.
- **Durability overclaim**: README states that in-memory Flow aggregation is for process-local, replayable, or test pipelines; durable event store/outbox remains required for cross-process recovery.
- **Diagram drift**: diagrams are generated as SVG+PNG, audited with XML parse, architecture/sequence validators, geometry audit, endpoint audit, contact sheet, and full-size visual inspection.

## Durable Infrastructure Boundary

| Concern | This Flow example | Durable store/outbox responsibility |
|---|---|---|
| Atomic write and publish | Not provided | Persist domain change and enqueue/publish atomically |
| Replay offsets | Finite replay from caller-owned source only | Store offsets/checkpoints and resume after failure |
| Duplicate suppression | Projection is tolerant but not authoritative | Idempotency keys and duplicate detection |
| Ordering guarantees | Fixture order is preserved inside one Flow | Partition/order guarantees from broker/store |
| Retry and poison handling | Upstream failure propagates to tests | Retry policy, poison queue, dead-letter handling |
| Reconciliation | Not provided | Operator-visible reconciliation and repair jobs |

## Runbook And Rollback

Rollout is additive: one new in-memory module, README links, diagram assets, smoke script entries, and Examples workflow entries. There is no data migration and no persistent cleanup.

Rollback is reverting those added module/docs/diagram/workflow/script entries, then verifying `./gradlew projects --console=plain`, `./scripts/smoke-validate.sh async`, README image/link checks, and `git diff --check`.

## Acceptance Criteria Mapping

| Issue criterion | Design response |
|---|---|
| Runnable/testable example with at least four Flow extensions | The pipeline uses `chunked`, `windowed`, `groupBy`, `toGroupItems`, `scanWith`, `bufferUntilChanged`, `zipWithNext`, and `Flow.log()` |
| Before/after README sections | README.md and README.ko.md compare mutable map + scheduler with the Flow extension pipeline |
| Tests cover batching | `chunkedActivity` and `rollingActivity` tests verify bounded and rolling summaries |
| Tests cover grouping | `groupedByOrder` test verifies finite interleaved per-order partitioning with `groupBy`, plus a finite high-cardinality grouping sample |
| Tests cover state accumulation | `readModels` test verifies `scanWith` read-model updates |
| Tests cover unchanged-state suppression | `statusRuns` test verifies consecutive `CREATED` states collapse before `PAID` |
| Tests cover transition emission | `transitions` test verifies created-to-paid-to-shipped or cancel transitions |
| Tests cover stability | Tests verify terminal-state convergence, duplicate/out-of-order events, upstream failure propagation, collector cancellation, and deterministic output ordering |
| Tests cover invalid parameters | Tests verify invalid `chunkSize`, `size`, `step`, overlong identifiers, and control-character rejection |
| README explains scope and durable store/outbox limits | Scope section states process-local limits and when durable event store/outbox is required |
| README includes used feature table | Both README files include `Used Bluetape4k features` |

## Documentation And Diagrams

README language set:

- `kotlin/flow-extensions-event-aggregation/README.md`
- `kotlin/flow-extensions-event-aggregation/README.ko.md`

Diagram assets:

- Scenario: noisy order events become summaries, read models, transitions, and audit entries.
- Architecture: top-to-bottom layered flow from event source to Flow extension pipeline to read-model/debug outputs.
- Domain model: event hierarchy, read model, summary, transition, audit entry.
- Sequence: event stream -> chunk/window/group/scan -> suppress unchanged status -> emit transitions.

Generated diagram labels use English. Architecture flow must be top-to-bottom, include clear layer separation, use required fonts, and avoid invented infra icons for code/domain concepts.

Both READMEs include a scope note: no durable event store, no outbox, no message broker, no cross-process exactly-once semantics, no replay checkpointing, and no production PII logging. The manual baseline is labeled as an anti-pattern contrast, not as the copy target.

The README scope note must also state that `groupBy` + `toGroupItems()` is safe here only for finite or replay-bounded completed streams. Long-running production streams need durable storage/outbox/checkpointing plus bounded windows/backpressure strategy. The audit/log path is test/debug-only, uses the fixed tag `order-event-aggregation`, includes only non-sensitive fields, and is not a substitute for production metrics/tracing.

## Validation Plan

- `./gradlew :kotlin-flow-extensions-event-aggregation:test --rerun-tasks --console=plain`
- `./gradlew :kotlin-flow-extensions-event-aggregation:compileKotlin :kotlin-flow-extensions-event-aggregation:compileTestKotlin --console=plain`
- `./gradlew projects --console=plain`
- `./scripts/smoke-validate.sh async`
- CI registration checks:
  - `.github/workflows/Examples.yml` push and PR path filters include `kotlin/flow-extensions-event-aggregation/**`.
  - `smoke-examples` runs `:kotlin-flow-extensions-event-aggregation:test`.
  - smoke artifact upload includes `kotlin/flow-extensions-event-aggregation/build/test-results/test/*.xml` and `kotlin/flow-extensions-event-aggregation/build/reports/tests/test/`.
  - `scripts/smoke-validate.sh` includes the task in both `all-smoke` and `async`.
- README validators and root README link checks.
- Diagram validation: SVG XML parse, CairoSVG render, architecture and sequence validators, geometry audit, endpoint audit, contact sheet, and full-size PNG visual inspection.
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## DoD

- Module is registered by existing `settings.gradle.kts` auto-include.
- Tests cover batching, grouping, state accumulation, unchanged-state suppression, transition emission, invalid domain values, invalid pipeline parameters, terminal-state convergence, upstream failure propagation, collector cancellation, deterministic ordering, and safe audit/debug rendering.
- README.md and README.ko.md are source-equivalent and include before/after, scope, and feature table sections.
- Diagrams are source-backed, readable, top-to-bottom where architectural flow is shown, and pass the current `$bluetape4k-diagram` checklist.
- Examples smoke workflow and script include `:kotlin-flow-extensions-event-aggregation:test`.
- Step 6-R and Step 7-R reviews converge with P0 = 0 and P1 = 0.

# Flow Event Aggregation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build issue #303 as a new in-memory Flow event aggregation workshop example.

**Architecture:** Add `kotlin/flow-extensions-event-aggregation` with a focused order-event domain and `OrderEventAggregationPipeline` functions that demonstrate bounded batching, rolling windows, grouping, state accumulation, unchanged-status suppression, transition detection, and debug audit logging. Keep the example process-local and deterministic so learners can understand the Flow semantics before adding durable event infrastructure.

**Tech Stack:** Kotlin/JVM, Java 21, `bluetape4k-coroutines`, `bluetape4k-junit5`, `bluetape4k-assertions`, `kotlinx-coroutines-test`, CairoSVG-rendered README diagrams.

---

## File Structure

- Create `kotlin/flow-extensions-event-aggregation/build.gradle.kts`: dependencies matching sibling Flow modules.
- Create `kotlin/flow-extensions-event-aggregation/src/main/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventDomain.kt`: serializable events, read model, summaries, status runs, transitions, and audit entries.
- Create `kotlin/flow-extensions-event-aggregation/src/main/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventAggregationPipeline.kt`: Flow extension pipelines.
- Create `kotlin/flow-extensions-event-aggregation/src/test/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventAggregationPipelineTest.kt`: acceptance tests.
- Create test resources `junit-platform.properties` and `logback-test.xml`.
- Create `kotlin/flow-extensions-event-aggregation/README.md` and `README.ko.md`.
- Modify root `README.md` and `README.ko.md` Async & Reactive tables.
- Create README diagram assets under `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-*.svg/png`.
- Modify `scripts/validate-readme-architecture-diagrams.mjs` and `scripts/validate-sequence-diagrams.mjs` only if validator allowlists require the new diagram slugs.
- Modify `.github/workflows/Examples.yml` and `scripts/smoke-validate.sh` so this in-memory Flow module joins smoke example coverage.
- Create `docs/review/2026-06-29-issue-303-flow-event-aggregation-review.md`.
- Create `docs/lessons/2026-06-29-issue-303-flow-event-aggregation.md`.

## Task 1: Module Skeleton And Domain

**Complexity:** medium  
**Applies:** `$bluetape4k-code-patterns`

**Files:**
- Create: `kotlin/flow-extensions-event-aggregation/build.gradle.kts`
- Create: `kotlin/flow-extensions-event-aggregation/src/main/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventDomain.kt`
- Create: `kotlin/flow-extensions-event-aggregation/src/test/resources/junit-platform.properties`
- Create: `kotlin/flow-extensions-event-aggregation/src/test/resources/logback-test.xml`

- [ ] Add Gradle dependencies copied from sibling Flow modules:

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

- [ ] Add serializable domain model:
  - `sealed interface OrderEvent : Serializable { val orderId: String; val occurredAt: Instant; val eventType: String }`
  - Event classes: `OrderCreated`, `LineAdded`, `PaymentAuthorized`, `ShipmentStarted`, `OrderCancelled`.
  - `enum class OrderStatus { NEW, CREATED, PAID, SHIPPED, CANCELLED }`.
  - Data classes: `OrderState`, `OrderReadModel`, `OrderActivitySummary`, `OrderStatusRun`, `OrderTransition`, `OrderAuditEntry`.
- [ ] Use constructor `init` blocks with bluetape4k validation helpers:
  - `orderId.requireNotBlank("orderId")`
  - `customerId.requireNotBlank("customerId")`
  - `sku.requireNotBlank("sku")`
  - `quantity.requirePositiveNumber("quantity")`
  - `amountCents.requirePositiveNumber("amountCents")`
  - `carrier.requireNotBlank("carrier")`
  - `trackingNumber.requireNotBlank("trackingNumber")`
  - `reason.requireNotBlank("reason")`
- [ ] Add safe `toString()` overrides for event classes that hide customer id, tracking number, and cancellation reason.
- [ ] Implement event classes as regular validated classes with private constructors plus companion factories. Do not expose public data-class `copy(...)` for event inputs. If a private data class is used internally, apply the repo's `@ConsistentCopyVisibility` pattern and add a review checklist item proving no public `copy(...)` path can create untrimmed, control-character, or overlong values.
- [ ] Reject control characters and overlong values for every string; use a printable ASCII token pattern for log-visible identifiers such as `orderId`, `sku`, and `carrier`.
- [ ] Restrict `OrderAuditEntry` to sanitized fields only: `sequence`, `eventType`, `orderId`, `status`, counts, amount, version, and timestamp. Do not store raw `customerId`, `trackingNumber`, or cancellation `reason`.
- [ ] Add `private const val serialVersionUID: Long = 1L` in each concrete serializable class companion.
- [ ] Add English KDoc for public domain types and note that `Serializable` is a repo convention, not a persistence/untrusted-deserialization feature.
- [ ] Confirm no README, KDoc, or example introduces Java object deserialization or persistence semantics.
- [ ] Add standard test resources by copying sibling Flow module patterns.
- [ ] Run module registration check after the module exists:

```bash
./gradlew projects --console=plain
```

Expected evidence: `:kotlin-flow-extensions-event-aggregation` appears.

## Task 2: RED Tests For Flow Semantics

**Complexity:** high  
**Applies:** `$bluetape4k-code-patterns`, `$test-driven-development`

**Files:**
- Create: `kotlin/flow-extensions-event-aggregation/src/test/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventAggregationPipelineTest.kt`

- [ ] Add tests before implementation. Test names:
  - ``chunked activity emits bounded event summaries``
  - ``rolling activity emits overlapping summary windows``
  - ``grouped events partition completed stream by order id``
  - ``read models accumulate state per order id``
  - ``unchanged status runs collapse repeated created updates``
  - ``transitions emit lifecycle changes only``
  - ``audit stream preserves readable event order``
  - ``domain values reject blank ids and non positive amounts``
  - ``domain values reject control characters and overlong identifiers``
  - ``sensitive fields are trimmed bounded and reject control characters``
  - ``event construction has no public copy bypass for validation``
  - ``debug rendering hides customer tracking and cancellation details``
  - ``invalid pipeline parameters fail before collection``
  - ``cancelled status stays terminal while audit version advances``
  - ``duplicate and out of order lifecycle events converge deterministically``
  - ``finite high cardinality grouping emits every order group once``
  - ``bounded read model growth remains predictable for many active orders``
  - ``long unchanged status run collapses but retains run until boundary``
  - ``rolling activity emits full and partial tail windows``
  - ``collector cancellation stops upstream collection``
  - ``upstream failure propagates through each aggregation path``
  - ``cancellation exception is not wrapped by aggregation paths``
- [ ] Use `io.bluetape4k.junit5.coroutines.runSuspendTest`.
- [ ] Use `io.bluetape4k.assertions.assertFailsWith`, `shouldBeEqualTo`, `shouldContain`, `shouldHaveSize`, `shouldBeEmpty`, and dot-call boolean matchers.
- [ ] Do not use JUnit, AssertJ, Kluent, or `kotlin.test` assertions.
- [ ] Use finite `flowOf(...)` inputs for normal behavior, grouping, windowing, and projection tests. Do not use sleeps, timers, or wall-clock scheduler assertions.
- [ ] For the cancellation test only, use a controlled `flow { emit(...); awaitCancellation() }` or gated `MutableSharedFlow` source with `cancelAndJoin`, and assert an `onCompletion` or `finally` flag so upstream cancellation is deterministic without sleeps.
- [ ] Verify `groupedByOrder` result order by `associateBy { it.key }` or sorted keys; assert strict original order only inside each group's `values`.
- [ ] Verify high-cardinality `groupedByOrder` with more distinct order ids than `flatMapMerge` default concurrency inside `withTimeout`, and keep it documented as a finite demo. If this exposes a timeout risk, bound concurrency explicitly and update README/KDoc to match.
- [ ] Verify function-specific failure propagation:
  - `chunkedActivity`, `rollingActivity`, `readModels`, `statusRuns`, `transitions`, and `audit` propagate the original upstream exception.
  - `groupedByOrder` wraps non-cancellation upstream failures in `FlowOperationException` with the original exception as `cause`.
  - Every path propagates `CancellationException` without wrapping.
- [ ] Decide and document that public `readModels(events)` emits the initial empty `OrderReadModel` from `scanWith`; tests and README must name that first emission.
- [ ] Test empty stream behavior, first-event transition behavior, unknown order id projection behavior, duplicate payment behavior, out-of-order shipment behavior, and terminal `CANCELLED` version advance.
- [ ] Sensitive field validation tests must cover blank, trimming, overlong, and control-character behavior for `customerId`, `trackingNumber`, and cancellation `reason`, not only `orderId`, `sku`, or `carrier`.
- [ ] Verify RED before production implementation:

```bash
./gradlew :kotlin-flow-extensions-event-aggregation:test --tests "io.bluetape4k.workshop.flow.event.aggregation.OrderEventAggregationPipelineTest" --console=plain
```

Expected evidence: tests fail because `OrderEventAggregationPipeline` behavior is not implemented yet. If Task 1 already created domain types, the RED evidence may be unresolved pipeline symbols or failing behavior assertions, not necessarily missing domain classes.

## Task 3: Pipeline Implementation

**Complexity:** high  
**Applies:** `$bluetape4k-code-patterns`

**Files:**
- Create: `kotlin/flow-extensions-event-aggregation/src/main/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventAggregationPipeline.kt`
- Modify: `kotlin/flow-extensions-event-aggregation/src/main/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventDomain.kt`

- [ ] Implement `OrderState.apply(event: OrderEvent): OrderState`:
  - `OrderCreated` moves `NEW -> CREATED`.
  - `LineAdded` increments line count and item quantity but keeps current lifecycle status unless status is `NEW`, then uses `CREATED`.
  - `PaymentAuthorized` sets `authorizedAmountCents` and moves non-cancelled orders to `PAID`.
  - `ShipmentStarted` moves non-cancelled orders to `SHIPPED`.
  - `OrderCancelled` moves to terminal `CANCELLED`.
  - Once `CANCELLED`, later non-cancel events keep status `CANCELLED` while still increasing version/last event for audit visibility.
- [ ] Accept out-of-order projection events rather than rejecting them:
  - `ShipmentStarted` before payment moves to `SHIPPED`.
  - Duplicate `PaymentAuthorized` stays `PAID` and updates amount/version if not cancelled.
  - `OrderCancelled` after `SHIPPED` moves to terminal `CANCELLED`.
  - Later non-cancel events after `CANCELLED` keep `CANCELLED`.
- [ ] Implement `OrderReadModel.apply(event)` by replacing only the changed order entry in an immutable map copy.
- [ ] Implement `OrderEventAggregationPipeline`:
  - `chunkedActivity(events, chunkSize)` validates `chunkSize > 0`, calls `events.chunked(chunkSize, partialWindow = true)`, then maps each batch to `OrderActivitySummary`.
  - `rollingActivity(events, size, step)` validates the same constraints as `windowed`, calls `events.windowed(size, step, partialWindow = true)`, then maps each window to `OrderActivitySummary`.
  - `groupedByOrder(events)` returns `Flow<GroupItem<String, OrderEvent>>` and calls `events.groupBy { it.orderId }.flatMapMerge(concurrency = GROUPING_CONCURRENCY) { it.toGroupItems() }`, where `GROUPING_CONCURRENCY` is documented as a finite-demo guard.
  - `readModels(events)` calls `events.scanWith({ OrderReadModel.empty() }) { model, event -> model.apply(event) }` and intentionally emits the initial empty model.
  - `statusRuns(events, orderId)` returns `Flow<OrderStatusRun>` by filtering one order, applying `scanWith`, dropping the initial `NEW` state, calling `bufferUntilChanged { it.status }`, and mapping each run list to one DTO with final state.
  - `transitions(events, orderId)` calls `statusRuns(...).map { it.finalState }.zipWithNext { previous, current -> OrderTransition(...) }` and filters out unchanged statuses.
  - `audit(events)` maps to sanitized `OrderAuditEntry` first, then calls `.log("order-event-aggregation")`.
- [ ] Add English KDoc for each public pipeline function, including the `groupBy` vs `bufferUntilChanged` distinction and finite-stream caveat for `groupedByOrder`.
- [ ] Document allocation behavior in KDoc and README:
  - `windowed`/`chunked` emit lists and overlapping windows duplicate retained elements.
  - `rollingActivity(size = 3, step = 1, partialWindow = true)` may emit amplified tail windows; tests lock the count.
  - `statusRuns` uses `bufferUntilChanged`, retains one run until status changes or upstream completes, then copies that run list; cost is `O(runLength)` retention.
  - immutable `OrderReadModel` snapshots allocate per event intentionally for learning clarity; the copy cost is `O(events * activeOrders)` in the finite teaching model.
  - high-throughput production projections should consider bounded mutable internal state, checkpointed projections, and durable stores instead of copying the full active-order map per event.
  - `audit` is diagnostic-only and should be gated/removed in high-throughput production paths.
  - `audit(events)` sanitizes emitted audit values, not arbitrary upstream exception messages; examples/tests use non-sensitive failure messages.
- [ ] Run targeted tests until green:

```bash
./gradlew :kotlin-flow-extensions-event-aggregation:test --rerun-tasks --console=plain
```

Expected evidence: module tests pass.

## Task 4: Documentation And Diagrams

**Complexity:** high  
**Applies:** `$bluetape4k-blog`, `$bluetape4k-diagram`

**Files:**
- Create: `kotlin/flow-extensions-event-aggregation/README.md`
- Create: `kotlin/flow-extensions-event-aggregation/README.ko.md`
- Modify: `README.md`
- Modify: `README.ko.md`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-scenario-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-architecture-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-domain-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-sequence-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-contact-sheet-01.png`

- [ ] README.md sections:
  - Language switch: `[한국어](README.ko.md) | English`
  - Overview
  - Before: mutable map and scheduled flush
  - After: Flow extension pipeline
  - Architecture and scenario diagrams
  - Core domain
  - Pipeline walkthrough
  - Used Bluetape4k features table
  - Scope and durable event store/outbox caveat
  - Resource ownership and recovery semantics
  - Durable store/outbox responsibility table
  - Debug audit/logging boundary
  - Rollout, rollback, and operator verification notes
  - Run commands
- [ ] README.ko.md mirrors the same source facts in natural Korean:
  - Language switch: `[English](README.md) | 한국어`
  - Avoid literal translation; keep technical terms clear.
- [ ] Root README tables add a Basic Async & Reactive row:
  - `flow-extensions-event-aggregation`
  - libs: `coroutines`, `junit5`
  - infra: `In-memory`
  - learning outcome: event aggregation with chunk/window/group/scan/suppression/transition Flow extensions.
- [ ] Generate diagrams with English labels, `Architects Daughter` and `Comic Mono`, no Graphviz, no invented infra icons, and top-to-bottom architecture flow with visible layers.
- [ ] Render every SVG to PNG with `~/.local/bin/cairosvg`.
- [ ] Run diagram validation:

```bash
xmllint --noout docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-*.svg
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-*.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-endpoint-audit.py docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-*.svg
```

- [ ] Record CairoSVG render success for every SVG.
- [ ] Create and inspect the contact sheet plus every full-size PNG with visual inspection; store the evidence in `docs/review/2026-06-29-issue-303-flow-event-aggregation-review.md`.

Expected evidence: validators pass, every SVG renders, and visual inspection passes.

## Task 5: Smoke Registration And Verification

**Complexity:** medium  
**Applies:** `$bluetape4k-code-patterns`

**Files:**
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`

- [ ] Add `kotlin/flow-extensions-event-aggregation/**` to push and pull request path filters in `.github/workflows/Examples.yml`.
- [ ] Add `:kotlin-flow-extensions-event-aggregation:test` to the `smoke-examples` Gradle command.
- [ ] Add the exact new test result paths to the smoke artifact upload:
  - `kotlin/flow-extensions-event-aggregation/build/test-results/test/*.xml`
  - `kotlin/flow-extensions-event-aggregation/build/reports/tests/test/`
- [ ] Add `:kotlin-flow-extensions-event-aggregation:test` to `scripts/smoke-validate.sh` in `all-smoke` and `async`.
- [ ] Note that adding the task to `all-smoke` intentionally covers `.github/workflows/nightly.yml` smoke runs; no Nightly workflow edit is needed unless direct workflow grouping changes.
- [ ] Update `stale-check` expected project count by +1 after confirming `./gradlew projects` count.
- [ ] Run:

```bash
actionlint .github/workflows/Examples.yml
./gradlew projects --console=plain
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
./scripts/smoke-validate.sh async
./scripts/smoke-validate.sh stale-check
git diff --check
```

Expected evidence: actionlint passes, project count is current, async smoke passes, and diff check is clean.

## Task 6: Reviews, Lessons, PR, And CI

**Complexity:** high  
**Applies:** `$bluetape4k-workflow`, `$bluetape4k-full-feature`

**Files:**
- Create: `docs/review/2026-06-29-issue-303-flow-event-aggregation-review.md`
- Create: `docs/lessons/2026-06-29-issue-303-flow-event-aggregation.md`

- [ ] Run Step 5 verifier check against issue #303 and this spec/plan.
- [ ] Run Step 6-R review with six independent perspectives plus main integration; converge P0 = 0 and P1 = 0.
- [ ] Create a concise lesson:
  - Context: event aggregation Flow example.
  - Decision: separate `groupBy` global grouping from `bufferUntilChanged` adjacent suppression.
  - Outcome: tests and README make the distinction explicit.
  - Future guidance: do not use `bufferUntilChanged` as an all-order grouping primitive.
- [ ] Commit implementation using Lore commit protocol.
- [ ] Create PR against `develop`:
  - Title: `feat: add Flow event aggregation workshop`
  - Assignee: `debop`
  - Milestone: `1.2.0`
  - Labels mirrored from issue #303: `documentation`, `enhancement`, `difficulty:intermediate`, `area:async-reactive`, `coroutines`
  - Body links `Closes #303`
  - Final Markdown `##` section is exactly `## DoD Status`.
- [ ] Verify live metadata:

```bash
gh issue view 303 --json assignees,labels,milestone,state
gh pr view <number> --json assignees,labels,milestone,body
```

- [ ] Run PR checks, verify PR body/metadata, and re-read reviews/review threads after checks are green.
- [ ] Report merge-ready status and stop unless the user has explicitly requested merge for this PR.
- [ ] If the user explicitly requests merge, re-read reviews/review threads immediately before merge, merge with rebase, sync local `develop`, remove the feature worktree only after merge ancestry is proven, and verify issue #303 closed.

# Workshop 재사용 Inventory 및 Kotlin Pattern Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 릴리스 기반 재사용 경계를 기록하고, virtual-thread SSE P1 위반을 수정하며, 남은 Kotlin pattern P2를 독립적으로 검증 가능한 이슈로 분해한다.

**Architecture:** 이 coordination branch는 #563의 재사용 matrix, #564의 severity evidence, Order Lifecycle SSE P1 수정과 SSE poll configuration의 exact released-helper 전환만 소유한다. `shared` API를 추가하지 않고, existing released API 또는 provider issue를 선택한다. Job Console UUID, 대규모 test assertion migration, 남은 validation migration은 파일·module 검증 범위가 독립적이므로 linked follow-up issue로 분리한다.

**Tech Stack:** Kotlin 2.4, Java 21 virtual threads, Spring MVC `SseEmitter`, Gradle, `bluetape4k-dependencies` BOM, `bluetape4k-core`, `bluetape4k-assertions`, `bluetape4k-junit5`, GitHub CLI.

---

## Files and Artifacts

- Existing design: `docs/superpowers/specs/2026-07-24-issues-563-564-reuse-and-kotlin-patterns-design.md`
- Create: `docs/review/2026-07-24-issues-563-564-reuse-inventory.md`
- Modify: `commerce/order-lifecycle-fulfillment/src/main/kotlin/io/bluetape4k/workshop/commerce/order/web/OrderEventStream.kt`
- Modify: `commerce/order-lifecycle-fulfillment/src/test/kotlin/io/bluetape4k/workshop/commerce/order/web/OrderEventStreamTest.kt`
- Modify only when released APIs provide exact parity: the raw validation
  call sites in `PublicationReconciliationService.kt`,
  `IdempotentOrderSubmissionService.kt`, `OrderCommandService.kt`,
  `web/OrderController.kt`, `web/OrderEventStream.kt`,
  `idempotency/HttpIdempotencyRepository.kt`, and `IdempotencyCleanupService.kt`
- Create: `docs/review/2026-07-24-issues-563-564-kotlin-pattern-review.md`
- Create: `docs/lessons/2026-07-24-issues-563-564-reuse-and-kotlin-patterns.md`

## Task 1: Establish the release-first reuse inventory (#563)

**Files:**
- Create: `docs/review/2026-07-24-issues-563-564-reuse-inventory.md`
- Modify: GitHub issue #563 through a comment/checklist update only after the file is committed.

- [ ] **Step 1: Verify the BOM-resolved Observation artifact before accepting #561 as a migration candidate.**

Run:

```bash
./gradlew :observability-basic:dependencyInsight \
  --dependency bluetape4k-micrometer \
  --configuration runtimeClasspath \
  --console=plain
```

Expected: the resolved `io.github.bluetape4k:bluetape4k-micrometer` version is
reported. Inspect that exact JAR with `javap` and confirm the public signatures
for `withObservationSuspending` and `withObservationContextSuspending`.

- [ ] **Step 2: Write the inventory decision record.**

Create a Korean table with the following mandatory rows and evidence anchors:

```markdown
| Candidate | Disposition | Evidence | Follow-up |
|---|---|---|---|
| `observed` in basic/advanced observability | released-bluetape4k candidate | two implementations, five production callers, resolved artifact signature | #561 parity tests before deletion |
| shared HTTP extensions | retain in `shared` | consumers across independent module groups and three contract suites | inspect Spring 4/API overlap only |
| voucher black-box contract | retain in `shared` | two independent voucher implementations | revisit only after third implementation |
| graph `requireEndpoint` | provider-gap candidate | repeated graph-service validation | check released graph API; open provider issue only if absent |
| Exposed DTO mappers and Mongo test bases | example-specific | table/fixture-specific contracts | no extraction |
```

- [ ] **Step 3: Record #563's live issue disposition.**

After the inventory file is committed, add an English issue comment that links
the commit and states that #561 remains the Observation implementation child.
For the graph candidate, create a provider issue only if the released graph
artifact lacks the required typed API; otherwise record the existing API and
do not create a duplicate issue.

- [ ] **Step 4: Validate the artifact.**

Run:

```bash
git diff --check
rg -n 'released-bluetape4k|Workshop shared|provider-gap|example-specific' \
  docs/review/2026-07-24-issues-563-564-reuse-inventory.md
```

Expected: no whitespace errors and all four disposition categories appear with
source/release evidence.

## Task 2: Lock the Order Lifecycle SSE P1 regression before changing concurrency (#564)

**Files:**
- Modify: `commerce/order-lifecycle-fulfillment/src/test/kotlin/io/bluetape4k/workshop/commerce/order/web/OrderEventStreamTest.kt`
- Modify: `commerce/order-lifecycle-fulfillment/src/main/kotlin/io/bluetape4k/workshop/commerce/order/web/OrderEventStream.kt`

- [ ] **Step 1: Capture the behavioral and structural baseline.**

This is a behavior-preserving structural refactor, so do not add a brittle test
that reads a production source file. Run the existing focused suite first and
capture the two prohibited constructs with a static scan:

```bash
./gradlew :commerce-order-lifecycle-fulfillment:test \
  --tests '*OrderEventStreamTest' \
  --console=plain
rg -n 'synchronized\\(|!!' \
  commerce/order-lifecycle-fulfillment/src/main/kotlin/io/bluetape4k/workshop/commerce/order/web/OrderEventStream.kt
```

Expected: the behavioral suite passes at baseline, while the scan reports the
monitor sections and `compute(...)!!`. Existing race tests establish behavioral
regression protection; the scan establishes the structural P1 removal target.

- [ ] **Step 2: Preserve behavioral race coverage before the refactor.**

Keep and tighten these existing tests with `bluetape4k-assertions` and
Awaitility, never `Thread.sleep`:

```kotlin
fun `shutdown rejects an open racing with the initial snapshot`()
fun `connections for the same order share one database poller`()
fun `shutdown cancels all blocked pollers within one bounded deadline`()
fun `heartbeat is emitted and timeout releases the connection slot`()
fun `client disconnect releases the connection slot`()
```

Replace the fixed wait in the shared-poller test with an Awaitility condition
that observes a stable single-poller state; use a latch/future outcome for the
bounded interval rather than sleeping the test thread.

- [ ] **Step 3: Replace the monitor with an explicit lock and remove `!!`.**

Change imports and state to:

```kotlin
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val lifecycleLock = ReentrantLock()
```

Replace each lifecycle critical section with `lifecycleLock.withLock { ... }`.
Replace the compute result assertion with a checked internal invariant:

```kotlin
val feed = checkNotNull(
    feeds.compute(orderId) { _, current ->
        current?.takeUnless { it.closed.get() } ?: OrderFeed(orderId)
    }
) { "feed creation must return an OrderFeed" }
```

Keep external IO (`queries.snapshot`, `emitter.send`, future cancellation, and
emitter completion) outside the lock exactly as in the current ownership flow.

- [ ] **Step 4: Prove the structural change and run the focused suite.**

Run, in this order:

```bash
rg -n 'synchronized\\(|!!' \
  commerce/order-lifecycle-fulfillment/src/main/kotlin/io/bluetape4k/workshop/commerce/order/web/OrderEventStream.kt
./gradlew :commerce-order-lifecycle-fulfillment:test \
  --tests '*OrderEventStreamTest' \
  --console=plain
./gradlew :commerce-order-lifecycle-fulfillment:test --console=plain
```

Expected: the scan produces no matches, all `OrderEventStreamTest` cases pass,
and no capacity leak, duplicate poller, shutdown timeout, or open/shutdown race
regression appears.

## Task 3: Adopt an exact released validation helper and isolate the remaining validation work (#564)

**Files:**
- Modify: `OrderEventStream.kt` and `OrderEventStreamTest.kt` for the
  `maxConcurrentPolls` exact positive-number contract.
- Create one follow-up issue for the remaining Order Lifecycle validation sites.

- [ ] **Step 1: Inspect exact released `RequireSupportKt` signatures.**

Run:

```bash
javap -classpath <resolved-bluetape4k-core-jar> \
  -public io.bluetape4k.support.RequireSupportKt
```

Expected: identify exact available `requireInRange`, `requirePositiveNumber`,
and related overloads. Do not infer regex/length helpers from provider `develop`.

- [ ] **Step 2: Convert the exact SSE positive-number match.**

First add and observe a failing module-local structural test that reads the
target source and requires
`requirePositiveNumber("order-lifecycle.sse.max-concurrent-polls")`. This is
limited to release-helper adoption; the monitor invariant itself is proven by
reflection rather than source text.

Replace the `maxConcurrentPolls` raw predicate with the released helper while
retaining the validated value and `IllegalArgumentException` contract:

```kotlin
val validMaxConcurrentPolls = maxConcurrentPolls.requirePositiveNumber("maxConcurrentPolls")
```

Do not broaden this coordination change into the remaining range, regex, and
length checks. Open a dedicated Order Lifecycle validation issue; it must use
range helpers only where inclusive boundaries and diagnostics match, and link
`bluetape4k-projects#1079` for unavailable regex/length APIs rather than adding
a Workshop helper.

- [ ] **Step 3: Run targeted validation-contract tests.**

Run:

```bash
./gradlew :commerce-order-lifecycle-fulfillment:test \
  --tests '*LifecyclePoliciesTest' \
  --tests '*OrderEventStreamTest' \
  --console=plain
```

Expected: valid values are returned, invalid caller input remains
`IllegalArgumentException`, and no business/domain exception changes type.

## Task 4: Decompose independent P2 remediation into bounded issues (#564)

**Files:**
- Modify: GitHub issue #564 via an English evidence comment/checklist update.

- [ ] **Step 1: Create the Job Console UUID follow-up issue.**

Scope it to these exact production paths:

```text
operations/job-console-core/src/main/kotlin/**/JobOutboxRepository.kt
operations/job-console-core/src/main/kotlin/**/JobRepository.kt
operations/job-console-spring/src/main/kotlin/**/JobConsoleProblemHandler.kt
operations/job-console-spring/src/main/kotlin/**/JobConsoleSpringController.kt
operations/job-console-ktor/src/main/kotlin/**/JobConsoleKtorApplication.kt
```

The issue must require `Uuid.V7.nextId()` for UUID values and
`Uuid.V7.nextId().toString()` for string correlation/subscription values, with
persistence, response, and subscription uniqueness tests.

- [ ] **Step 2: Create one assertion-migration issue per affected Gradle module.**

Create three issues, each with the exact candidate files listed below and no
cross-module implementation scope:

```text
:commerce-usage-metering-billing-event-sourcing (21 files)
:commerce-reservation-control-plane (7 files)
:commerce-promotion-voucher-campaign (4 files)
```

Each issue requires `io.bluetape4k.assertions.assertFailsWith`, intent-specific
matchers, and existing coroutine/Awaitility primitives. It must exclude the
currently merged event-sourced voucher worktree and prohibit a generic test
framework wrapper.

- [ ] **Step 3: Update #564 with severity convergence.**

Post an English comment that marks the SSE P1 as fixed only after its fresh
module validation passes; links the five P2 issues; and reports all clean scan
categories as N/A with concrete searches. Do not close #564 while linked P2
issues remain open.

## Task 5: Final review, documentation, and validation

**Files:**
- Create: `docs/review/2026-07-24-issues-563-564-kotlin-pattern-review.md`
- Create: `docs/lessons/2026-07-24-issues-563-564-reuse-and-kotlin-patterns.md`

- [ ] **Step 1: Render the Kotlin review artifact.**

Record KT-01 through KT-05 and KT-FIN-01 through KT-FIN-11 with exact source,
test, release-JAR, and command evidence. State `P0=0`, whether the SSE P1 is
resolved, and which P2 issues remain open.

- [ ] **Step 2: Add the durable Korean lesson.**

Explain why `shared` needs repeated independent contracts, why a released JAR
rather than a provider checkout governs Workshop adoption, and why virtual
thread code cannot keep monitors even for short lifecycle sections.

- [ ] **Step 3: Run the required checks sequentially.**

Run:

```bash
./gradlew :commerce-order-lifecycle-fulfillment:compileKotlin \
  :commerce-order-lifecycle-fulfillment:compileTestKotlin \
  :commerce-order-lifecycle-fulfillment:test \
  --warning-mode all --console=plain
./gradlew detekt --console=plain
git diff --check
```

Expected: all commands pass. If Gradle reports an existing failure, capture it,
separate it from the branch diff, and do not claim the relevant check passed.

- [ ] **Step 4: Commit the converged scope with Lore trailers.**

Commit only inventory, Order Lifecycle, review, lesson, and plan/spec files.
The commit must name the release-first boundary, reject a generic `shared`
wrapper, record fresh validation, and list unresolved P2 work as linked
follow-up rather than a hidden gap.

## Plan Self-Review

- Spec coverage: Task 1 maps every #563 disposition; Tasks 2–4 cover #564 P1,
  matched validation, and all discovered P2 families; Task 5 covers review,
  lessons, and validation.
- Dependency order: release evidence precedes reuse decisions; SSE behavioral
  and structural baselines precede mutation; SSE green validation precedes the
  live #564 status update.
- Scope boundary: no `shared` addition, no new dependency, no Observation code
  deletion, and no unbounded assertion rewrite occur on this coordination branch.
- Rollback: restore the pre-lock `OrderEventStream` only if the preserved race
  tests fail; leave a provider gap unchanged if no released API parity exists.

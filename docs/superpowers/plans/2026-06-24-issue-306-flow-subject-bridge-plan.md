# Flow Subject Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build issue #306 as a new callback-to-Flow Subject Bridge workshop example.

**Architecture:** Add one in-memory Kotlin module under `kotlin/flow-extensions-subject-bridge`. `DeviceSubjectBridge` exposes read-only `Flow` views and owns Subject mutation methods so README readers learn selection semantics without copying globally mutable streams.

**Tech Stack:** Kotlin 2.4, Java 21, `bluetape4k-coroutines`, `bluetape4k-junit5`, `bluetape4k-assertions`, CairoSVG-rendered README diagrams.

---

### Task 1: Module skeleton and domain model

**Files:**
- Create: `kotlin/flow-extensions-subject-bridge/build.gradle.kts`
- Create: `kotlin/flow-extensions-subject-bridge/src/main/kotlin/io/bluetape4k/workshop/flow/subject/bridge/DeviceBridgeDomain.kt`
- Create: `kotlin/flow-extensions-subject-bridge/src/test/resources/junit-platform.properties`
- Create: `kotlin/flow-extensions-subject-bridge/src/test/resources/logback-test.xml`

- [ ] Add dependencies matching `flow-extensions-parallel-enrichment`.
- [ ] Define serializable domain records: `DeviceEvent`, `DeviceState`, `WorkItem`.
- [ ] Define `DeviceStatus` and `DeviceEventType` enums.

### Task 2: Bridge implementation

**Files:**
- Create: `kotlin/flow-extensions-subject-bridge/src/main/kotlin/io/bluetape4k/workshop/flow/subject/bridge/DeviceSubjectBridge.kt`

- [ ] Back read-only flows with `PublishSubject`, `BehaviorSubject`, `ReplaySubject`, `MulticastSubject`, and `UnicastWorkSubject`.
- [ ] Add callback-style mutation methods for event, state, multicast, work, complete, and failure paths.
- [ ] Add `awaitEventSubscribers`, `awaitMulticastSubscribers`, and `awaitWorkSubscriber` helper methods for deterministic tests and README examples.

### Task 3: Tests

**Files:**
- Create: `kotlin/flow-extensions-subject-bridge/src/test/kotlin/io/bluetape4k/workshop/flow/subject/bridge/DeviceSubjectBridgeTest.kt`

- [ ] Test PublishSubject active-subscriber delivery.
- [ ] Test BehaviorSubject latest-state replay.
- [ ] Test ReplaySubject bounded late history.
- [ ] Test MulticastSubject two-subscriber delivery.
- [ ] Test UnicastWorkSubject single-consumer queue behavior and simultaneous collector rejection.
- [ ] Test normal completion, error completion, and `emitError(null)` no-op semantics.

### Task 4: README and root index

**Files:**
- Create: `kotlin/flow-extensions-subject-bridge/README.md`
- Create: `kotlin/flow-extensions-subject-bridge/README.ko.md`
- Modify: `README.md`
- Modify: `README.ko.md`

- [ ] Add language switch.
- [ ] Add scenario, architecture, domain/class/sequence sections with diagram embeds.
- [ ] Add Before/After and Subject selection guide.
- [ ] Add Used Bluetape4k features table and test command.
- [ ] Register module in root Async & Reactive tables.

### Task 5: Diagram assets

**Files:**
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-scenario-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-architecture-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-erd-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-class-diagram-01.svg/png`
- Create: `docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-sequence-01.svg/png`

- [ ] Use English labels.
- [ ] Use orthogonal connectors with short rounded corners, no spline-like curves.
- [ ] Render PNG via CairoSVG and inspect full-size PNGs.
- [ ] Run geometry and endpoint audits.

### Task 6: Validation and review

**Files:**
- Create: `docs/review/2026-06-24-issue-306-flow-subject-bridge-review.md`
- Create: `docs/lessons/2026-06-24-issue-306-flow-subject-bridge.md`

- [ ] Run `./gradlew :kotlin-flow-extensions-subject-bridge:test`.
- [ ] Run `./gradlew :kotlin-flow-extensions-subject-bridge:compileKotlin :kotlin-flow-extensions-subject-bridge:compileTestKotlin`.
- [ ] Run `./gradlew projects` and confirm module registration.
- [ ] Run `git diff --check`.
- [ ] Run diagram XML/render/geometry/endpoint/visual validation.
- [ ] Run integrated 7-tier review and record P0/P1 = 0.

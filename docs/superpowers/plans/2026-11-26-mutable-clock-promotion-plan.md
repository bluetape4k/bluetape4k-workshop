# MutableClock 공통 테스트 fixture 승격 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 여러 주제의 wall-clock 테스트 구현을 shared test fixture로 단일화하면서 consumer별 이름·기본값·zone 계약을 보존한다.

**Architecture:** `shared`의 `MutableClock`이 atomic timeline과 zone view를 소유한다. 기존 public test fixture 이름이 있는 operations는 wrapper로 유지하고, private 테스트 clock은 공통 타입으로 교체한다. `VirtualClock`과 업무별 시간 모델은 범위 밖으로 둔다.

**Tech Stack:** Kotlin 2.4, Java 25, Gradle test fixtures, JUnit 5, bluetape4k assertions, `java.time.Clock`.

---

### Task 1: Phase B evidence and spec/plan gate

**Files:**
- Create: `.bluetape/inputs/mutable-clock-*` evidence files
- Create: `docs/superpowers/specs/2026-11-26-mutable-clock-promotion-design.md`
- Create: `docs/superpowers/plans/2026-11-26-mutable-clock-promotion-plan.md`

- [x] **Step 1: Record approved scope and current module inventory**

  Source anchors are AWS `KinesisConsumerFlowTest`, promotion voucher admission/reconciliation, pre-generated pool admission, usage metering command receipt, operations `JobConsoleFixtureClock`, and optimization field-service outbox fencing. `VirtualClock` is explicitly excluded.

- [x] **Step 2: Review spec and plan against the Phase A boundary**

  Confirm that no production API, BOM, or upstream publication is included and that every consumer migration has a rollback point.

- [ ] **Step 3: Commit the artifacts before code mutation**

  Run `git diff --check`, then commit the spec and plan with Lore trailers.

### Task 2: Shared contract RED test

**Files:**
- Create: `shared/src/test/kotlin/io/bluetape4k/workshop/shared/time/MutableClockTest.kt`
- Create: `shared/src/testFixtures/kotlin/io/bluetape4k/workshop/shared/time/MutableClock.kt` (after RED)

- [ ] **Step 1: Write the failing direct contract test**

  Cover initial instant/zone, `advance(Duration)`, `advanceSeconds`, negative duration, `withZone` shared timeline, and parallel atomic increments. Use `shouldBeEqualTo` and actual values.

- [ ] **Step 2: Run the RED proof**

  Run `./gradlew :shared:test --tests '*MutableClockTest' --no-daemon --console=plain`.

  Expected: compile failure because `io.bluetape4k.workshop.shared.time.MutableClock` does not exist.

- [ ] **Step 3: Implement the minimal shared fixture**

  Use one `AtomicReference<Instant>` shared by zone views. `advance` and `advanceSeconds` use `updateAndGet`; no caller validation or scheduler behavior is added.

- [ ] **Step 4: Run the GREEN proof**

  Run the same targeted command and then `./gradlew :shared:testFixturesClasses --no-daemon --console=plain`.

### Task 3: Operations wrapper and consumer dependency boundary

**Files:**
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/JobConsoleFixtureClock.kt`
- Modify: `operations/job-console-core/build.gradle.kts`
- Test: operations fixture compile and existing clock consumers

- [ ] **Step 1: Preserve the named wrapper**

  Make `JobConsoleFixtureClock` extend shared `MutableClock`, retaining the existing default initial instant and `advance(Duration): Instant` behavior.

- [ ] **Step 2: Wire only test fixtures**

  Keep `testImplementation(testFixtures(project(":shared")))`; do not reintroduce a main `project(":shared")` test dependency.

- [ ] **Step 3: Verify operations**

  Run `./gradlew :operations-job-console-core:testFixturesClasses :operations-job-console-core:test --tests '*JobConsole*' --no-daemon --console=plain`.

### Task 4: Migrate AWS, commerce, metering, and optimization consumers

**Files:**
- Modify build files: `aws/kinesis-coroutines/build.gradle.kts`, `commerce/promotion-voucher-campaign/build.gradle.kts`, `commerce/pre-generated-voucher-pool/build.gradle.kts`, `commerce/usage-metering-billing-ledger/build.gradle.kts`, `optimization/field-service-dispatch/build.gradle.kts`
- Modify tests: the six clock-containing test files identified in the spec
- Test: each module's targeted compile/test

- [ ] **Step 1: Replace local clock definitions with shared imports**

  Remove only the duplicate `Clock` implementations and unused imports. Keep each test's default instant and existing method calls; use `advanceSeconds` where the caller already uses seconds.

- [ ] **Step 2: Replace `project(":shared")` with test fixtures**

  Add `testImplementation(testFixtures(project(":shared")))` to each consumer. No version catalog or production dependency changes are allowed.

- [ ] **Step 3: Compile all migrated test sources**

  Run `./gradlew :aws-kinesis-coroutines:compileTestKotlin :commerce-promotion-voucher-campaign:compileTestKotlin :commerce-pre-generated-voucher-pool:compileTestKotlin :commerce-usage-metering-billing-ledger:compileTestKotlin :optimization-field-service-dispatch:compileTestKotlin --no-daemon --console=plain`.

- [ ] **Step 4: Run targeted tests sequentially**

  Run the existing clock-bearing test classes one module at a time, preserving Testcontainers serialization:

  ```bash
  ./gradlew :aws-kinesis-coroutines:test --tests '*KinesisConsumerFlowTest' --no-daemon --console=plain
  ./gradlew :commerce-promotion-voucher-campaign:test --tests '*VoucherAdmissionGateTest' --tests '*VoucherReconciliationServiceTest' --no-daemon --console=plain
  ./gradlew :commerce-pre-generated-voucher-pool:test --tests '*VoucherPoolAdmissionGateTest' --no-daemon --console=plain
  ./gradlew :commerce-usage-metering-billing-ledger:test --tests '*CommandReceiptPostgresIntegrationTest' --no-daemon --console=plain
  ./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceOutboxFencingTest' --no-daemon --console=plain
  ```

### Task 5: Final verification and evidence

**Files:**
- Create: `.bluetape/inputs/mutable-clock-implementation-evidence.json`
- Create: `.bluetape/inputs/mutable-clock-verification-evidence.json`
- Update: Issue #1069 comment

- [ ] **Step 1: Run diff and scope scans**

  Run `git diff --check`, confirm `VirtualClock` and production source are unchanged, and verify the shared fixture has no external client imports.

- [ ] **Step 2: Run proportional validation**

  Run `./gradlew :shared:testFixturesClasses` plus all migrated compile/test commands. Record any pre-existing Docker image or detekt failures separately.

- [ ] **Step 3: Commit the implementation and evidence**

  Use Korean Lore commit messages. Keep the branch local; no PR, merge, tag, or upstream publication is in this plan.

- [ ] **Step 4: Update issue and close the Phase B receipt**

  Record exact head, tests, changed modules, known gaps, and the conditional `WorkerPid` follow-up in Korean. Complete the workflow only after all required checks and main verification are attached.

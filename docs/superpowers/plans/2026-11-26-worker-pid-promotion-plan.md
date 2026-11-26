# WorkerPid 공통 테스트 fixture 승격 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 두 고경합 consumer의 동일한 worker PID 파일 handoff를 shared test fixture로 단일화하고 기존 호출 이름과 안전 계약을 보존한다.

**Architecture:** `shared`의 `WorkerPidPublisher`가 property 확인, trusted parent 검사, `CREATE_NEW` write, file/directory force를 소유한다. operations와 commerce는 기존 object 이름을 유지하는 얇은 delegate wrapper를 사용한다. production dependency와 published artifact는 건드리지 않는다.

**Tech Stack:** Kotlin 2.4, Java 25, Gradle `java-test-fixtures`, JUnit 5, `bluetape4k-assertions`, `java.nio.file`.

---

### Task 1: Phase C artifact and receipt gate

**Files:**
- Create: `docs/superpowers/specs/2026-11-26-worker-pid-promotion-design.md`
- Create: `docs/superpowers/plans/2026-11-26-worker-pid-promotion-plan.md`
- Create: `.bluetape/inputs/worker-pid-*-evidence.json`

- [x] **Step 1: Record current provenance**

  Confirm the two exact writer files, two exact call sites, identical property and
  filesystem behavior, existing `testFixtures(project(":shared"))` dependencies,
  and the exclusion of production/upstream publication scope.

- [x] **Step 2: Review the design and plan**

  Confirm the recommended wrapper approach, direct contract cases, source-level
  `force(true)` durability rule, rollback point, and sequential consumer proof.
  Record `SPW-01` through `SPW-05` for both artifacts.

- [x] **Step 3: Commit the artifacts before code mutation**

  Run `git diff --check` and commit only the spec/plan with Korean Lore trailers.

### Task 2: Direct shared contract RED test

**Files:**
- Create: `shared/src/test/kotlin/io/bluetape4k/workshop/shared/process/WorkerPidPublisherTest.kt`
- Create later: `shared/src/testFixtures/kotlin/io/bluetape4k/workshop/shared/process/WorkerPidPublisher.kt`

- [x] **Step 1: Write the failing contract test**

  Add JUnit 5 tests using `@TempDir` and `io.bluetape4k.assertions.assertFailsWith`:

  - missing `highContentionWorkerPidFile` is a no-op;
  - blank `highContentionProcessOwner` fails before creating the target;
  - a symbolic-link parent is rejected and the link target remains untouched;
  - a configured directory receives exactly `${ProcessHandle.current().pid()}\\n`
    as UTF-8 bytes;
  - a second publish raises `FileAlreadyExistsException` and preserves the
    original bytes.

  Save and restore both system properties in `@AfterEach` so tests do not leak
  configuration into other shared tests.

- [x] **Step 2: Run RED and read the failure**

  Run:

  ```bash
  ./gradlew :shared:test --tests '*WorkerPidPublisherTest' --no-daemon --console=plain
  ```

  Expected result: test compilation fails because
  `io.bluetape4k.workshop.shared.process.WorkerPidPublisher` does not exist.
  A setup error is not an acceptable RED result; repair the test and rerun.

- [x] **Step 3: Implement the minimum shared writer**

  Add `WorkerPidPublisher.publishIfConfigured()` with the exact contract from
  the spec. Use `requireNotBlank` for owner, `Files.isDirectory(parent,
  NOFOLLOW_LINKS)` plus `!Files.isSymbolicLink(parent)`, `FileChannel.open(path,
  CREATE_NEW, WRITE)`, a complete `ByteBuffer` write, `channel.force(true)`,
  and a directory force that catches only `UnsupportedOperationException`.
  Keep the property names private constants and write Korean KDoc.

- [x] **Step 4: Run GREEN and fixture compilation**

  Run the RED command again and then:

  ```bash
  ./gradlew :shared:testFixturesClasses --no-daemon --console=plain
  ```

  Expected result: all `WorkerPidPublisherTest` cases pass and the shared
  fixture output compiles.

### Task 3: Preserve consumer names with thin delegates

**Files:**
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionWorkerPid.kt`
- Modify: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionWorkerPid.kt`

- [x] **Step 1: Replace duplicated writers with delegates**

  Keep each object and `publishIfConfigured()` signature, remove local
  filesystem imports/constants, and delegate to
  `io.bluetape4k.workshop.shared.process.WorkerPidPublisher`. Keep concise
  Korean KDoc explaining that the local object preserves the consumer boundary.

- [x] **Step 2: Verify consumer compile boundaries**

  Run sequentially:

  ```bash
  ./gradlew :operations-job-console-core:testFixturesClasses --no-daemon --console=plain
  ./gradlew :commerce-concert-ticket-flash-sale:compileTestKotlin --no-daemon --console=plain
  ```

  Expected result: both existing call sites compile through the shared fixture;
  no main `project(":shared")` dependency is added.

### Task 4: Targeted consumer proof

**Files:**
- Test only; no additional production files.

- [x] **Step 1: Run operations high-contention contract/lifecycle tests**

  Run the existing test source sequentially with the shared test fixture:

  ```bash
  ./gradlew :operations-job-console-core:test --tests '*HighContentionProcessProbeTest' --tests '*HighContentionLifecycleTest' --no-daemon --console=plain
  ```

- [x] **Step 2: Run commerce ticket high-contention contract tests**

  Run:

  ```bash
  ./gradlew :commerce-concert-ticket-flash-sale:test --tests '*TicketHighContentionContractTest' --no-daemon --console=plain
  ```

  Container-backed or Redis-backed tests remain sequential. A network/image
  failure must be recorded as a verification gap and must not be promoted to a
  code PASS.

### Task 5: Final verification, lesson, and issue evidence

**Files:**
- Create: `.bluetape/inputs/worker-pid-implementation-evidence.json`
- Create: `.bluetape/inputs/worker-pid-verification-evidence.json`
- Create: `docs/lessons/2026-11-26-worker-pid-promotion.md` when the review confirms a reusable lesson is present
- Update: Issue #1069 comment

- [x] **Step 1: Run scope and static checks**

  Run `git diff --check`, verify only shared test fixture, two wrapper files,
  direct contract test, spec/plan/lesson/evidence are changed, and prove that
  `VirtualClock`, production source, version catalog, BOM, and upstream library
  modules are unchanged.

- [x] **Step 2: Review Kotlin contracts**

  Check assertion matcher choice, owner exception type, symlink boundary,
  duplicate-write behavior, force fallback, Korean KDoc, and the absence of
  production `!!` or swallowed unexpected exceptions. Record P0/P1/P2/P3;
  no P0/P1 may remain.

- [x] **Step 3: Commit the implementation**

  A separate lesson file is N/A for this lane: the reusable provenance-first rule
  is already captured by the existing workspace guidance and Issue #1069 evidence;
  the build-logic profile gap remains an issue-level follow-up rather than a
  WorkerPid implementation lesson.

  Commit the implementation and any evidence-backed lesson with Korean Lore
  trailers. Keep PR creation, push, merge, tag, publication, and release out of
  scope.

- [x] **Step 4: Update Issue #1069 and complete the receipt**

  Record exact feature head, receipt run/checksum, provenance, changed files,
  RED/GREEN and consumer test results, known verification gaps, and the next
  boundary: only a real external `bluetape4k-*` reuse requirement triggers a
  separate API/ABI/BOM/publication design.

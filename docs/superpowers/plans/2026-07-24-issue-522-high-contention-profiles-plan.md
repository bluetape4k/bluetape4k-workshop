# Issue #522 Deterministic High-Contention Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Job Operations Console과 Concert Ticket Flash Sale에 동일한 versioned high-contention 의미를 적용하고, 실제 PostgreSQL·Redis·Toxiproxy 경로에서 correctness/convergence 증거와 환경 종속 성능 관찰값을 분리해 재현 가능한 JSON artifact로 남긴다.

**Architecture:** `profiles/high-contention/v1`이 suite/profile/report vocabulary와 golden schedule의 유일한 공통 source다. Job Console core의 test fixture가 Job core/Spring/Ktor adapter를 지원하고 Ticket은 module-local adapter를 사용한다. 새 generic module이나 cross-domain production SPI는 만들지 않는다. Root Gradle coordinator는 각 child `Test` task를 별도 JVM에서 순차 실행하고, immutable journal/report 및 exact-label cleanup 결과를 검증한 뒤 summary와 upload allowlist를 확정한다.

**Tech Stack:** Kotlin 2.4.0, Java 25 virtual threads, Gradle Kotlin DSL, JUnit 5, Bluetape4k core/Jackson3/logging/assertions/JUnit5/Testcontainers/Lettuce/Micrometer/virtual-thread API, JetBrains Exposed JDBC, Spring Boot 4, Ktor, HikariCP, PostgreSQL 18, Redis 8, Toxiproxy 2.9.0 container with catalog-resolved `toxiproxy-java`.

---

## 실행 경계

- 이 계획은 승인된 설계
  `docs/superpowers/specs/2026-07-24-issue-522-high-contention-profiles-design.md`를 구현한다.
- production HTTP/API/schema 의미를 바꾸지 않는다.
- 일반 `test`는 parser/schedule/report/lifecycle unit test만 실행한다. 실제 profile은 전용 task에서만 실행한다.
- container profile은 항상 `--max-workers=1`, `maxParallelForks=1`, JUnit parallel disabled로 실행한다.
- `highContentionRunId`는 caller input이므로 Bluetape `require*` 반환값으로 검증한다. `check`는 내부 불변식에만 사용한다.
- Spring profile은 `SpringApplication`으로 실제 context를 기동하고 context의 Hikari `DataSource` bean을 사용한다.
- Ticket test fixture의 `PGSimpleDataSource` profile 경로는 Hikari로 교체한다. Exposed repository/transaction boundary를 profile 전용 raw JDBC write로 우회하지 않는다.
- Redis는 보조 조정자다. correctness 판정은 PostgreSQL authority와 durable effect receipt에서 수행한다.
- Toxiproxy는 profile마다 `ToxiproxyServer()`를 직접 소유한다. singleton launcher와 raw `ToxiproxyContainer`를 사용하지 않는다.
- plan 구현 커밋은 task별로 작게 유지한다. 각 커밋은 Lore protocol을 따른다.

## Ecosystem capability selection

| Responsibility | Reused Bluetape ecosystem capability | 적용 방식 | 사용하지 않는 대안 / 제약 |
|---|---|---|---|
| caller validation | `bluetape4k-core` `require*` | run/profile/implementation ID, profile 수치와 path 반환값 검증 | raw `require`/`check`를 caller input에 사용하지 않음 |
| JSON | `bluetape4k-jackson3` | typed DTO, canonical JSON, duplicate/unknown field 거부 | Gson/Ajv/새 serializer dependency 추가 안 함 |
| logging | `bluetape4k-logging` `KLogging` | lifecycle, injection, recovery, cleanup의 low-cardinality event | raw identity/URI/control endpoint logging 금지 |
| test/assertion | `bluetape4k-junit5`, `bluetape4k-assertions` | fixture lifecycle과 invariant assertion | raw JUnit assertion은 대응 helper가 없을 때만 사용 |
| PostgreSQL | `PostgreSQLServer` | authoritative integration topology | H2/mock을 correctness proof로 사용하지 않음 |
| Redis | `RedisServer`, `bluetape4k-lettuce` | advisory state와 actual socket/reconnect path | Redis를 durable authority로 승격하지 않음 |
| Redis path failure | `ToxiproxyServer`, catalog `testcontainers-toxiproxy` | per-profile network, old/new connection fault와 recovery | singleton launcher, raw Testcontainers wrapper, version pin 금지 |
| persistence | 기존 Exposed repository/transaction, Spring Exposed integration, HikariCP | authority query와 state transition은 기존 boundary 재사용 | profile 전용 generic CRUD/raw JDBC mutation 추가 안 함 |
| concurrency | Java 25 virtual threads, `bluetape4k-virtualthread-api` | bounded dispatcher/workload/fault-observer executor | unbounded executor와 `Thread.sleep` 기반 domain transition 금지 |
| metrics | `bluetape4k-micrometer` | Hikari/executor/worker low-cardinality saturation snapshot | raw identity를 metric tag로 노출하지 않음 |
| deterministic control | 기존 `JobConsoleFixtureClock`, `JobConsoleBarrier`, fake payment/ticket providers | lease/provider/restart transition과 barrier | 실제 provider 및 wall-clock domain timeout 금지 |
| shared contract | repository JSON assets + golden vectors | 두 module-local loader가 동일 digest/vector를 검증 | 새 generic test-kit module과 cross-domain SPI 금지 |

## Acceptance criteria mapping

| Acceptance criterion | 구현 task | 검증 증거 |
|---|---|---|
| reproducible curve/duration/topology/control | 1–3 | contract parser, golden vector, effective configuration report |
| queue/admission/inventory/idempotency | 6–9 | PostgreSQL invariant result와 exactly-one attempt evidence |
| Redis unavailable/key loss | 5, 7, 9 | old/new connection path evidence와 namespace-safe key deletion |
| slow payment/worker restart/duplicate delivery | 7–9 | barrier/fencing/reconciliation/effect receipt |
| framework별 독립 결과 | 10–12 | implementation/profile report matrix |
| correctness와 observation 분리 | 3, 10 | closed result vocabulary와 report validation |
| 순차 heavyweight topology | 3A, 11–13 | worker-tree reap, journal의 active topology count와 workflow `--max-workers=1` |
| Java 25와 ecosystem 우선 | 모든 task | toolchain/dependency/import audit |
| README·lesson·CI artifact | 13–14 | bilingual docs, workflow validation, lesson |

### Task 1: Versioned suite/profile/report assets

**Files:**

- Create: `profiles/high-contention/v1/profile-contract.json`
- Create: `profiles/high-contention/v1/report-contract.json`
- Create: `profiles/high-contention/v1/child-descriptor-contract.json`
- Create: `profiles/high-contention/v1/schedule-vectors.json`
- Create: `profiles/high-contention/v1/redis-key-vectors.json`
- Create: `profiles/high-contention/v1/suite-manifest.json`
- Create: `profiles/high-contention/v1/profiles/ci-correctness/burst.json`
- Create: `profiles/high-contention/v1/profiles/ci-correctness/duplicate-storm.json`
- Create: `profiles/high-contention/v1/profiles/ci-correctness/redis-path-outage.json`
- Create: `profiles/high-contention/v1/profiles/ci-correctness/redis-key-loss.json`
- Create: `profiles/high-contention/v1/profiles/ci-correctness/slow-provider.json`
- Create: `profiles/high-contention/v1/profiles/ci-correctness/worker-restart.json`
- Create: `profiles/high-contention/v1/profiles/ci-correctness/duplicate-delivery.json`
- Create: `profiles/high-contention/v1/profiles/local-reference/burst.json`
- Create: `profiles/high-contention/v1/profiles/local-reference/duplicate-storm.json`
- Create: `profiles/high-contention/v1/profiles/local-reference/redis-path-outage.json`
- Create: `profiles/high-contention/v1/profiles/local-reference/redis-key-loss.json`
- Create: `profiles/high-contention/v1/profiles/local-reference/slow-provider.json`
- Create: `profiles/high-contention/v1/profiles/local-reference/worker-restart.json`
- Create: `profiles/high-contention/v1/profiles/local-reference/duplicate-delivery.json`
- Create: `scripts/high-contention/validate-contract.mjs`
- Create: `scripts/high-contention/validate-contract.test.mjs`

- [ ] **Step 1: Write failing repository-asset validation tests**

  Node built-in tests must first assert missing schema versions, duplicate `(mode, profileId, implementation)`,
  unsafe `profileFile`, inconsistent operation totals, invalid cleanup reserves, unknown closed-enum values, and a
  child descriptor that attempts to carry configurable roots/mode/implementation are rejected.

  Run:

  ```bash
  node --test scripts/high-contention/validate-contract.test.mjs
  ```

  Expected: FAIL because assets and validator do not exist.

- [ ] **Step 2: Define v1 closed contracts and bounded profiles**

  Encode the exact field set, limits, result vocabulary, deadline composition, ordered implementation matrix,
  and child descriptor comparison contract
  (`job-core`, `job-spring`, `job-ktor`, `ticket-spring`), and 14 mode/profile documents from the design.
  Local-reference values may be larger, but correctness invariants and failure semantics must be identical.
  The descriptor contains the expected `(runId, profileId, mode, implementation)`, parent manifest digest, and
  complete logical-resource label allocation. It is comparison evidence only: it cannot configure contract root,
  output root, mode, or implementation.
  `redis-key-vectors.json` owns the valid namespace, adjacent prefix, malformed owner key, foreign sentinel, and
  delete-bound cases that both module-local implementations must consume.

- [ ] **Step 3: Add a dependency-free repository validator**

  The validator parses JSON with duplicate-key detection, rejects unknown fields/enums, validates normalized
  descendant paths without following symlinks, recomputes the expected matrix, and verifies SHA-256 digests.
  It must not execute a topology.

- [ ] **Step 4: Prove deterministic assets**

  Run:

  ```bash
  node --test scripts/high-contention/validate-contract.test.mjs
  node scripts/high-contention/validate-contract.mjs profiles/high-contention/v1
  ```

  Expected: PASS with 14 profile documents, 4 implementations, and one ordered matrix.

- [ ] **Step 5: Commit**

  ```bash
  git add profiles/high-contention/v1 scripts/high-contention
  git commit -m "Make high-contention inputs versioned before running topology"
  ```

### Task 2: Job-family contract loader and canonical schedule

**Files:**

- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionModel.kt`
- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionContractLoader.kt`
- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/DeterministicSchedule.kt`
- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionWorkloadEngine.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionContractLoaderTest.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/DeterministicScheduleTest.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionWorkloadEngineTest.kt`

- [ ] **Step 1: Write failing loader and golden-vector tests**

  Cover valid selection, omitted optional filter, empty/unknown filter, duplicate JSON key, unknown field,
  independent suite/profile/report version mismatch, overflow/negative numeric values, symlink/path escape,
  before/after file identity change, and every schedule vector.

  Run:

  ```bash
  ./gradlew :operations-job-console-core:test \
    --tests '*HighContentionContractLoaderTest' \
    --tests '*DeterministicScheduleTest'
  ```

  Expected: FAIL with missing types.

- [ ] **Step 2: Implement typed fail-closed parsing**

  Use Bluetape Jackson3 and `require*` return values. Disable polymorphic typing, reject unknown/duplicate fields,
  bound document size/depth, and validate the trusted real contract root plus `NOFOLLOW_LINKS` components before
  reading a stable file handle.

- [ ] **Step 3: Implement canonical schedules**

  Implement SHA-256 unsigned lexicographic rank, overflow-safe burst/step/retry-storm offsets, weighted authority
  selection, and canonical token serialization. Floating point and platform PRNG are forbidden.

- [ ] **Step 4: Implement and test the bounded open-loop engine**

  Allocate a seed-derived warm-up namespace that cannot overlap any measured tenant/user/idempotency identity.
  Execute exactly `warmupOperationCount` operations before opening the measurement window, exclude them from the
  workload schedule count/digest and all latency/throughput observations, and then invoke the adapter's read-only
  authority/effect/receipt baseline snapshot hook. Reject missing, extra, measured, or namespace-overlapping
  warm-up records.

  Compute the complete ordered measured schedule before dispatch. The scheduler never blocks on a full bounded dispatcher;
  it records `LOCALLY_REJECTED`, while dispatched tokens independently record `MISSED_DEADLINE` when applicable.
  A reserved executor/permit runs the fault observer even under workload saturation. Require every expected stable
  ordinal to have exactly one realized record and every dispatched attempt to have exactly one terminal
  disposition. Recompute expected/realized canonical digests from journal evidence and reject missing, duplicate,
  unknown, or silently dropped ordinals. Assert:

  - `expectedTokenCount == operationCount == scheduledCount`
  - `scheduledCount == dispatchedCount + locallyRejectedCount`
  - `dispatchedCount == completedCount + cancelledCount + timedOutCount`
  - retry attempt totals and per-identity exactly-one/at-most-one winner rules
  - `measuredTokenCount == expectedTokenCount`, with warm-up records absent from both canonical digests

- [ ] **Step 5: Run the focused tests twice**

  ```bash
  ./gradlew :operations-job-console-core:test \
    --tests '*HighContentionContractLoaderTest' \
    --tests '*DeterministicScheduleTest' \
    --tests '*HighContentionWorkloadEngineTest'
  ./gradlew :operations-job-console-core:test \
    --tests '*DeterministicScheduleTest'
  ```

  Expected: both PASS and identical expected schedule digest.

- [ ] **Step 6: Commit**

  ```bash
  git add operations/job-console-core
  git commit -m "Make workload schedules portable across Job adapters"
  ```

### Task 3: Job-family report, journal, redaction, and lifecycle primitives

**Files:**

- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionJournal.kt`
- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionReport.kt`
- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionArtifactStore.kt`
- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionLifecycle.kt`
- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionMeasurements.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionJournalTest.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionReportTest.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionLifecycleTest.kt`

- [ ] **Step 1: Write failing artifact and lifecycle tests**

  Cover JSONL sequence/hash chain, torn final line, corruption before tail, `CREATE_NEW`, no-replace atomic move,
  fsync, report serialization fallback, closed result combinations, submission/attempt conservation, partial
  percentile status, saturation interval accounting, run ID/path/symlink guards, sentinel redaction, acquired-before-
  start ledger, reverse cleanup, double close, timeout clipping, fail-open barriers, interrupt preservation, and
  suppressed cleanup errors. Explicitly test
  `profileExecutionDeadline = absoluteProfileDeadline - cleanupReserve`, independent
  `reportFinalizeReserve`, and phase budget clipping to
  `min(configuredPhaseBudget, remainingProfileExecutionBudget, remainingRunExecutionBudget)`.

- [ ] **Step 2: Implement immutable artifacts**

  Use canonical Bluetape Jackson3 serialization. Keep a single mutable execution accumulator inside the runner,
  then create one immutable terminal DTO. Do not expose a generic CRUD or arbitrary artifact writer surface.

- [ ] **Step 3: Implement bounded lifecycle**

  Register `ALLOCATED` before start, record `STARTING/STARTED/CLOSED/CLOSE_FAILED`, run blocking cleanup actions
  in daemon cleanup threads with independent budgets, and stop the suite on any remaining thread/future/resource.

- [ ] **Step 4: Verify**

  ```bash
  ./gradlew :operations-job-console-core:test \
    --tests '*HighContentionJournalTest' \
    --tests '*HighContentionReportTest' \
    --tests '*HighContentionLifecycleTest'
  ```

  Expected: PASS.

- [ ] **Step 5: Commit**

  ```bash
  git add operations/job-console-core
  git commit -m "Preserve terminal evidence even when profile execution fails"
  ```

### Task 3A: Prove the nested Gradle/test-worker process boundary

**Files:**

- Create: `buildSrc/src/main/kotlin/HighContentionProcessProbeTask.kt`
- Create: `buildSrc/src/test/kotlin/HighContentionProcessProbeTaskTest.kt`
- Modify: `buildSrc/build.gradle.kts`
- Modify: `operations/job-console-core/build.gradle.kts`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionProcessProbeTest.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/HighContentionProcessProbeChild.kt`

- [ ] **Step 1: Write a failing no-container process-boundary test**

  Register a public `highContentionProcessProbe` coordinator task and an internal
  `highContentionProcessProbeChild` `Test` task. The public task uses the same-worktree Gradle wrapper to start only
  the internal task in a separate `--no-daemon` Gradle invocation with an isolated project cache; recursive public
  task invocation is a test failure. The actual test-worker JVM writes its PID before doing other work, starts one
  harmless long-lived descendant, journals that PID, and blocks until the parent deadline.

- [ ] **Step 2: Implement the smallest injectable JDK process boundary**

  Keep `buildSrc` Gradle/JDK-only. Use small injectable process/filesystem ports and JDK `ProcessHandle`; do not add
  Jackson, Testcontainers, Docker client, or version pins. The parent times out, terminates and reaps the recorded
  worker PID and its journaled/discovered descendant tree, then terminates the wrapper invocation. Prove stable-zero
  live processes and no Gradle cache/build lock remains. The internal child task rejects direct invocation without
  the one-shot probe descriptor created by the public task.

- [ ] **Step 3: Verify the spike without Docker**

  ```bash
  ./gradlew -p buildSrc test --tests '*HighContentionProcessProbeTaskTest'
  ./gradlew :operations-job-console-core:highContentionProcessProbe --max-workers=1
  ```

  Expected: PASS with the worker PID and descendant observed, timed out, and completely reaped. No container is
  created.

- [ ] **Step 4: Commit the reusable boundary**

  ```bash
  git add buildSrc operations/job-console-core
  git commit -m "Prove child worker cleanup before allocating topology"
  ```

  Task 11 must refactor and reuse this process boundary; it must not replace the spike with a second launcher.

### Task 4: Per-profile Redis/Toxiproxy topology for Job adapters

**Files:**

- Modify: `operations/job-console-core/build.gradle.kts`
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/JobConsoleContainerFixture.kt`
- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/JobConsoleProxiedTopology.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/JobConsoleProxiedTopologyTest.kt`

- [ ] **Step 1: Add compile-failing Toxiproxy API contract tests**

  Test direct `ToxiproxyServer()` ownership, Redis network alias, `ToxiproxyClient`, `Proxy.disable()/enable()`,
  old-connection toxic removal, new-connection listener down/up, direct Redis bypass guard, partial-start cleanup,
  and idempotent close. Mark the class `@Tag("integration")`; the default `test` task must never start containers.

- [ ] **Step 2: Add only the catalog runtime dependency**

  Add:

  ```kotlin
  testFixturesImplementation(libs.testcontainers.toxiproxy)
  ```

  Keep `bluetape4k-testcontainers`; do not pin a version or import another BOM. Toxiproxy library types remain
  private to the fixture implementation and never appear in its public API.

- [ ] **Step 3: Implement explicit ownership**

  Add a proxied factory without changing `shared()`. The profile fixture owns `Network`, `RedisServer`,
  `ToxiproxyServer`, proxy/toxics, and client resources. Public fixture signatures expose only sanitized Redis URI
  and lifecycle operations, not control endpoint credentials. Before each Docker create, fsync a parent-issued
  logical resource key, resource type, and complete label set to the child journal; attach that exact label set to
  the create request; then fsync the returned container/network ID before readiness. Add crash-cutpoint tests before
  create and between create return and ID fsync.

- [ ] **Step 4: Verify actual data-plane failure and recovery**

  ```bash
  ./gradlew :operations-job-console-core:integrationTest \
    --tests '*JobConsoleProxiedTopologyTest' \
    --max-workers=1
  ```

  Expected: PASS with old pooled connection failure, new connection establishment failure, and two independent
  recovery PINGs.

- [ ] **Step 5: Commit**

  ```bash
  git add operations/job-console-core
  git commit -m "Exercise the Redis socket path instead of a failure stub"
  ```

### Task 5: Namespace-safe Redis key-loss fixture

**Files:**

- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/OwnedRedisNamespace.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/OwnedRedisNamespaceTest.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketOwnedRedisNamespace.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketOwnedRedisNamespaceTest.kt`

- [ ] **Step 1: Write failing owner-parser tests in both domain families**

  Cover the terminal delimiter, adjacent prefix, foreign sentinel, malformed key, delete upper bound, incomplete
  scan convergence, and empty/unknown namespace components. Both test suites load every case from
  `profiles/high-contention/v1/redis-key-vectors.json` and assert the same canonical vector digest; neither suite
  maintains a copied Kotlin case table.

- [ ] **Step 2: Implement module-local bounded `SCAN` + `UNLINK`**

  Pause the writer at a barrier, collect every candidate, validate all candidates before deleting any, enforce the
  profile-derived upper bound, then unlink and verify convergence. Never call `FLUSHALL`/`FLUSHDB`.

- [ ] **Step 3: Prove both adapters reject the same golden negative cases**

  ```bash
  ./gradlew \
    :operations-job-console-core:test \
    :commerce-concert-ticket-flash-sale:test \
    --tests '*OwnedRedisNamespaceTest' \
    --max-workers=1
  ```

  Expected: PASS.

- [ ] **Step 4: Commit**

  ```bash
  git add operations/job-console-core commerce/concert-ticket-flash-sale
  git commit -m "Prevent key-loss evidence from crossing its owned namespace"
  ```

### Task 6: Job Console core high-contention adapter

**Files:**

- Create: `buildSrc/src/main/kotlin/HighContentionProfileTasks.kt`
- Create: `buildSrc/src/test/kotlin/HighContentionProfileTasksTest.kt`
- Modify: `buildSrc/build.gradle.kts`
- Modify: `operations/job-console-core/build.gradle.kts`
- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/JobConsoleHighContentionAdapter.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/JobConsoleCoreHighContentionProfileTest.kt`
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/JobConsoleBarrier.kt`
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/JobConsoleFixtureClock.kt`
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/JobConsoleScenario.kt`

- [ ] **Step 1: Write failing profile tests**

  Add tests for burst tenant FIFO/one-active-job, duplicate-storm idempotency, durable cancel across Redis outage and
  key loss, long work with lease/checkpoint fencing, transaction-free old-worker pause, lease takeover and actual
  stale CAS rejection, and stable event duplicate delivery. Add warm-up omission/overlap/count/digest regressions
  and prove PostgreSQL authority, effect, and receipt assertions use the snapshot immediately after warm-up as
  their baseline rather than absolute table cardinality.

- [ ] **Step 2: Extend existing fixtures narrowly**

  Add deterministic seed-derived identities and timed fail-open barrier operations. Keep helpers in test fixtures;
  do not add production management APIs. Use existing `JobRepository`, `JobWorkerEngine`, outbox and projection
  boundaries.

- [ ] **Step 3: Enforce the stale-worker proof**

  The old worker pauses outside a transaction and holds no DB/advisory lock. After clock advance and takeover, the
  old token attempts its real repository commit and receives the existing lease-lost/fenced outcome.

- [ ] **Step 4: Register and run the core profile tasks**

  First TDD a buildSrc registration helper that owns the complete dedicated-task contract: fixed repository
  contract input, root-project output root, internal/final system-property injection, caller spoof rejection,
  allowlisted selection, Java 25 launcher/runtime assertion, `high-contention` tag only, JUnit parallel disabled,
  `maxParallelForks=1`, and `test-mutex`. Register `highContentionCiProfile` and
  `highContentionLocalReferenceProfile` through that helper. Update `integrationTest` to exclude the tag so the
  opt-in profile never joins the ordinary integration suite.

  ```bash
  ./gradlew -p buildSrc test --tests '*HighContentionProfileTasksTest'
  ./gradlew :operations-job-console-core:highContentionCiProfile \
    -PhighContentionRunId=plan-job-core-1 \
    -PhighContentionProfileId=worker-restart \
    --max-workers=1
  ```

  Expected: PASS and one validated child report.

- [ ] **Step 5: Commit**

  ```bash
  git add buildSrc operations/job-console-core
  git commit -m "Prove Job authority survives hostile worker timing"
  ```

### Task 7: Spring Job Console live adapter and bounded context restart

**Files:**

- Create: `operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringJobConsoleHighContentionProfileTest.kt`
- Create: `operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringJobConsoleHighContentionSelectionTest.kt`
- Create: `operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringJobConsoleProfileApplication.kt`
- Modify: `operations/job-console-spring/build.gradle.kts`

- [ ] **Step 1: Write a failing live-context profile test**

  Start the real application with `SpringApplication`, random port, shared PostgreSQL schema, proxied Redis URI,
  and profile properties. Assert the effective Redis client endpoint is the proxy and retrieve the actual Hikari
  `DataSource` bean from the context.

  Register the Spring module's two dedicated profile tasks and exclude `high-contention` from ordinary
  `integrationTest` before executing the new class. Reuse `HighContentionProfileTasks`; do not resolve or inject
  roots/properties ad hoc.

- [ ] **Step 2: Add HTTP burst/duplicate/outage evidence**

  Drive the public REST/SSE boundary with bounded virtual threads. Query authority through existing repositories/
  services and read-only verification queries, not profile-specific write SQL. Immediately after the isolated
  warm-up, capture the Job fixture's PostgreSQL authority/effect/receipt baseline; every final assertion and report
  uses the measured-run delta. Assert warm-up identities never appear in measured counts, digests, percentiles, or
  throughput.

  The selection contract maps all seven declared Job profiles to a Spring live action and authoritative assertion:

  | Profile | Spring live action | Authoritative assertion |
  |---|---|---|
  | `burst` | concurrent HTTP submit/snapshot/SSE | tenant FIFO, one active job, queue version convergence |
  | `duplicate-storm` | repeated HTTP POST with stable idempotency identity | one job/sequence/terminal history |
  | `redis-path-outage` | cancel across old/new proxied Redis connection faults | PostgreSQL cancel/history convergence |
  | `redis-key-loss` | delete only run-owned signal keys, then cancel/snapshot | durable cancel/history retained |
  | `slow-provider` | barrier-controlled long work and lease advance | checkpoint/lease fencing preserved |
  | `worker-restart` | context and worker lifecycle restart | takeover commit plus old-token stale rejection |
  | `duplicate-delivery` | replay the same stable outbox event ID/digest | one durable effect per event |

  A non-topology selection test fails if the suite declares a Job profile without an exact mapping.

- [ ] **Step 3: Implement true lifecycle restart**

  Pause the old attempt in a profile-owned stale-attempt executor at a transaction-free pre-commit barrier and
  record `transaction/connection/lock=0`, old token, and pending commit input. Close the context-owned worker
  executor and Spring context with bounded termination while the separate stale-attempt executor remains paused.
  Retain PostgreSQL authority, start a new context/worker, commit takeover, verify HTTP snapshot convergence, then
  release the old attempt to perform the real short-transaction CAS and observe authoritative stale rejection.
  Assert zero late effects/receipts, then close/join the stale-attempt executor. No static shared application context
  may cross profile runs.

- [ ] **Step 4: Verify**

  ```bash
  ./gradlew :operations-job-console-spring:highContentionCiProfile \
    -PhighContentionRunId=plan-job-spring-1 \
    -PhighContentionProfileId=redis-path-outage \
    --max-workers=1
  ```

  Expected: PASS with Hikari bean evidence, old/new Redis connection failure evidence, and recovery.

- [ ] **Step 5: Commit**

  ```bash
  git add operations/job-console-spring
  git commit -m "Make Spring recovery evidence cross the real application boundary"
  ```

### Task 8: Ktor Job Console live adapter and bounded server restart

**Files:**

- Create: `operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/KtorJobConsoleHighContentionProfileTest.kt`
- Create: `operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/KtorJobConsoleHighContentionSelectionTest.kt`
- Create: `operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/KtorJobConsoleProfileServer.kt`
- Modify: `operations/job-console-ktor/build.gradle.kts`

- [ ] **Step 1: Write a failing Ktor profile test**

  Create a fixture-owned Hikari datasource, real Netty server, public HTTP driver, worker executor, and proxied
  Redis client. Assert datasource/pool limits and endpoint routing before workload.

  Register the Ktor module's two dedicated profile tasks and exclude `high-contention` from ordinary
  `integrationTest` before executing the new class. Reuse `HighContentionProfileTasks`; do not resolve or inject
  roots/properties ad hoc.

- [ ] **Step 2: Reuse the Job core contract without exposing production APIs**

  Feed the same schedule/profile/report vocabulary into the Ktor adapter. Domain assertions remain in existing
  repositories; Ktor-specific work is server lifecycle and HTTP mapping only. Mirror the Spring seven-row profile
  mapping with Ktor HTTP/SSE actions, and add a non-topology selection test that fails on any unmapped manifest
  profile. Reuse the same isolated warm-up and post-warm-up PostgreSQL authority/effect/receipt snapshot hook;
  evaluate all final correctness and observations from the measured-run baseline delta.

- [ ] **Step 3: Prove server and worker restart independently**

  Pause the old attempt in a profile-owned executor outside transaction/connection/lock ownership, then stop/restart
  the Netty server and context-owned worker executor against the same PostgreSQL schema. The new worker commits the
  takeover before the old attempt is released to the real fenced CAS. Assert authoritative stale rejection and zero
  late effects/receipts, then bounded-close the stale executor and verify no port, connection, thread, or
  application resource remains.

- [ ] **Step 4: Verify**

  ```bash
  ./gradlew :operations-job-console-ktor:highContentionCiProfile \
    -PhighContentionRunId=plan-job-ktor-1 \
    -PhighContentionProfileId=worker-restart \
    --max-workers=1
  ```

  Expected: PASS with one server active at a time and zero leaked owned resources.

- [ ] **Step 5: Commit**

  ```bash
  git add operations/job-console-ktor
  git commit -m "Make Ktor restart evidence retain the durable Job authority"
  ```

### Task 9: Ticket module-local contract, Hikari topology, and hostile profiles

**Files:**

- Modify: `commerce/concert-ticket-flash-sale/build.gradle.kts`
- Modify: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/persistence/TicketDatabaseFixture.kt`
- Modify: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/purchase/PurchaseServiceIntegrationTest.kt`
- Delete: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/TicketStressProfileTest.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionModel.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionContractLoader.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionWorkloadEngine.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionArtifacts.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketProxiedTopology.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionProfileApplication.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionProfileTest.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionContractTest.kt`

- [ ] **Step 1: Lock the current Ticket behavior**

  Run the existing purchase, payment, ticket effect, Redis outage, context restart, and hostile concurrency tests
  before editing. Add a regression assertion that `TicketDatabaseFixture.dataSource` is Hikari-backed.

  ```bash
  ./gradlew :commerce-concert-ticket-flash-sale:test \
    --tests '*PurchaseServiceIntegrationTest' \
    --tests '*PaymentReconciliationIntegrationTest' \
    --tests '*TicketEffectIntegrationTest' \
    --tests '*RedisUnavailableIntegrationTest' \
    --tests '*TicketContextRestartIntegrationTest' \
    --tests '*TicketHostileConcurrencyIntegrationTest' \
    --max-workers=1
  ```

  Register the Ticket module's two dedicated `high-contention` tasks and change the default `test` task to exclude
  `high-contention` before introducing the replacement profile class. Reuse `HighContentionProfileTasks`; do not
  resolve or inject roots/properties ad hoc.

- [ ] **Step 2: Replace the test datasource with Hikari**

  Build `HikariDataSource` from `PostgreSQLServer`, set the schema and bounded pool size, close it before dropping
  the schema, and keep existing Exposed `TicketJdbcExecutor` transaction boundaries. Do not introduce
  `Database.connect()` per test or direct connection factories.

- [ ] **Step 3: Implement the module-local contract adapter**

  Parse the repository assets independently with Bluetape Jackson3 and validate every `schedule-vectors.json`
  case. Match the Job-family digest/report contract without adding a dependency from Ticket to Job. The Ticket
  tests must independently cover duplicate/unknown fields, bounded stable-handle reads, repository-root
  containment, every path component with `NOFOLLOW_LINKS`, symlink/TOCTOU rejection, output `CREATE_NEW`,
  no-replace atomic move, post-create containment, and raw identity/credential/URI sentinel redaction. Implement
  the same bounded open-loop scheduler/dispatcher/fault-observer contract locally and pass every golden vector plus
  missing/duplicate/unknown ordinal, exactly-one disposition, conservation, expected/realized digest, closed result,
  JSONL corruption/torn-tail, cleanup reserve, and terminal artifact test. This is behavioral parity through data
  vectors, not a Job module dependency.

- [ ] **Step 4: Implement Ticket Redis/Toxiproxy ownership**

  Add `testImplementation(libs.testcontainers.toxiproxy)`, use `RedisServer`/`ToxiproxyServer`, verify proxy-only
  effective URI, old/new connection failure, recovery, namespace-safe key loss, and reverse-order cleanup. Mirror
  the Job child-journal protocol: fsync parent-issued exact labels and create intent before Docker create, attach
  labels to every container/network, and fsync returned IDs before readiness. Cover both create crash cut points.

- [ ] **Step 5: Replace the one-off stress test with all seven profiles**

  Cover admission ordering/inventory/identity guards, duplicate HTTP attempt, fail-closed Redis outage, DB guard
  after Redis key loss, slow provider timeout/reconciliation/late-response fencing, stable operation worker
  restart, and stable event duplicate effect/receipt. Use `PurchaseService`, `PaymentWorker`, `TicketEffectWorker`,
  fake providers, and existing repositories. Start the real Ticket `SpringApplication` for each live profile,
  obtain the Hikari `DataSource` and application services from the context, and restart that context against the
  same PostgreSQL authority for the restart profile. No production test hook or raw JDBC state transition is
  allowed. For restart, retain the old token and pending input in a profile-owned, transaction-free paused
  executor; commit takeover in the new context; release the old attempt into the real fenced apply path; assert
  stale disposition, zero late effect/receipt, and exactly one takeover terminal winner before closing the old
  executor. Independently mirror the Job warm-up contract: use a seed-derived non-overlapping Ticket namespace,
  execute exactly the configured count outside measurement/digests, snapshot PostgreSQL authority/effect/receipt
  immediately afterward, and judge the measured run only from baseline deltas. Add regressions for omitted,
  overlapping, or measurement-contaminating warm-up.

- [ ] **Step 6: Verify contract and one hostile profile**

  ```bash
  ./gradlew :commerce-concert-ticket-flash-sale:test \
    --tests '*TicketHighContentionContractTest' \
    --max-workers=1
  ./gradlew :commerce-concert-ticket-flash-sale:highContentionCiProfile \
    -PhighContentionRunId=plan-ticket-1 \
    -PhighContentionProfileId=slow-provider \
    --max-workers=1
  ```

  Expected: PASS; late provider attempt is `IGNORED_FENCED` and adds no effect/receipt.

- [ ] **Step 7: Commit**

  ```bash
  git add commerce/concert-ticket-flash-sale
  git commit -m "Make Ticket contention evidence use its real durable boundaries"
  ```

### Task 10: Module-local profile tasks and compatibility cleanup

**Files:**

- Modify: `operations/job-console-core/build.gradle.kts`
- Modify: `operations/job-console-spring/build.gradle.kts`
- Modify: `operations/job-console-ktor/build.gradle.kts`
- Modify: `commerce/concert-ticket-flash-sale/build.gradle.kts`

- [ ] **Step 1: Verify the eight registered task entry points**

  ```bash
  test "$(./gradlew tasks --all --console=plain | rg -c 'highContention(Ci|LocalReference)Profile')" -eq 8
  ```

  Expected: PASS with exactly two tasks for each of four implementations.

- [ ] **Step 2: Audit exactly two tasks per module**

  Audit and normalize the already registered `highContentionCiProfile` and
  `highContentionLocalReferenceProfile`. Both use the test source output, include only the module profile class,
  declare `profiles/high-contention/v1` as an input directory, derive mode from task name, require run ID, accept
  only manifest-allowlisted optional profile, fix implementation ID from module path, set `maxParallelForks=1`,
  disable JUnit parallel, and use `test-mutex`. All four profile classes use only the `high-contention` tag;
  ordinary `test` and Job `integrationTest` tasks explicitly exclude it. Direct and root-invoked tasks both write
  under `rootProject.layout.buildDirectory.dir("reports/high-contention/<run-id>")`; module-local build directories
  are not artifact roots. Task code derives the fixed contract root, output root, mode, and implementation from
  repository layout, task name, module path, and validated run ID; it injects those final values only into its own
  test-worker JVM. The caller may supply only run ID and the manifest-allowlisted optional profile. Caller attempts
  to spoof the corresponding `high.contention.*` system properties or any internal parent/descriptor channel are
  rejected before topology start. Every profile `Test` task sets its `javaLauncher` from a Java 25 toolchain and
  verifies the child runtime feature version before any profile file or topology is opened.

  A direct module task requires an absent run directory and creates its own single-child manifest. A root-invoked
  child accepts an existing run directory only through a coordinator-issued, `CREATE_NEW` child descriptor passed
  on a reserved internal environment channel. It derives its own fixed roots/mode/implementation first, then
  verifies the descriptor digest, exact expected tuple, parent manifest digest, complete label allocation, and an
  absent child journal/report target before appending. Descriptor values are never used as configuration. Caller
  `-D`/`-P`/environment attempts to select parent mode, descriptor path, label set, or internal task state are
  rejected.

  Direct/root local-reference tasks fail preflight when `git status --porcelain` is non-empty. Workflow-owned CI
  mode uses a coordinator-internal flag derived from the workflow environment—not an arbitrary caller property—and
  also requires clean source. Add clean/dirty TestKit cases and prove rejection occurs before run-directory
  creation.

- [ ] **Step 3: Remove the legacy Ticket stress entry point**

  Remove `ticketStressTest`, `ticketStressRun`, and `ticket.stress.run`. Preserve the same oversell invariant under
  `highContentionLocalReferenceProfile`/`burst`.

- [ ] **Step 4: Prove validation and no overwrite**

  ```bash
  ./gradlew :operations-job-console-core:highContentionCiProfile --max-workers=1
  ./gradlew :operations-job-console-core:highContentionCiProfile \
    -PhighContentionRunId='../../unsafe' --max-workers=1
  ./gradlew :operations-job-console-core:highContentionCiProfile \
    -PhighContentionRunId=task-contract-1 \
    -PhighContentionProfileId=unknown --max-workers=1
  ./gradlew :operations-job-console-core:highContentionCiProfile \
    -PhighContentionRunId=task-contract-1 \
    -Dhigh.contention.output.root=/tmp/spoofed --max-workers=1
  ```

  Expected: all four FAIL before topology start with sanitized validation messages.

  Then run:

  ```bash
  ./gradlew :operations-job-console-core:highContentionCiProfile \
    -PhighContentionRunId=task-contract-2 \
    -PhighContentionProfileId=burst --max-workers=1
  ```

  Expected: PASS once; a second identical run ID fails without replacing files.

- [ ] **Step 5: Commit**

  ```bash
  git add operations/job-console-core operations/job-console-spring operations/job-console-ktor \
    commerce/concert-ticket-flash-sale
  git commit -m "Expose one safe profile entry point per implementation"
  ```

### Task 11: Root sequential coordinator and artifact validator

**Files:**

- Create: `buildSrc/src/main/kotlin/HighContentionSuiteTask.kt`
- Create: `buildSrc/src/main/kotlin/HighContentionArtifactValidator.kt`
- Create: `buildSrc/src/test/kotlin/HighContentionSuiteTaskTest.kt`
- Create: `buildSrc/src/test/kotlin/HighContentionArtifactValidatorTest.kt`
- Create: `scripts/high-contention/validate-run.mjs`
- Create: `scripts/high-contention/validate-run.test.mjs`
- Modify: `buildSrc/build.gradle.kts`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Write failing build-logic tests**

  Use Gradle TestKit fixtures for ordered selection, zero selection, missing child report, ordinary FAIL collection,
  ERROR continuation only after zero-live cleanup, active topology overlap, child timeout, constants-only failure
  upload, exact label mismatch, duplicate label collision, delayed Docker create after initial zero, quiet-period
  convergence, parent cleanup reserve exhaustion, unsafe/empty run ID, existing run directory, symlinked parent,
  pre-open/post-open path identity change, no-replace atomic move, post-create root containment, missing/wrong Java
  25 launcher, orphaned test-worker descendant, no-start when a full `profileDeadline` does not remain, run/profile
  phase clipping, and independent run cleanup/journal-finalize reserves.

- [ ] **Step 2: Implement root tasks**

  Register `highContentionCi` and `highContentionLocalReference`. They require `highContentionRunId`, apply optional
  exact filters, create the expected matrix/run journal before children, and launch each module-local task in a
  separate `--no-daemon` Gradle process with an isolated project cache sequentially. The parent passes only the
  validated run ID, optional allowlisted profile ID, and a no-replace coordinator-issued child descriptor over the
  reserved internal environment channel; it never transports contract/output/mode/implementation settings. The
  descriptor records the expected tuple and complete label allocation for comparison. The profile runner
  verifies that descriptor and fsyncs its actual test-worker JVM PID before topology start;
  the coordinator reuses the Task 3A launcher and owns that PID, its journaled/discovered descendant tree, the wrapper
  invocation, timeout, parent journal and compensating cleanup. On timeout it terminates and reaps the recorded
  worker tree before the wrapper, then proves stable-zero live child processes before considering another profile.
  Before the first write it validates the run ID allowlist, trusted real root, every parent with `NOFOLLOW_LINKS`,
  and an absent target directory. It opens temporary/terminal files with `CREATE_NEW`, fsyncs, performs a
  no-replace atomic move, and revalidates real-root containment after creation. The root output is always
  `rootProject.layout.buildDirectory.dir("reports/high-contention/<run-id>")`; caller system properties cannot
  override contract/output/mode/implementation.

  At run start compute
  `runExecutionDeadline = absoluteRunDeadline - runCleanupReserve`. Before each child, require the complete
  `profileDeadline` to remain in the run execution budget. The child computes
  `profileExecutionDeadline = absoluteProfileDeadline - cleanupReserve`; all non-cleanup phases use the minimum of
  configured, remaining profile-execution, and remaining run-execution budgets. Profile cleanup/report finalize
  and parent cleanup/run-journal finalize each consume only their separately reserved budgets.

- [ ] **Step 3: Implement exact-label parent cleanup**

  Child create intent is fsynced before Docker create and returned ID is fsynced before readiness. On timeout/crash,
  delete only when ID and the complete `(runId, profileId, resourceKey, resourceType)` label set match. For an
  in-flight create with no ID, poll the exact label set until stable zero for the configured quiet period; on
  collision or mismatch delete nothing and fail closed.

- [ ] **Step 4: Implement summary/upload validation**

  Implement `HighContentionArtifactValidator` as a plain helper, not a separately registered Gradle task.
  `HighContentionSuiteTask` invokes it from `finally` on success, ordinary failure, timeout, and cleanup failure, so
  constants-only fallback cannot be skipped. Implement `validate-run.mjs` and its Node built-in tests in this task;
  the script owns typed run-journal/report parsing, duplicate/unknown-field rejection, canonical digest and
  redaction checks, and atomic terminal summary/upload-manifest writing. For validation/redaction failures reached
  inside the script, it may also write the constants-only fallback. Validate every
  expected child journal/report, schedule realization digest, closed result, redaction, evidence allowlist,
  cleanup zero-live state, and
  `maxActiveTopologies == 1`. Write `summary.json` and `upload-manifest.json` once. If validation/redaction cannot
  complete, write only constants-only `upload-failure-summary.json` under a separate upload directory.

  Keep `buildSrc` Gradle/JDK-only: no Jackson, Testcontainers, Docker client, or dependency version pins. Reuse the
  dependency-free repository Node validator for JSON artifact checks. `HighContentionArtifactValidator` invokes it
  with a bounded JDK process, fixed script path, fixed argument vector, captured/sanitized result, and no shell.
  If Node is unavailable, process creation fails, the process times out/crashes/exits nonzero, its result is
  malformed, or expected terminal files fail verification, the Kotlin helper writes an emergency fallback from
  fixed constant bytes only using `CREATE_NEW`, fsync, and no-replace atomic move. It never copies exception text,
  paths, process output, or run data into that file. BuildSrc tests cover launch failure, timeout, crash, malformed
  result, and missing/invalid terminal output and prove exactly one constants-only fallback remains.
  Exact-label bounded discovery and deletion are reached only through a small injectable Docker CLI port owned by
  the root coordinator.

  Add a table-driven coordinator policy test: `PASS` continues; `FAIL` continues only after safe zero-live cleanup;
  `ERROR` continues only when its error class permits it and cleanup is zero-live; `UNAVAILABLE` is preserved,
  skips no expected report, and yields final nonzero; missing/invalid artifact, cleanup leak, child-process leak,
  or parent cleanup error stops immediately and forbids the next child.

- [ ] **Step 5: Verify task discovery and one filtered run**

  ```bash
  ./gradlew -p buildSrc test
  node --test scripts/high-contention/validate-run.test.mjs
  ./gradlew tasks --all | rg '^highContention(Ci|LocalReference)'
  ./gradlew highContentionCi \
    -PhighContentionRunId=root-contract-1 \
    -PhighContentionProfileId=duplicate-storm \
    -PhighContentionImplementation=job-core \
    --max-workers=1
  ```

  Expected: build logic and run validator PASS, two root tasks found, filtered run PASS with one child and one
  summary.

- [ ] **Step 6: Commit**

  ```bash
  git add buildSrc build.gradle.kts scripts/high-contention
  git commit -m "Keep heavyweight profiles sequential and recoverable from child failure"
  ```

### Task 12: Full correctness matrix and local-reference observation proof

**Files:**

- Modify as failures require only within:
  `operations/job-console-core/**`,
  `operations/job-console-spring/**`,
  `operations/job-console-ktor/**`,
  `commerce/concert-ticket-flash-sale/**`,
  `profiles/high-contention/v1/**`,
  `buildSrc/**`

- [ ] **Step 1: Run each implementation/profile CI matrix through the root coordinator**

  Commit any Task 11 repair first so the source is clean. `local-reference` and workflow-owned CI refuse a dirty
  source; local `ci-correctness` may record `sourceDirty=true` only for the focused development loop.

  ```bash
  ./gradlew highContentionCi \
    -PhighContentionRunId=issue-522-ci-local \
    --max-workers=1
  ```

  Expected: all expected reports exist, correctness PASS, cleanup PASS, and summary shows
  `maxActiveTopologies=1`.

- [ ] **Step 2: Inspect machine evidence**

  ```bash
  node scripts/high-contention/validate-contract.mjs profiles/high-contention/v1
  find build/reports/high-contention/issue-522-ci-local -type f -maxdepth 6 -print | sort
  ```

  Expected: only the canonical artifact tree; no raw logs, credentials, URIs, or unlisted evidence.

- [ ] **Step 3: Fix only evidence-backed defects and commit the repair**

  For every failure, preserve its report/journal, write a regression test first, then rerun the filtered root task.
  Do not weaken profile thresholds to make a topology pass. If files changed, commit the repair before the
  local-reference run:

  ```bash
  git add operations commerce profiles buildSrc build.gradle.kts
  git commit -m "Close gaps exposed by the complete contention matrix"
  ```

  If no repair was needed, verify the worktree is already clean instead of creating an empty commit.

- [ ] **Step 4: Run one bounded local-reference combination from a clean source**

  Confirm `git status --short` is empty before this command.

  ```bash
  ./gradlew highContentionLocalReference \
    -PhighContentionRunId=issue-522-local-reference \
    -PhighContentionProfileId=burst \
    -PhighContentionImplementation=ticket-spring \
    --max-workers=1
  ```

  Expected: correctness PASS; observed throughput/latency and environment fields present; no ranking/capacity claim.

- [ ] **Step 5: Preserve the clean exact-head evidence**

  Record the source commit and report digests in the task notes. Generated `build/reports` artifacts remain
  untracked.

### Task 13: CI/nightly integration and upload handshake

**Files:**

- Modify: `.github/workflows/Examples.yml`
- Modify: `.github/workflows/nightly.yml`
- Modify: `scripts/smoke-validate.sh`
- Create: `scripts/high-contention/select-upload.mjs`
- Create: `scripts/high-contention/select-upload.test.mjs`

- [ ] **Step 1: Write failing upload-selection tests**

  Cover valid canonical tree, absent upload manifest, digest mismatch, redaction failure, symlink, unknown file,
  `UNAVAILABLE` preservation, constants-only fallback, `workflowRunAndAttempt` mismatch, and exact
  `high-contention-<mode>-<run-id>` artifact naming/retention.

- [ ] **Step 2: Add a dedicated Examples correctness job**

  Use Java 25, Docker, `highContentionCi`, run ID
  `examples-${{ github.run_id }}-${{ github.run_attempt }}`, `--max-workers=1`, and Ryuk-disabled parent cleanup
  proof. An `if: always()` step validates and stages only the upload-manifest allowlist. Upload with
  exact name `high-contention-ci-correctness-examples-${{ github.run_id }}-${{ github.run_attempt }}`,
  `if-no-files-found: error`, and 7-day retention. The selector must reject any manifest/report whose
  `workflowRunAndAttempt` differs from `${{ github.run_id }}-${{ github.run_attempt }}`. Add the job to
  `examples-status`.

- [ ] **Step 3: Add a dedicated nightly local-reference job**

  Run on weekly/full scope with Java 25 and run ID
  `nightly-${{ github.run_id }}-${{ github.run_attempt }}`. Upload validated artifacts for 14 days and add the job
  to `nightly-status`. The exact artifact name is
  `high-contention-local-reference-nightly-${{ github.run_id }}-${{ github.run_attempt }}` and the same
  `workflowRunAndAttempt` equality is mandatory.

- [ ] **Step 4: Add local validation routes**

  Add `high-contention-contract` and `high-contention-ci` groups to `scripts/smoke-validate.sh`; keep them out of
  `all-smoke`.

- [ ] **Step 5: Verify workflow syntax and anchored changes**

  ```bash
  node --test scripts/high-contention/select-upload.test.mjs
  ./scripts/smoke-validate.sh high-contention-contract
  actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml
  ./gradlew tasks --all | rg '^highContention(Ci|LocalReference)'
  ```

  Expected: PASS.

- [ ] **Step 6: Commit**

  ```bash
  git add .github/workflows/Examples.yml .github/workflows/nightly.yml \
    scripts/smoke-validate.sh scripts/high-contention
  git commit -m "Preserve validated contention evidence in CI and nightly runs"
  ```

### Task 14: Bilingual runbooks and lesson

**Files:**

- Modify: `operations/job-console-core/README.md`
- Modify: `operations/job-console-core/README.ko.md`
- Modify: `operations/job-console-spring/README.md`
- Modify: `operations/job-console-spring/README.ko.md`
- Modify: `operations/job-console-ktor/README.md`
- Modify: `operations/job-console-ktor/README.ko.md`
- Modify: `commerce/concert-ticket-flash-sale/README.md`
- Modify: `commerce/concert-ticket-flash-sale/README.ko.md`
- Create: `docs/lessons/2026-07-24-issue-522-high-contention-profiles.md`
- Create: `scripts/validate-high-contention-readme.mjs`

- [ ] **Step 1: Write the README validator first**

  Require both root commands, Docker/JDK 25/memory prerequisites, report path, correctness/observation distinction,
  Toxiproxy scope, PostgreSQL authority, no framework ranking, and no production capacity claim in both languages.

- [ ] **Step 2: Update module runbooks**

  Public English READMEs describe commands and operational boundaries. Korean companions mirror the same facts.
  Do not add benchmark rankings or charts.

- [ ] **Step 3: Record the lesson in Korean**

  Explain why actual old/new Redis connection paths, Hikari/Spring bean usage, PostgreSQL fencing, immutable
  evidence, cleanup reserves, and module-local adapters were selected. Include rejected generic module and
  singleton Toxiproxy alternatives.

- [ ] **Step 4: Verify docs**

  ```bash
  node scripts/validate-high-contention-readme.mjs
  node scripts/validate-readme-language.mjs
  ./scripts/smoke-validate.sh stale-check
  ```

  Expected: PASS.

- [ ] **Step 5: Commit**

  ```bash
  git add operations/job-console-core/README* operations/job-console-spring/README* \
    operations/job-console-ktor/README* commerce/concert-ticket-flash-sale/README* \
    docs/lessons/2026-07-24-issue-522-high-contention-profiles.md \
    scripts/validate-high-contention-readme.mjs
  git commit -m "Make contention evidence interpretable without capacity claims"
  ```

### Task 15: Final verification, independent review, and PR preparation

**Files:**

- Modify only files required by evidence-backed P0/P1 findings.

- [ ] **Step 1: Run targeted unit and integration tests**

  ```bash
  ./gradlew \
    :operations-job-console-core:test \
    :operations-job-console-core:integrationTest \
    :operations-job-console-spring:test \
    :operations-job-console-spring:integrationTest \
    :operations-job-console-ktor:test \
    :operations-job-console-ktor:integrationTest \
    :commerce-concert-ticket-flash-sale:test \
    --max-workers=1
  ```

- [ ] **Step 2: Run the canonical CI correctness task**

  ```bash
  ./gradlew highContentionCi \
    -PhighContentionRunId=issue-522-final-local \
    --max-workers=1
  ```

- [ ] **Step 3: Run static/document/workflow checks**

  ```bash
  ./gradlew detekt
  node --test scripts/high-contention/*.test.mjs
  node scripts/high-contention/validate-contract.mjs profiles/high-contention/v1
  node scripts/validate-high-contention-readme.mjs
  actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml
  ./scripts/smoke-validate.sh stale-check
  git diff --check
  ```

- [ ] **Step 4: Audit ecosystem and dependency policy**

  ```bash
  rg -n 'platform\\(libs\\.bluetape4k\\..*bom|io\\.github\\.bluetape4k:.*:[0-9]' \
    operations/job-console-* commerce/concert-ticket-flash-sale gradle/libs.versions.toml
  rg -n 'ToxiproxyContainer|ToxiproxyServer\\.Launcher|FLUSHALL|flushAll|Thread\\.sleep' \
    operations/job-console-* commerce/concert-ticket-flash-sale \
    --glob '**/highcontention/**'
  rg -n 'PGSimpleDataSource|Database\\.connect' \
    operations/job-console-* commerce/concert-ticket-flash-sale \
    --glob '**/highcontention/**'
  rg -n 'jackson|testcontainers|docker-java|com\\.github\\.docker|:[0-9]+\\.[0-9]+' \
    buildSrc/build.gradle.kts buildSrc/src
  ```

  Expected: no individual Bluetape BOM/version pin, raw Toxiproxy/singleton/flush/sleep, or profile-path
  `PGSimpleDataSource`/per-test `Database.connect()` usage. `buildSrc` remains Gradle/JDK-only with no JSON,
  Testcontainers, Docker-client, or pinned-version dependency/import.

- [ ] **Step 5: Run six independent code-review perspectives**

  Review caller safety, developer/API compatibility, operations/lifecycle, correctness/concurrency, test quality,
  and simplification/over-engineering. Every finding must include severity and exact file/line evidence. Resolve all
  P0/P1 findings and rerun the smallest proving command.

- [ ] **Step 6: Commit final review fixes and verify clean exact head**

  ```bash
  git status --short
  git log -1 --oneline
  git diff origin/develop...HEAD --check
  ```

  Expected: clean worktree, reviewed commits only, no generated run artifact tracked.

- [ ] **Step 7: Create the English PR and wait for CI**

  Push `feature/issue-522-high-contention-profiles`, create an English PR to `develop` with issue link, architecture,
  ecosystem capability selection, commands, artifact contract, risk/rollback, and a final `## DoD Status` section.
  Do not merge. Report the exact head only after live CI, review state, and unresolved thread count are checked.

## Rollback and rerun points

- Contract/parser failure: revert only Task 1–2 commits; no topology or production code is affected.
- Lifecycle/artifact failure: preserve the failed run tree, fix Task 3/11, and rerun one filtered combination with a
  new run ID.
- Redis/Toxiproxy failure: close clients first, run exact-label cleanup, confirm stable zero, then rerun only
  `redis-path-outage`.
- Domain invariant failure: add a regression test beside the affected Job/Ticket adapter; do not change report
  aggregation or profile threshold first.
- Workflow/upload failure: use a local staged artifact tree and `select-upload.test.mjs`; never upload an
  unvalidated raw run directory.
- Any cleanup leak, label collision, redaction failure, or immutable artifact mismatch is a hard stop. Do not start
  another topology or prepare a PR until the failure is resolved and reverified.

# Issue #524 Planning Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Java 25 Spring Boot planning-contracts workshop module that uses Bluetape virtual threads, Exposed repositories, PostgreSQL inbox/outbox consistency, deterministic provider fixtures, and redacted planning read models.

**Architecture:** A provider-neutral `PlanningEngine` is implemented by a deterministic default adapter and two disabled HTTP adapters. Spring services own transaction boundaries around Bluetape Exposed repositories; a PostgreSQL outbox drives submission, a unique callback inbox makes delivery idempotent, and the final command revalidates aggregate version before returning a candidate.

**Tech Stack:** Kotlin 2.3 language level, Java 25 toolchain for `optimization/*`, Spring Boot 4.1 MVC, HikariCP, JetBrains Exposed JDBC resolved through `bluetape4k-dependencies:1.3.1`, `bluetape4k-exposed-jdbc`, `bluetape4k-exposed-jdbc-tests`, `bluetape4k-testcontainers`, `bluetape4k-http`, Jackson 3, Micrometer, WireMock, JUnit 5, and Bluetape virtual-thread API/JDK 25 runtime.

---

## Source truth

- Spec: `docs/superpowers/specs/2026-07-18-issue-524-planning-contracts-design.md`
- Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/524
- Branch: `feature/issue-524-planning-contracts`
- Worktree: `.worktrees/issue-524-planning-contracts`

## Ecosystem capability selection

| Responsibility | Reused module/capability | Why used or not used | Constraint |
|---|---|---|---|
| Versions | `bluetape4k-dependencies:1.3.1` | Sole Bluetape authority | No individual BOM/version pin |
| Persistence | `bluetape4k-exposed-core`, `bluetape4k-exposed-jdbc` | Repository inheritance and auditable tables | Custom SQL only for atomic state transitions |
| Persistence tests | `bluetape4k-exposed-jdbc-tests` | Reuse released JDBC test helpers | Exclude any conflicting starter/provider transitives if required |
| Virtual threads | `bluetape4k-virtualthread-api`, runtime `bluetape4k-virtualthread-jdk25` | Spring-owned Java 25 execution | Exclude JDK 21 provider |
| HTTP | `bluetape4k-http` | Production VT client | Disable/control retry for submit POST |
| JSON | `bluetape4k-jackson3`, `bluetape4k-exposed-jackson3` | Closed fixture/payload mapping | No default typing |
| IDs | `bluetape4k-idgenerators` | UUID v7 request ids | Provider event ids remain external |
| Logging/metrics | `bluetape4k-logging`, `bluetape4k-micrometer` | Context and observations | Redacted low-cardinality tags only |
| Infrastructure tests | `bluetape4k-testcontainers` | PostgreSQL and WireMock launchers | Default CI has no external network |
| Redis/Kafka/leader/JaVers | None | Not required by the first contract | Lettuce only if Redis becomes proven scope |

## File structure

Create module files under
`optimization/planning-contracts/src/main/kotlin/io/bluetape4k/workshop/optimization/planning/`:

- `PlanningContractsApplication.kt`: application entrypoint.
- `config/PlanningProperties.kt`: bounded provider, worker, callback, and retry configuration.
- `config/PlanningConfiguration.kt`: database, executor, clock, engine, and lifecycle beans.
- `domain/PlanningModels.kt`: status, request/result, callback, audit, and command values.
- `domain/PlanningEngine.kt`: provider-neutral submission/status port.
- `persistence/PlanningTables.kt`: aggregate, request, outbox, inbox, and audit tables.
- `persistence/PlanningRecords.kt`: serializable repository projections.
- `persistence/PlanningRequestRepository.kt`: UUID auditable repository plus accepted-revision update.
- `persistence/PlanningOutboxRepository.kt`: Long auditable repository plus claim/retry/dead-letter methods.
- `persistence/PlanningCallbackInboxRepository.kt`: Long auditable repository plus insert-if-absent.
- `persistence/PlanningAuditRepository.kt`: append-only Long repository.
- `persistence/PlanningAggregateRepository.kt`: aggregate-version fixture and revalidation.
- `adapter/fake/DeterministicPlanningEngine.kt`: default fixture adapter.
- `adapter/http/HttpPlanningEngine.kt`: shared normalized HTTP adapter implementation.
- `adapter/http/TimefoldPlatformPlanningEngine.kt`: Timefold endpoint mapping.
- `adapter/http/CustomSolverPlanningEngine.kt`: custom Solver endpoint mapping.
- `adapter/http/CallbackSignatureVerifier.kt`: profile-specific callback verification.
- `application/PlanningRequestService.kt`: request plus outbox transaction.
- `application/PlanningOutboxWorker.kt`: VT claim/submit/retry processing.
- `application/PlanningCallbackService.kt`: inbox-first callback transaction.
- `application/PlanningCommandService.kt`: final aggregate-version validation.
- `application/PlanningQueryService.kt`: redacted projection.
- `observability/PlanningObservations.kt`: Micrometer observations and counters.
- `web/PlanningDtos.kt`: validated request/callback and closed response DTOs.
- `web/PlanningController.kt`: REST endpoints.
- `web/PlanningExceptionHandler.kt`: sanitized errors.

Create resources and tests:

- `optimization/planning-contracts/build.gradle.kts`
- `optimization/planning-contracts/src/main/resources/application.yml`
- `optimization/planning-contracts/src/main/resources/fixtures/fake-planning-result.json`
- `optimization/planning-contracts/src/test/resources/junit-platform.properties`
- `optimization/planning-contracts/src/test/resources/logback-test.xml`
- `optimization/planning-contracts/src/test/resources/fixtures/timefold-submit-response.json`
- `optimization/planning-contracts/src/test/resources/fixtures/custom-solver-submit-response.json`
- tests under the matching package for contracts, repositories, worker,
  callbacks, HTTP adapters, MVC endpoints, lifecycle, and restart replay.
- `optimization/planning-contracts/README.md`
- `optimization/planning-contracts/README.ko.md`
- `optimization/README.md`
- `optimization/README.ko.md`

Modify registration surfaces:

- `build.gradle.kts`
- `settings.gradle.kts`
- `AGENTS.md`
- `README.md`
- `README.ko.md`
- `.github/workflows/ci.yml`
- `.github/workflows/Examples.yml`
- `.github/workflows/nightly.yml`
- `scripts/smoke-validate.sh`

## Task 1: Register the Java 25 optimization module

**Complexity:** medium
**Dependencies:** none
**Pattern skills:** `bluetape-kotlin-patterns` module setup

- [ ] Create `optimization/planning-contracts/build.gradle.kts` with Spring
  Boot MVC, validation, actuator, Hikari/PostgreSQL, approved Bluetape
  dependencies, and versionless aliases only.
- [ ] Add `includeModules("optimization", false, true)` to
  `settings.gradle.kts`; verify the project name is
  `:optimization-planning-contracts`.
- [ ] Change root toolchain selection to compute `targetJavaVersion = 25` only
  when `projectDir` is below root `optimization/`; keep 21 elsewhere for Java
  and Kotlin toolchains.
- [ ] Exclude `io.github.bluetape4k:bluetape4k-virtualthread-jdk21` from the
  optimization module configurations and add the JDK 25 provider as
  `runtimeOnly`.
- [ ] Add required test resources and minimal application resource file.
- [ ] Run `./gradlew projects --console=plain`.
  Expected: `Project ':optimization-planning-contracts'` is present.
- [ ] Run `./gradlew :optimization-planning-contracts:javaToolchains --console=plain` and
  `:optimization-planning-contracts:compileKotlin`.
  Expected: Java 25 launcher is selected and compilation succeeds.

**Rollback/rerun:** Revert only toolchain/registration edits if Java 21 modules
resolve to target 25. Re-run one existing Java 21 module compile plus the new
module compile before proceeding.

## Task 2: Define domain contracts with TDD

**Complexity:** medium
**Dependencies:** Task 1
**Pattern skills:** TDD, Kotlin patterns

- [ ] Write `PlanningEngineContractTest` first for deterministic submission,
  normalized provider status, stable request identity, and bounded redacted
  explanation values.
- [ ] Run the focused test and observe an expected compile failure because
  `PlanningEngine` and the contract values do not exist.
- [ ] Implement the minimum serializable domain values and `PlanningEngine`
  port. Use named value objects for repeated string/long parameters and define
  `serialVersionUID` for every data class.
- [ ] Implement `DeterministicPlanningEngine` from the recorded fixture.
- [ ] Run the focused test until green; then refactor names without adding
  persistence or HTTP behavior.
- [ ] Add failure/edge tests for unsupported provider status, negative revision,
  oversized explanation, and unknown request id.

Run:

```bash
./gradlew :optimization-planning-contracts:test \
  --tests '*PlanningEngineContractTest*' --console=plain
```

Expected: RED before production types, then all contract cases PASS.

## Task 3: Implement Bluetape Exposed repositories with PostgreSQL proof

**Complexity:** high
**Dependencies:** Task 2
**Pattern skills:** TDD, `ecc-kotlin-exposed`, Kotlin testing

- [ ] Write PostgreSQL repository tests first using
  `PostgreSQLServer.Launcher.postgres`; do not instantiate raw containers.
- [ ] Assert inherited request CRUD/paging/count behavior and append-only audit
  insertion before adding custom methods.
- [ ] Add failing tests for unique `(provider,event_id)` insert-if-absent,
  exclusive outbox lease claim, retry/dead-letter transition, newest-revision
  update, and aggregate-version comparison.
- [ ] Observe the expected RED failures from missing tables/repositories.
- [ ] Implement tables and record mappings using
  `UUIDAuditableJdbcRepository`, `LongAuditableJdbcRepository`, and
  `LongJdbcRepository` released APIs.
- [ ] Implement only the atomic custom SQL proved by failing tests. Import
  top-level Exposed operators and extract locals where receiver shadowing could
  occur.
- [ ] Use `bluetape4k-exposed-jdbc-tests` fixtures/helpers where their released
  API matches; record any incompatibility instead of copying a stale API.
- [ ] Add `MultithreadingTester` cases for concurrent duplicate inbox insert
  and lease claim exclusivity.

Run sequentially:

```bash
./gradlew :optimization-planning-contracts:cleanTest \
  --tests '*RepositoryTest*' --tests '*RepositoryConcurrencyTest*' \
  --no-build-cache --max-workers=1 --console=plain
```

Expected: RED for each new behavior before implementation, then one inbox row,
one lease owner, and all repository cases PASS.

**Rollback/rerun:** Drop/recreate only the module's isolated test schema. Never
remove shared Testcontainers state as a first response to a failure.

## Task 4: Implement request/outbox transaction and virtual-thread worker

**Complexity:** high
**Dependencies:** Task 3
**Pattern skills:** TDD, Spring Boot Kotlin, performance/stability scan

- [ ] Write a failing integration test proving request and outbox are committed
  together and both roll back when outbox insert fails.
- [ ] Write a failing worker test proving one due row is claimed, submitted on
  a Bluetape JDK 25 virtual thread, and completed in a task-owned transaction.
- [ ] Add failure tests for timeout, 5xx, claim expiry, bounded retry,
  dead-letter, shutdown, and restart recovery.
- [ ] Verify RED before adding services or executor configuration.
- [ ] Implement `PlanningRequestService` with one Spring transaction and UUID
  v7 request id.
- [ ] Implement Spring-owned executor beans using
  `VirtualThreads.executorService()` and verify the runtime provider name is the
  JDK 25 implementation.
- [ ] Implement bounded claim and worker execution. Do not submit JDBC work to
  another executor from inside the caller's transaction.
- [ ] Wrap submit/claim outcomes with low-cardinality logging/observation
  context; never log payloads.

Run:

```bash
./gradlew :optimization-planning-contracts:test \
  --tests '*PlanningRequestServiceTest*' \
  --tests '*PlanningOutboxWorkerTest*' \
  --max-workers=1 --console=plain
```

Expected: atomic creation, Java 25 virtual-thread execution, bounded retries,
recoverable leases, and clean shutdown PASS.

## Task 5: Implement callback idempotency and final-command revalidation

**Complexity:** high
**Dependencies:** Task 4
**Pattern skills:** TDD, Exposed, Spring Boot Kotlin

- [ ] Write failing callback tests for accepted result, duplicate event,
  out-of-order revision, changed aggregate version, invalid signature, and
  concurrent duplicate delivery.
- [ ] Assert invalid signatures create no inbox/audit row and duplicate events
  create no second accepted audit row.
- [ ] Write a failing final-command test that changes the aggregate version
  after callback acceptance and expects a conflict result.
- [ ] Implement profile-specific `CallbackSignatureVerifier`; HTTP profiles use
  JCE `Mac` plus constant-time compare, while the fake verifier is explicit.
- [ ] Implement inbox-first `PlanningCallbackService` in one transaction and
  append an explicit decision audit for new non-duplicate callbacks.
- [ ] Implement `PlanningCommandService` to re-read PostgreSQL aggregate state
  immediately before returning the command candidate.
- [ ] Run the tests with PostgreSQL serially until green.

Run:

```bash
./gradlew :optimization-planning-contracts:test \
  --tests '*PlanningCallbackServiceTest*' \
  --tests '*PlanningCommandServiceTest*' \
  --max-workers=1 --console=plain
```

Expected: duplicate convergence, stale rejection, signature rejection, and
aggregate-version conflict PASS.

## Task 6: Implement offline HTTP adapter fixtures

**Complexity:** high
**Dependencies:** Task 2, Task 4
**Pattern skills:** TDD, Kotlin HTTP testing rules

- [ ] Write WireMock contract tests first for Timefold Platform and custom
  Solver submit/status mapping, timeout, 5xx, malformed JSON, EOF/body close,
  request tags, and redaction.
- [ ] Add an ambiguity test proving submit `POST` is not automatically retried
  after an unknown outcome; status reconciliation or explicit provider
  idempotency is required before replay.
- [ ] Verify expected RED because HTTP adapters do not exist.
- [ ] Implement the shared normalized HTTP adapter with
  `productionVirtualThreadHttpClientOf`, explicit request timeout, closed
  response bodies, bounded payloads, and provider-specific endpoint mappers.
- [ ] Register Timefold and custom Solver adapters only under non-default
  profiles. Default tests select the deterministic fake and need no API key.

Run:

```bash
./gradlew :optimization-planning-contracts:test \
  --tests '*HttpPlanningEngineContractTest*' --console=plain
```

Expected: all local WireMock cases PASS with no external network access and no
ambiguous submit retry.

## Task 7: Add Spring MVC and redacted read models

**Complexity:** medium
**Dependencies:** Tasks 4-6
**Pattern skills:** TDD, Backend Implementation, Spring Boot Kotlin

- [ ] Write MockMvc/Spring integration tests first for create, process demo,
  callback, query, and final-command endpoints.
- [ ] Add validation tests for blank ids, negative versions/revisions, oversized
  bodies, unknown request ids, invalid signatures, and command conflict.
- [ ] Add leak assertions ensuring JSON does not contain payload, signature,
  secret, API key, stack trace, JDBC URL, raw provider body, or unredacted
  explanation fields.
- [ ] Implement validated DTOs, controller, query mapping, and sanitized
  exception handling.
- [ ] Use constructor injection and keep business logic in services.

Run:

```bash
./gradlew :optimization-planning-contracts:test \
  --tests '*PlanningControllerTest*' --max-workers=1 --console=plain
```

Expected: endpoint success/failure mapping and redaction assertions PASS.

## Task 8: Prove restart convergence end to end

**Complexity:** high
**Dependencies:** Tasks 3-7
**Pattern skills:** TDD, Kotlin testing

- [ ] Write an integration test that commits a request/outbox row, reconstructs
  the worker/service boundary, processes the same provider callback more than
  once, and asserts one accepted audit history.
- [ ] Write an out-of-order variant and an aggregate-version-change variant.
- [ ] Observe RED if any lifecycle or idempotency boundary is incomplete.
- [ ] Make only the minimum recovery fix and rerun all repository, worker, and
  callback tests from Tasks 3-5.

Run:

```bash
./gradlew :optimization-planning-contracts:cleanTest \
  --tests '*PlanningRestartConvergenceTest*' \
  --no-build-cache --max-workers=1 --console=plain
```

Expected: restart plus duplicate delivery converges to exactly one accepted
audit, with stale/changed versions rejected.

## Task 9: Document the module and register validation surfaces

**Complexity:** medium
**Dependencies:** Tasks 1-8
**Pattern skills:** `bluetape-writer`, Kotlin module setup

- [ ] Create source-equivalent module-group and module `README.md` /
  `README.ko.md` pairs with language switches, architecture, flow, dependency
  governance, configuration, API examples, failure modes, and focused commands.
- [ ] Update root README locale tables and `AGENTS.md` module map with
  `optimization/` and the Java 25 exception.
- [ ] Add `optimization/planning-contracts/**` path triggers and a Java 25
  container-backed test step/artifact to `Examples.yml`.
- [ ] Update `ci.yml` and `nightly.yml` so the runner JDK can provision both
  Java 21 and Java 25 toolchains; keep the module out of Docker-free smoke and
  include it in full container-backed validation.
- [ ] Add an `optimization` group to `scripts/smoke-validate.sh` that runs the
  module serially with `--max-workers=1`.
- [ ] Verify no publication/BOM entry is added because this is a consumer
  workshop module.

Run:

```bash
./gradlew projects --console=plain
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh optimization
actionlint .github/workflows/ci.yml .github/workflows/Examples.yml .github/workflows/nightly.yml
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs
```

Expected: registration, workflow syntax, bilingual README checks, and the
container-backed optimization group PASS.

## Task 10: Final verification and review

**Complexity:** high
**Dependencies:** Tasks 1-9
**Pattern skills:** verification-before-completion, full-feature review

- [ ] Run the module's full tests serially and then compile one representative
  existing Java 21 module to prove the mixed toolchain boundary.
- [ ] Run detekt and `git diff --check`.
- [ ] Run performance/stability scan over HTTP, DB, executor, lease, callback,
  and lifecycle files.
- [ ] Review the final diff through performance, stability, security, Ops,
  developer/API, and user/caller lenses; fix and re-run every P0/P1.
- [ ] Verify every spec acceptance row against a named passing test.
- [ ] Create `docs/review/2026-07-18-issue-524-planning-contracts-review.md`
  and `docs/lessons/2026-07-18-issue-524-planning-contracts.md` with evidence.

Run sequentially:

```bash
./gradlew :optimization-planning-contracts:cleanTest \
  --no-build-cache --max-workers=1 --console=plain
./gradlew :exposed-mvc-virtualthread:compileKotlin \
  :optimization-planning-contracts:compileKotlin --console=plain
./gradlew detekt --console=plain
git diff --check
```

Expected: all commands PASS, latest review P0=0/P1=0, no unresolved warnings or
deprecations in touched code.

## Spec traceability

| Spec/issue requirement | Plan task and proof |
|---|---|
| Java 25 for optimization examples | Task 1 mixed-toolchain compile; Task 10 regression compile |
| Bluetape VT API + JDK 25 provider | Tasks 1 and 4 runtime provider test |
| BOM 1.3.1 controls Exposed | Tasks 1 and 9 dependency insight/governance check |
| Active Exposed repository pattern | Task 3 inherited CRUD and atomic SQL tests |
| PostgreSQLServer launcher | Tasks 3, 5, and 8 serial integration tests |
| Request plus outbox atomicity | Task 4 transaction rollback test |
| Duplicate/out-of-order callback behavior | Task 5 concurrency/revision tests |
| Restart convergence | Task 8 end-to-end recovery test |
| Final aggregate-version check | Task 5 command conflict test |
| Deterministic offline fake | Task 2 contract tests |
| Separate provider adapters | Task 6 WireMock contract tests |
| Redacted read models | Task 7 leak assertions |
| Ecosystem capability inventory | This plan table and Task 9 dependency audit |
| Module/CI/Nightly/docs registration | Task 9 validation commands |

## Risk prediction

| Risk | Signal | Mitigation | Rollback/rerun point |
|---|---|---|---|
| JDK 21 provider wins ServiceLoader | runtime name/provider assertion fails | exclude JDK 21 module and keep JDK 25 runtimeOnly | Task 1 compile/runtime test |
| Submit duplicated by nested retries | WireMock sees more than one POST after ambiguous timeout | disable automatic POST retry; reconcile status first | Task 6 adapter contract |
| Two workers claim one row | concurrency test returns duplicate lease owner | atomic claim predicate and lease token | Task 3 repository test |
| Callback audit duplicated | accepted audit count exceeds one | unique inbox insert-if-absent in same transaction | Task 5/8 convergence tests |
| Aggregate changes after acceptance | final command still returned | mandatory fresh PostgreSQL version compare | Task 5 command test |
| Secret/raw payload leak | JSON/log assertion finds forbidden text | closed response DTO and sanitized summaries | Task 7 negative tests |
| Container lifecycle flake | retry-only test pass or shared schema collision | serial execution and isolated schema reset | rerun Task 3 from cleanTest |
| Executor leaks on shutdown | lifecycle test does not terminate or leases stay claimed | Spring bean destroy plus recoverable TTL | Task 4 lifecycle test |

## Plan review convergence

| Lens | Initial plan finding | Plan repair | Latest blockers |
|---|---|---|---|
| Performance | No explicit bounded batch/body/timeout proof | Tasks 4, 6, and 7 name bounded cases | P0=0, P1=0 |
| Stability | Restart, shutdown, and ambiguous submit replay needed ordered tests | Tasks 4, 6, and 8 provide lifecycle order | P0=0, P1=0 |
| Security | HMAC negative path and output leak checks were not task-owned | Tasks 5 and 7 own both | P0=0, P1=0 |
| Operator/Ops | Mixed toolchain CI and lease recovery needed explicit registration | Tasks 9 and 10 own workflow and recovery proof | P0=0, P1=0 |
| Developer/API | Repository usage could be bypassed by custom DAO methods | Task 3 proves inherited CRUD before custom SQL | P0=0, P1=0 |
| User/caller | Documentation could omit that a plan is non-final | Task 9 requires command revalidation and misuse guidance | P0=0, P1=0 |

Integration review confirms every spec criterion maps to an ordered task, no
task depends on a later artifact, Testcontainers work is serial, Exposed 1.2+
imports are guarded, public KDoc/README locale work is assigned, and rollback
points exist for toolchain, schema, HTTP, and lifecycle risks. No P0/P1 remains.

## Stop conditions

- Stop implementation and repair the plan if a released Bluetape API differs
  from the inspected dependency bytecode/source.
- Stop provider submission if automatic retry cannot be disabled without
  replacing the client configuration.
- Keep live Timefold deployment evidence pending when credentials/public route
  are unavailable; do not simulate it as production success.
- Stop before PR creation because the current approved scope names local issue
  implementation but does not explicitly authorize a PR/base/head creation
  action.
- After #524 is locally verified, preserve sequential issue order
  #532 → #533 → #534. #1055 and #391 continue in parallel as contract/fixture
  tracks and do not block that sequence.

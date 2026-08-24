# Issue #526 Staff Coverage 및 Shift Swap 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `optimization/shift-coverage`에 synthetic multi-site worker/shift coverage와 사람이 확인하는 shift swap을 구현하고, deterministic planner, PostgreSQL revision/CAS, callback/inbox/outbox 수렴, Java 25 virtual-thread lifecycle, redacted demo console을 검증한다.

**Architecture:** 새 Spring Boot MVC consumer module이 worker·shift·assignment·pin·swap·plan 상태와 감사/Inbox/Outbox를 소유한다. planner는 immutable canonical snapshot만 읽어 proposal을 반환하고, approval/swap acceptance만 PostgreSQL 고정 lock 순서와 expected-revision CAS를 통해 assignment를 변경한다. 실제 Timefold Platform은 dependency나 credential로 추가하지 않고 normalized adapter port 뒤의 deterministic fake/recorded fixture로 고정한다.

**Tech Stack:** Kotlin 2.4, Java 25, Spring Boot 4.0.6 MVC, JetBrains Exposed 1.4 계열, PostgreSQL/Testcontainers, Jackson 3, Micrometer/Actuator, `bluetape4k-dependencies` BOM, Bluetape core/idgenerators/logging/exposed-jdbc/http/virtualthread/JUnit5/assertions/testcontainers.

## 구현 상태 (2026-08-24 현재)

아래 상태는 이 계획을 실행한 뒤의 fresh evidence를 반영한 것이다. 원래의 Task별
체크박스는 실행 순서와 RED → GREEN 추적을 보존하고, 이 표가 현재 DoD 판정을
보완한다.

| Task | 상태 | 근거 및 남은 범위 |
|---:|---|---|
| 0 | PASS | preflight review가 P0/P1/P2/P3 모두 0으로 통과했다. |
| 1 | PASS | Java 25/virtual-thread module skeleton, demo/postgres profile, shutdown 경계를 구현하고 테스트했다. |
| 2 | PASS | worker/shift/assignment/swap domain ID·model·hard-rule reason과 deterministic planner를 구현했다. |
| 3 | PASS | canonical v1, NFC/UTC/Jackson3 escaping, digest/HMAC target context와 callback preflight를 구현했다. |
| 4 | PASS | normalized planning port와 credential-free deterministic fake/fixture를 구현했다. |
| 5 | PASS | Exposed authority tables, UUID aggregate repository, CAS/lock tuple, PostgreSQL lock/statement timeout Testcontainers를 검증했다. |
| 6 | PASS | approval/swap CAS, principal-scoped idempotency, inbox monotonic/retry terminal, generation/event stale, outbox unknown/reconcile/redrive 경계를 구현했다. |
| 7 | PASS | bounded executor, readiness/close, demo replan/approve/swap/read model을 구현했다. |
| 8 | PARTIAL | MVC/controller/callback/operator/static console, strict callback envelope, DST boundary, redaction/loopback/Origin, bounded Micrometer tags와 핵심 MockMvc error cases를 구현했다. full CORS/error matrix, restart generation sweep, long-run metrics cardinality 검증은 남아 있다. |
| 9 | PASS | README/README.ko, optimization index, workflow, smoke/stale-check, lesson을 등록했다. |
| 10 | PARTIAL / PENDING | 46 tests, build, PostgreSQL, demo actuator/HTTP boot, smoke, actionlint, shell syntax, diff check가 통과했다. Gradle `detekt` task가 등록되지 않았고, 위 Task 8의 full CORS/restart/long-run gaps가 남아 있어 전체 DoD는 아직 `PENDING`이다. |

**현재 판정:** `optimization-shift-coverage`의 구현 단위는 **PENDING**이다. #527 →
#528 → #529는 이 계획의 Stop condition을 충족하고 fresh approval을 얻은 뒤에만
시작한다.

---

## 0. 구현 전 필수 계약과 재사용 원장

구현을 시작하기 전에 다음 skill과 저장소 기준을 다시 읽는다.

- `$bluetape-workflow` 실행 receipt의 Type A gate와 #526 lane을 유지한다.
- `$bluetape-kotlin-patterns` 및 `references/testing.md`, `references/spring-boot.md`, `references/module-setup.md`, `references/checklist.md`의 KT-01..KT-05를 각 Task에 적용한다.
- `$test-driven-development`를 읽고 모든 구현 Task를 RED → 최소 GREEN → 회귀 순서로 수행한다.
- `$ecc-kotlin-exposed`는 Exposed table/repository/transaction 경계를 작성하기 전에 읽고, `$ecc-springboot-kotlin`은 configuration/controller/lifecycle을 작성하기 전에 읽는다.

### Bluetape 재사용 매트릭스

| 책임 | 직접 재사용할 API/모듈 | 계획상 적용 | 사용하지 않을 때의 근거 |
|---|---|---|---|
| 버전 권위 | root `bluetape4k-dependencies` BOM | module에는 Bluetape 버전을 쓰지 않고 root catalog alias만 사용 | 개별 BOM/버전 pin은 consumer 규칙 위반 |
| 호출자 검증 | `requireNotBlank`, `requireNotNull`, `requirePositiveNumber` | public command/header/value-class 생성자에서 `IllegalArgumentException` 유지 | helper에 없는 printable-ASCII/UTF-8 byte/depth만 raw predicate로 구현하고 이유 기록 |
| 식별자 | `Uuid.V7.nextId()` | UUID PK, request/audit/generation ID에 사용 | provider 문자열 경계만 `Base58.randomString(22)` 사용 |
| opaque token | `Base58.randomString(22)` | effect key/fake correlation을 생성하고 저장·응답·log에서 redacted | canonical cursor는 Base64URL 계약 때문에 표준 encoding 유지 |
| Exposed CRUD/audit | `AuditableUUIDTable`, `UUIDAuditableJdbcRepository`, `auditedUpdateAll` | plan/generation/audit aggregate CRUD/audit timestamp 재사용; 기존 `PlanningRequestRepository`를 정확한 UUID 기준으로 삼음 | multi-column lock/CAS는 업무 계약이므로 좁은 top-level DML 구현 |
| Exposed DML | current top-level `eq`, `and`, `forUpdate`, `selectAll`, `update` | 고정 lock order와 `affectedRows == 1` 검증 | deprecated `SqlExpressionBuilder.eq`, receiver shadowing, production non-null assertion 금지 |
| JDBC/HTTP execution | `VirtualThreads.executorService()`, `productionVirtualThreadHttpClientOf` | blocking I/O만 4-slot/queue-8 admission 뒤에서 실행 | 기본 fake는 network를 호출하지 않고 provider profile은 fail-closed |
| 운영 로그 | `KLogging()`/필요 시 `KLoggingChannel()` | operational class마다 bounded event와 lazy key/value | pure DTO/table/enum에는 logger를 추가하지 않음 |
| assertions | Bluetape `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeFalse`, `assertFailsWith` | 신규 테스트의 값·예외 assertion에 사용 | exact helper가 없는 deep JSON만 JUnit을 사용하고 이유 기록 |
| 동시성 테스트 | `MultithreadingTester`, 필요 시 `StructuredTaskScopeTester`/`SuspendedJobTester` | swap/CAS/queue/cancellation 검증 | sleep/raw Thread/Executors stress loop 금지 |
| PostgreSQL fixture | `PostgreSQLServer.Launcher.postgres` + root `build.gradle.kts`의 Gradle `test-mutex` BuildService + module `junit-platform.properties` + Gradle `--max-workers=1` | resolved `bluetape4k-testcontainers:1.11.0`/`bluetape4k-junit5:1.11.0` artifact에는 helper가 없지만 workspace root가 모든 표준 `test` task를 `usesService(testMutex)`로 직렬화 | H2 대체·skipped container PASS·module 전용 mutex helper 금지 |

모든 raw fallback은 이 원장과 구현 파일 KDoc에 이유를 남긴다. 새로운 공통 core나
#524 구현 dependency는 추가하지 않는다.

## 변경 파일 지도

### 새 모듈과 설정

- Create: `optimization/shift-coverage/build.gradle.kts` — planning-contracts와 같은 Java 25/Spring MVC/Exposed/Jackson 3/Testcontainers 계열; `project(":optimization-planning-contracts")` 없음.
- Create: `optimization/shift-coverage/README.md`, `README.ko.md`, `src/main/resources/application.yml`.
- Create: `.../shiftcoverage/ShiftCoverageApplication.kt`.
- Create: `.../shiftcoverage/config/ShiftCoverageConfiguration.kt`, `ShiftCoverageProperties.kt`, `ShiftCoverageExecutorShutdown.kt`.

### domain / canonical planner

- Create: `.../domain/ShiftCoverageIds.kt` — `WorkerId`, `SiteId`, `ShiftId`, `AssignmentId`, `PlanId`, `SwapRequestId`, `EventId`, `GenerationId`, `Digest`와 `Uuid.V7`/`Base58` factory.
- Create: `.../domain/ShiftCoverageModels.kt`, `ShiftCoverageEvents.kt`, `ShiftCoverageErrors.kt`, `ShiftCoverageLimits.kt`.
- Create: `.../planner/ShiftCoverageSnapshot.kt`, `ShiftCoverageCanonicalizer.kt`, `DeterministicShiftCoveragePlanner.kt`, `ShiftCoverageReason.kt`.

### adapter / persistence / application

- Create: `.../adapter/ShiftCoveragePlanningPort.kt`, `adapter/fake/DeterministicShiftCoverageAdapter.kt`, `adapter/http/ShiftCoverageCallbackEnvelope.kt`, `ShiftCoverageSignatureVerifier.kt`, `ShiftCoverageHttpAdapter.kt`.
- Create: `.../persistence/ShiftCoverageTables.kt`, `ShiftCoverageRecords.kt`, `ShiftCoverageAggregateRepository.kt`, `ShiftCoverageRepository.kt`, `ShiftCoverageInboxRepository.kt`, `ShiftCoverageIdempotencyRepository.kt`, `ShiftCoverageOutboxRepository.kt`, `ShiftCoverageTransactionSupport.kt`, `ShiftCoverageDatabaseInitializer.kt`.
- Create: `.../application/ShiftCoveragePlanService.kt`, `ShiftCoverageApprovalService.kt`, `ShiftSwapService.kt`, `ShiftCoverageEventService.kt`, `ShiftCoverageOutboxWorker.kt`, `ShiftCoverageExecutorLifecycle.kt`, `ShiftCoverageQueryService.kt`.

### web / browser / registration

- Create: `.../web/ShiftCoverageDtos.kt`, `ShiftCoverageController.kt`, `ShiftCoverageCallbackController.kt`, `ShiftCoverageExceptionHandler.kt`, `ShiftCoverageRequestFilter.kt`, `ShiftCoverageWebConfig.kt`.
- Create: `src/main/resources/static/shift-coverage/index.html`, `shift-coverage.js`.
- Modify: `optimization/README.md`, `optimization/README.ko.md`, `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`.
- Create: `docs/lessons/2026-08-24-issue-526-shift-coverage.md` after fresh verification.
- Do not modify `settings.gradle.kts`: `includeModules("optimization", false, true)` auto-registers the directory; verify with `./gradlew projects`.

### 테스트

Create matching package tests for IDs/models, canonicalizer/signature, planner/complexity,
repository/CAS, inbox/idempotency, approval/swap, outbox/restart, lifecycle, controller/
redaction/browser, runtime and benchmark probe. Tests use the same package path as production
and `bluetape4k` assertions; PostgreSQL tests are serialized.

## Task 0: 구현 전 plan preflight review gate를 통과시킨다

**Files:** `docs/superpowers/reviews/2026-08-24-issue-526-shift-coverage-plan-review.md`.

- [ ] **Step 1: 계획 self-review를 먼저 실행한다.** 설계 acceptance 1–12와 계획
  Task 1–10을 교차 대조하고, UUID ID 타입·lock tuple·retry terminal state·operator
  명령 계약·RED/GREEN 명령의 이름과 경로가 일치하는지 확인한다. 미완성 표식과
  모호한 구현 지시를 제거한다.
- [ ] **Step 2: 독립 관점 review를 작성한다.** Performance, Stability, Security,
  Operator/Ops, Developer/API, User/caller와 main integration을 각각 fresh-read하고
  P0/P1은 0이어야 한다. P2/P3는 정확한 Task·테스트 명령 또는 범위 밖 근거를 갖는다.
- [ ] **Step 3: preflight PASS를 확인한다.** 위 review artifact가 PASS로 갱신되기
  전에는 Task 1 구현을 시작하지 않는다. review 뒤 구현 중 계약이 바뀌면 Task 10에서
  delta review를 다시 수행한다.

## Task 1: 모듈 골격과 Bluetape runtime contract를 RED/GREEN으로 고정

**Files:** build.gradle, application/config/properties/shutdown, runtime and shutdown tests.

- [ ] **Step 1: 실패 테스트를 먼저 작성한다.** `ShiftCoverageRuntimeContractTest`는
  `Runtime.version().feature() == 25`, `VirtualThreads.runtimeName() == "jdk25"`,
  `VirtualThreads.executorService()` 작업이 virtual thread임을 검증한다.
  `ShiftCoverageExecutorShutdownTest`는 close가 admission을 닫고 idempotent하며
  interrupt 시 interrupt flag를 복원하는지 검증한다.
- [ ] **Step 2: module build를 작성한다.** planning-contracts/field-service의
  versionless alias 세트를 사용한다: core/logging/http/jackson3/idgenerators/micrometer/
  virtualthread API + JDK25 runtime, Exposed core/jdbc/Jackson3/JDBC tests, Spring MVC/JDBC/
  Actuator, PostgreSQL, Bluetape JUnit5/assertions/testcontainers, WireMock. JDK21 provider는
  configuration에서 제외하고 `springBoot.mainClass`는 `ShiftCoverageApplicationKt`로 둔다.
- [ ] **Step 3: `application.yml`과 configuration을 작성한다.** default `demo`, loopback
  `127.0.0.1`, body 256 KiB, page 100, mutation 8, planner fixed 4/queue 8, outbox
  4/batch 10/lease 30s/I/O 5s, actuator `health,info,prometheus`만 노출한다. Clock은
  UTC이고 executor destroy는 explicit shutdown bean이 소유한다.
- [ ] **Step 4: RED → GREEN을 실행한다.**

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*ShiftCoverageRuntimeContractTest' \
  --tests '*ShiftCoverageExecutorShutdownTest' \
  --max-workers=1 --console=plain
```

Expected: first run fails because the new project/classes are absent; after the minimal
골격 is added the same command passes.

## Task 2: IDs, limits, validation과 model serialization을 구현

**Files:** `domain/ShiftCoverageIds.kt`, `ShiftCoverageLimits.kt`, `ShiftCoverageModels.kt`, `ShiftCoverageErrors.kt`, matching tests.

- [ ] **Step 1: 경계 RED를 작성한다.** blank ID, 200-byte 초과 ID/header, display/reason
  240 code point 초과, 21개 skill/availability/reason, envelope count 초과, negative/NaN
  score를 각각 stable `IllegalArgumentException`/no-write error로 고정한다.
- [ ] **Step 2: ecosystem ID factory를 구현한다.** `Uuid.V7.nextId()`를 UUID PK/request/
  audit/generation ID의 기본값으로 사용하고 provider-facing opaque `effectKey`는
  `Base58.randomString(22)`로 생성한다. public data/value class는 `Serializable`,
  explicit `serialVersionUID`, Korean KDoc을 갖는다.
- [ ] **Step 3: caller/internal validation을 분리한다.** public input은 Bluetape
  `requireNotBlank`/`requireNotNull`/`requirePositiveNumber`, printable ASCII/UTF-8 byte/
  closed enum만 raw predicate를 사용한다. production `!!`는 금지하고 internal invariant는
  `check`/`checkNotNull`로 표현한다.
- [ ] **Step 4: signed minor-unit model을 고정한다.** cost/fairness/coverage는 signed
  `Long`이고 `Math.addExact`/`subtractExact` overflow를 stable error로 변환한다.
- [ ] **Step 5: GREEN을 실행한다.**

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*ShiftCoverageModelsTest' \
  --tests '*ShiftCoverageIdsTest' \
  --max-workers=1 --console=plain
```

## Task 3: canonical snapshot과 normalized provider ABI를 구현

**Files:** `planner/ShiftCoverageSnapshot.kt`, `ShiftCoverageCanonicalizer.kt`, `adapter/*`, canonical/signature tests and fixtures.

- [ ] **Step 1: canonicalization RED를 작성한다.** semantic snapshot의 field/map/array
  순서만 바꾼 입력이 NFC 문자열, UTC `Z` instant, plain decimal, schema-order JSON bytes,
  SHA-256 digest를 동일하게 만드는 golden fixture를 추가한다. duplicate/unknown key,
  trailing token, depth 13, body 256 KiB 초과, non-finite number는 parse 전에 거부한다.
- [ ] **Step 2: canonicalizer를 구현한다.** Jackson 3 mapper에 closed deserialization과
  duplicate detection을 켜고 map/skill/availability/worker/shift/assignment/pin 배열을
  정렬한다. raw callback body/secret을 저장하지 않고 `MessageDigest.isEqual`로 digest를
  비교한다. 공개 API는 다음처럼 고정한다.

```kotlin
class ShiftCoverageCanonicalizer(private val mapper: ObjectMapper) {
    fun canonicalBytes(snapshot: ShiftCoverageSnapshot): ByteArray
    fun digest(snapshot: ShiftCoverageSnapshot): SnapshotDigest
    fun compare(expected: SnapshotDigest, actual: SnapshotDigest): DigestMatch
}
```

- [ ] **Step 3: provider ABI를 구현한다.** `PlanningRequest`는 provider/dataset/generationId/
  aggregate/site/revision/canonicalizationVersion/snapshotDigest/callbackBinding만,
  `PlanningCallback`은 provider/event/request/dataset/generationId/aggregate/site/
  targetAssignment/providerRevision/status/proposalDigest/score/reason만 담는다. raw row,
  credential, secret은 제외하고 `submit`/`accept` 두 method만 노출한다.
- [ ] **Step 4: HMAC callback fixture를 구현한다.** canonical bytes와 versioned context
  `(v1,method,path,schemaVersion,provider,requestId,datasetId,generationId,aggregateId,siteId,eventId,issuedAt)`를
  length-prefixed UTF-8 bytes로 묶어 `X-Shift-Coverage-Signature`/
  `X-Shift-Coverage-Key-Version` header를 검증하고 constant-time compare, DB clock 기준
  5분 replay, wrong binding을 확인한다. signature/target preflight는 inbox `RECEIVED`
  claim보다 먼저 실행하며 invalid/missing signature, wrong target, replay는 inbox/plan/
  assignment no-write fixture로 고정한다.
- [ ] **Step 5: GREEN을 실행한다.**

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*ShiftCoverageCanonicalizerTest' \
  --tests '*ShiftCoverageCallbackEnvelopeTest' \
  --tests '*ShiftCoverageSignatureVerifierTest' \
  --max-workers=1 --console=plain
```

## Task 4: deterministic planner와 bounded admission을 TDD로 구현

**Files:** `planner/DeterministicShiftCoveragePlanner.kt`, `ShiftCoverageReason.kt`,
`application/ShiftCoveragePlanService.kt`, planner/complexity/lifecycle tests.

- [ ] **Step 1: planner RED를 작성한다.** same-site, valid interval, complete availability,
  all skills, minimum rest, started/pin immutability를 hard-rule 순서로 검증한다. 실패
  사유는 `OVERLAP`, `UNAVAILABLE`, `MISSING_SKILL`, `REST_RULE`, `STARTED_SHIFT`, `PINNED`,
  `NO_CANDIDATE`이고 equal vector는 `(shiftId, workerId, assignmentId)` lexical order다.
  동일 canonical snapshot은 assignment/reason/metrics/digest가 byte-identical이어야 한다.
- [ ] **Step 2: planner 최소 구현을 작성한다.** 후보를 hard-rule 순서로 filter하고
  coverage gap reduction → preference → fairness delta → cost → stable ID로 정렬한다.
  후보 50,000 초과는 `PLANNER_LIMIT_EXCEEDED`, 5초 초과는 `REPLAN_TIMEOUT`이며
  materialization하지 않는다. `PlannerClock`과 `StepBudget`을 주입하고 production은
  `System.nanoTime`을 사용한다. fake clock으로 5초 미만·정확히 5초·초과를 각각 고정해
  경계에서만 timeout을 발생시키며, score addition은 overflow-checked signed `Long`이다.
- [ ] **Step 3: CPU admission을 구현한다.** fixed worker 4와 `ArrayBlockingQueue(8)`를
  사용하고 동일 dataset의 진행 중 replan은 하나로 합친다. queue full은
  `REPLAN_REJECTED`/429 no-write다. CPU planner는 fixed executor에 남기고 blocking JDBC/
  HTTP만 virtual-thread admission으로 보낸다.
- [ ] **Step 4: cancellation/permit fixture를 작성한다.** `StructuredTaskScopeTester` 또는
  `MultithreadingTester`로 cancellation/timeout 뒤 permit, queued generation, lease가
  남지 않는지 검증한다. `CancellationException`은 일반 오류로 변환하지 않고 재전파한다.
- [ ] **Step 5: GREEN을 실행한다.**

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*DeterministicShiftCoveragePlannerTest' \
  --tests '*ShiftCoveragePlannerComplexityTest' \
  --tests '*ShiftCoverageExecutorLifecycleTest' \
  --max-workers=1 --console=plain
```

## Task 5: Exposed schema/repository와 PostgreSQL authority를 구현

**Files:** all `persistence/*`, schema/repository/CAS tests, test resources.

- [ ] **Step 1: table contract RED를 작성한다.** assignment `(site_id, shift_id, worker_id)`,
  shift `(site_id, start_at, shift_id)`, inbox `(provider, event_id)`, idempotency
  `(method, route, demo_scope, principal, key)`와 `fingerprint_sha256 CHAR(64)`
  (source/target/plan/dataset/generation/body digest의 canonical SHA-256 hex,
  lowercase-hex length/check constraint), outbox `(status, next_attempt_at, id)` unique/index와
  FK/check constraint를 PostgreSQL fixture에서 먼저 검증한다.
- [ ] **Step 1 RED command를 관찰한다.**

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*ShiftCoverageTableContractTest' \
  --tests '*ShiftCoverageLockOrderDeadlockTest' \
  --tests '*ShiftCoveragePostgresLockTimeoutTest' \
  --tests '*ShiftCoverageIdempotencySchemaTest' \
  --tests '*ShiftCoverageIdempotencyRestartTest' \
  --max-workers=1 --console=plain
```

Expected: 새 테스트/프로젝트가 없어서 test class 또는 compilation failure가 발생한다.
Docker가 없으면 이 RED 관찰은 `PENDING`으로 기록하고 skipped test를 PASS로 바꾸지 않는다.

- [ ] **Step 2: table contract와 audit aggregate를 구현한다.** assignment `(site_id,
  shift_id, worker_id)`, shift `(site_id, start_at, shift_id)`, inbox `(provider, event_id)`,
  idempotency `(method, route, demo_scope, principal, key, fingerprint_sha256)`, outbox
  `(status, next_attempt_at, id)`의
  unique/index와 FK/check constraint를 만든다. plan/generation/audit aggregate의 PK와
  record `id`는 모두 `UUID`로 고정하고 `AuditableUUIDTable` 및
  `UUIDAuditableJdbcRepository`를 기존 `PlanningRequestRepository`와 같은
  `ResultRow.toEntity`, `extractId`, `auditedUpdateAll` 형태로 구현한다. `Uuid.V7.nextId()`를
  client default로 사용하고 raw `UUID.randomUUID()`를 신규 production에 추가하지 않는다.
- [ ] **Step 3: 업무 repository와 canonical lock order를 구현한다.** mutating transaction은
  event/idempotency claim → worker `(siteId, workerId)` → shift `(siteId, startAt, shiftId)` →
  assignment `(shiftId, workerId)` → plan `(planId)` → swap `(swapId)` 순으로 `forUpdate`한다.
  각 lock class와 claim key는 tuple을 UTF-8 lexical ascending으로 정렬하고, 입력 순서가
  역순이어도 같은 canonical order로 acquire한다. 두 transaction이 worker/shift/assignment를
  역순으로 요청하는 `ShiftCoverageLockOrderDeadlockTest`로 deadlock-free ordering을 확인한다.
  query path에는 lock을 넣지 않는다. 매 mutation session에
  `SET LOCAL lock_timeout='2s'`, `statement_timeout='5s'`를 설정한다.
- [ ] **Step 4: CAS/no-write를 구현한다.** approval/swap/event/outbox transition은
  expected revision/status/owner/token 조건을 update에 함께 넣고 `affectedRows == 1`만
  성공으로 취급한다. 실패/timeout/deadlock exhaustion은 rollback 후 별도 짧은 audit
  transaction에 bounded conflict code만 기록한다.
- [ ] **Step 4a: idempotency persistence evidence를 추가한다.** `ShiftCoverageIdempotencySchemaTest`
  는 fingerprint column의 64 lowercase-hex check와 unique `(method, route, demo_scope,
  principal, key)`를 확인한다. `ShiftCoverageIdempotencyRestartTest`는 재시작 뒤 동일
  fingerprint replay가 stored response를 반환하고 다른 fingerprint가 `409`
  no-write가 되는지 assertion한다.
- [ ] **Step 5: named timeout evidence를 추가한다.** `ShiftCoveragePostgresLockTimeoutTest`
  는 실제 PostgreSQL session의 `lock_timeout=2s`, `statement_timeout=5s`, lock wait 후
  `affectedRows == 0`, business table no-write, 별도 bounded conflict audit만 남는지를
  assertion한다. deadlock retry exhaustion과 timeout 모두 같은 no-partial-write 계약을 쓴다.
- [ ] **Step 6: PostgreSQL integration을 실행한다.**
  `PostgreSQLServer.Launcher.postgres`를 사용하고 module의 `junit-platform.properties`
  (`parallel.enabled=false`, class mode same-thread)와 Gradle `--max-workers=1`로 schema
  lifecycle을 직렬화한다. 공개 `withTables` 또는 기존 disposable `SchemaUtils` 패턴을
  사용한다. root `test-mutex` BuildService가 표준 `test` task에 상속된다는 것을
  `build.gradle.kts`에서 확인하고, custom integration task를 추가할 경우에도 같은
  `usesService` 등록을 명시한다. module 전용 mutex helper를 새로 만들지 않는다.
  Testcontainers는 다른 container suite와 병렬로 실행하지 않는다.

```bash
./gradlew :optimization-shift-coverage:cleanTest \
  :optimization-shift-coverage:test \
  --no-build-cache --max-workers=1 --console=plain
```

- [ ] **Step 7: GREEN command를 실행한다.** 위 command가 table/lock/timeout test를
  통과해야 하며 Docker/Colima unavailable이면 보고서에 원인·로그와 함께 `PENDING`으로
  남긴다.

## Task 6: approval, swap, idempotency, six-event inbox/replan convergence를 구현

**Files:** approval/swap/event/plan services, inbox/idempotency repositories and tests.

- [ ] **Step 1: idempotency RED를 작성한다.** same route/scope/principal/key와 same canonical
  fingerprint는 stored response replay, 다른 source/target/plan/dataset/generationId/body
  digest는 `409 IDEMPOTENCY_KEY_REUSED` no-write, in-progress는 bounded retry outcome이
  되는지 검증한다. manager-demo↔worker-demo 및 worker-a-demo↔worker-b-demo가 같은 key를
  재사용하는 negative fixture는 authorization-first no-write를 assertion하며, key는
  printable ASCII/200 UTF-8 byte limit이다.

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*ShiftCoverageIdempotencyTest' \
  --tests '*ShiftCoverageInboxRetryConvergenceTest' \
  --max-workers=1 --console=plain
```

Expected: idempotency service/repository가 없어 test compilation failure가 발생한다.
최소 구현 후 같은 명령이 GREEN이어야 한다.
- [ ] **Step 2: approval을 구현한다.** manager-only approval은 immutable plan의 worker/
  shift/assignment revision을 fixed lock order로 재검증하고 started shift/pinned
  assignment를 이동하지 않는다. `affectedRows == 1` CAS 후에만 `APPROVED`/outbox/audit를
  같은 transaction에 기록한다. planner/callback/replay는 assignment를 직접 변경하지 않는다.
- [ ] **Step 3: swap을 구현한다.** worker request는 source assignment/target worker/
  expected revision/plan revision/idempotency를 저장한다. manager acceptance는 source/
  target worker와 shift/assignment를 lock하고 overlap/availability/skill/rest/started/pin을
  재검증한다. 한 concurrent winner만 변경하고 loser는 `REVISION_CONFLICT` no-partial-write다.
- [ ] **Step 4: inbox/event/replan을 구현한다.** `availability.changed`,
  `shift.demand_changed`, `worker.sick_called`, `swap.requested`, `swap.accepted`,
  `shift.started`를 provider/event unique row로 먼저 `RECEIVED` claim한다.
  providerRevision `>`만 apply, `==` same digest는 duplicate, `<`는 `STALE`, mismatch
  digest는 `EVENT_KEY_REUSED`다. retry는 `2/4/8/16/30s`, 최대 5회, `RETRY_EXHAUSTED` 후
  operator requeue만 허용한다. `ShiftCoverageInboxRetryConvergenceTest`는
  `RETRY_EXHAUSTED`가 자동 replay되지 않고 operator-only requeue + 새 request ID + bounded
  audit 뒤에만 다시 `RECEIVED`가 되는지, `providerRevision ==` same digest duplicate,
  `>` apply, `<` stale, digest mismatch `EVENT_KEY_REUSED`의 terminal history를 각각
  assertion한다. 동일 `(datasetId, generationId)` replay는 materialization과 outbox를 한 번만
  만든다.
- [ ] **Step 5: generation/materialization을 구현한다.** durable generation을 `REQUESTED`
  로 만든 뒤 `RUNNING/SUCCEEDED/STALE/CANCELLED/FAILED`를 기록한다. aggregate revision과
  canonical digest가 현재와 일치할 때만 materialize하며 stale generation은 audit와 다음
  unique generation을 남긴다.
- [ ] **Step 6: concurrency/ordering tests를 실행한다.** `MultithreadingTester`로 sick-call
  vs swap, same-target swap, duplicate callback을 실행하고 하나의 terminal history/no
  partial loser write를 검증한다. UTC midnight, DST gap/ambiguous offset, started shift,
  pinned assignment, out-of-order callback을 deterministic fixture로 고정한다.

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*ShiftCoverageIdempotencyTest' \
  --tests '*ShiftCoverageInboxRetryConvergenceTest' \
  --max-workers=1 --console=plain
```

## Task 7: fenced outbox/effect와 Java 25 lifecycle을 구현

**Files:** `ShiftCoverageOutboxRepository.kt`, `ShiftCoverageOutboxWorker.kt`,
`ShiftCoverageExecutorLifecycle.kt`, outbox/restart tests.

- [ ] **Step 1: state-machine RED를 작성한다.** `PENDING → CLAIMED → STARTED →
  APPLIED/COMPLETED`, pre-send `RETRYABLE/DEAD_LETTER`, uncertain send
  `DELIVERY_UNKNOWN`와 paired-effect invariant를 table test로 고정한다. initial PENDING만
  effect-less state다.

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*ShiftCoverageOutboxStateMachineTest' \
  --tests '*ShiftCoverageOutboxQueueSaturationTest' \
  --tests '*ShiftCoverageOperatorRedriveTest' \
  --tests '*ShiftCoverageInboxRequeueTest' \
  --max-workers=1 --console=plain
```

Expected: outbox repository/state machine이 없어 test compilation failure가 발생한다.
최소 state table 구현 후 같은 명령이 GREEN이어야 한다.
- [ ] **Step 2: fenced claim/send/ack를 구현한다.** DB clock/owner/token/lease 조건으로
  claim하고 external send 전에 fenced `STARTED` commit (`affectedRows == 1`)을 수행한다.
  provider ACK는 같은 effect key/request ID를 포함해야 하며 effect/message 모두
  `affectedRows == 1`일 때 terminal completion한다. timeout/crash 뒤에는 duplicate send를
  하지 않고 definitive lookup으로 `APPLIED`/`NOT_FOUND`를 판정한다.
- [ ] **Step 3: worker bound를 구현한다.** startup 및 10초 sweep, ascending message ID,
  batch 10, four in-flight handler, lease 30s, I/O deadline 5s, max five/backoff를
  적용한다. delivery admission은 `ArrayBlockingQueue(8)`로 고정하고 queue가 가득 차면
  row를 claim/send하지 않고 `PENDING|RETRYABLE` 그대로 둔다. queue saturation test는
  no-write와 retry-after를 assertion한다. expired NOT_STARTED claim만 reclaim하고 stale
  owner를 fencing하며 STARTED는 UNKNOWN으로 남긴다.
- [ ] **Step 4: lifecycle을 구현한다.** mutation/replan admission을 닫고 pending generation을
  cancel하며 `finally`에서 permit/lease를 해제한다. 30초 drain 후 timeout이면
  `shutdownNow()`하고 interrupt flag를 복원한다. readiness는 shutdown 중 false, liveness는
  유지한다. `KLogging` bounded events로 close/retry/unknown을 기록한다.
- [ ] **Step 5: operator redrive와 restart/cancellation GREEN을 실행한다.**
  `POST /api/shift-coverage/outbox/{effectKey}/redrive`는 `X-Demo-Role=manager`만
  허용하고, definitive provider lookup이 `NOT_FOUND`로 전환한 `RETRYABLE`만 같은
  effect key로 재시도한다. `DELIVERY_UNKNOWN`은 lookup 전 redrive하지 않으며,
  `APPLIED|COMPLETED`, `DEAD_LETTER`, unresolved lookup은 redrive하지 않는다. operator
  request ID/reason/audit를 남긴다. Inbox는 별도로
  `POST /api/shift-coverage/inbox/{provider}/{eventId}/requeue`를 manager-only로 허용하고
  `RETRY_EXHAUSTED`에서만 저장 digest·새 request ID·bounded reason/audit와 함께
  `RECEIVED`로 되돌린다. fake provider가 ACK 후 DB commit 전 crash, UNKNOWN lookup,
  operator redrive/requeue를 재현하고 permit/lease/orphan effect가 남지 않는지
  PostgreSQL + lifecycle test로 검증한다.

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*ShiftCoverageOutboxStateMachineTest' \
  --tests '*ShiftCoverageOutboxQueueSaturationTest' \
  --tests '*ShiftCoverageOperatorRedriveTest' \
  --tests '*ShiftCoverageInboxRequeueTest' \
  --max-workers=1 --console=plain
```

## Task 8: closed HTTP/demo role/read-model과 browser console을 구현

**Files:** `web/*`, static resources, controller/filter/browser tests, `application.yml`.

- [ ] **Step 1: route/DTO RED를 작성한다.** query `GET /api/shift-coverage/plans`,
  `GET /api/shift-coverage/swaps`; demo mutation `POST /api/shift-coverage/replans`,
  `POST /api/shift-coverage/plans/{revision}/approve`, `POST /api/shift-coverage/swaps`,
  `POST /api/shift-coverage/swaps/{id}/accept`; callback
  `POST /api/shift-coverage/callbacks/{provider}`; operator redrive
  `POST /api/shift-coverage/outbox/{effectKey}/redrive`와 inbox requeue
  `POST /api/shift-coverage/inbox/{provider}/{eventId}/requeue`의
  method/path/header/body/status/error matrix를 live `WebTestClient`로 고정한다.
  `Idempotency-Key`, `X-Request-Id`,
  `X-Demo-Operator`, `X-Demo-Role`, callback의 `X-Shift-Coverage-Signature`와
  `X-Shift-Coverage-Key-Version` header 이름을 고정하고, `409` (`REVISION_CONFLICT`,
  `IDEMPOTENCY_KEY_REUSED`, `CALLBACK_REPLAY`, `EVENT_KEY_REUSED`, `STALE`), `413`
  (`RESPONSE_TOO_LARGE`), `422` (`RETRY_EXHAUSTED`), `429` (`REPLAN_REJECTED`),
  `202` (`REPLAN_ACCEPTED`)와 `400` (`REQUEST_INVALID`), `401`
  (`CALLBACK_SIGNATURE_INVALID`), `403` (`DEMO_ROLE_FORBIDDEN`, `LOOPBACK_REQUIRED`,
  `ORIGIN_FORBIDDEN`), `404` (`DEMO_PROFILE_REQUIRED`)의 retryability/`Retry-After`/
  no-write/nextAction을 golden matrix로 assertion한다. malformed/unknown DTO, invalid or
  missing signature, wrong target, forbidden role, non-loopback/non-demo/CORS denial은
  response·audit·inbox no-write를 함께 고정한다.

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*ShiftCoverageControllerTest' \
  --tests '*ShiftCoverageHttpErrorMatrixTest' \
  --tests '*ShiftCoverageCallbackPreflightTest' \
  --tests '*ShiftCoverageRoleRedactionTest' \
  --tests '*ShiftCoverageOperatorRedriveTest' \
  --tests '*ShiftCoverageInboxRequeueTest' \
  --max-workers=1 --console=plain
```

Expected: 새 controller/project가 없어 test compilation failure가 발생한다. 최소 DTO와
route 구현 후 같은 명령이 GREEN이어야 하며 live HTTP/Testcontainers가 불가능하면 해당
부분만 `PENDING`으로 남긴다.
- [ ] **Step 2: closed DTO와 filter를 구현한다.** unknown fields/duplicate keys/trailing
  tokens/non-finite/open enum/oversized body/depth를 거부한다. loopback+`demo` profile,
  bounded `X-Demo-Operator`, `X-Demo-Role` (`manager|worker`), `X-Request-Id`, idempotency
  header를 확인하고 response에는 stable code/requestId/retryability/Retry-After/nextAction만
  둔다. `X-Demo-Operator`는 고정된 `manager-demo→manager/site-demo`와
  `worker-a-demo|worker-b-demo→worker/site-demo` subject로만 매핑하고 principal/role/
  worker/site scope를 idempotency namespace와 canonical fingerprint에 포함한다.
  authorization과 callback signature/target preflight는 inbox/plan/assignment write보다
  먼저 수행하고, versioned context `(v1, method, path, schemaVersion, provider, requestId,
  datasetId, generationId, aggregateId, siteId, eventId, issuedAt)`를 length-prefixed
  UTF-8 canonical bytes로 HMAC-SHA-256 검증한다. invalid/missing signature, wrong target,
  replay는 `RECEIVED` inbox row와 모든 business row를 만들지 않는다. raw callback,
  credential, JDBC URL, internal exception은 redacted한다.
- [ ] **Step 3: role/read-model allowlist를 구현한다.** manager에는 site/shift/synthetic
  worker/interval/assignment state/plan revision/gap/cost/fairness/reason/change impact만,
  worker에는 자기 worker/shift/interval/site/state/swap status만 노출한다. forbidden field,
  secret-like value, 다른 worker availability/preference가 JSON에 없는 redaction canary를
  작성한다. manager↔worker, worker-A↔worker-B 동일 idempotency key와 foreign site/worker
  조회는 authorization-first no-write/no-read fixture로 고정한다. non-loopback, non-demo,
  foreign `Origin`/CORS, malformed-body secret sentinel도 response/audit/log/metric에
  나타나지 않는지 assertion한다.
- [ ] **Step 4: cursor/metrics/actuator를 구현한다.** opaque Base64URL cursor <=256,
  query page <=100, low-cardinality metrics (`plan.duration`, `candidate_evaluations`,
  conflicts, callback duplicate/stale, outbox retry/dead-letter), `/actuator/health`와
  `/actuator/prometheus` contract를 검증한다. worker/shift/tenant/raw SQL을 label에 넣지 않는다.
- [ ] **Step 5: browser console을 구현한다.** static HTML/JS는 plan revision, gap, cost,
  fairness, reason, change impact, swap state를 표시하고 redacted routes만 호출한다.
  manager/worker command visibility, stale 409 refresh, 413 shrink, 429 retry-after,
  202 poll을 keyboard-accessible state로 제공한다.

```bash
./gradlew :optimization-shift-coverage:test \
  --tests '*ShiftCoverageControllerTest' \
  --tests '*ShiftCoverageHttpErrorMatrixTest' \
  --tests '*ShiftCoverageCallbackPreflightTest' \
  --tests '*ShiftCoverageRoleRedactionTest' \
  --tests '*ShiftCoverageOperatorRedriveTest' \
  --tests '*ShiftCoverageInboxRequeueTest' \
  --max-workers=1 --console=plain
```

## Task 9: module/group documentation and CI registration

**Files:** module/root README locales, Examples workflow, smoke validator, lesson.

- [ ] **Step 1: module README 쌍을 작성한다.** fake default, optional provider boundary,
  local loopback safety, PostgreSQL/Testcontainers, Java 25, exact commands, limits,
  manager/worker role fixture, no production credential/autonomous reassignment를 한·영
  parity로 기록한다. reader-facing prose/KDoc는 Korean 규칙을 따른다.
- [ ] **Step 2: optimization README 쌍에 module 목적/인프라/검증 명령을 추가한다.** 기존
  two-row table의 문체를 유지하고 BOM-only consumer rule과 sibling implementation
  non-dependency를 명시한다.
- [ ] **Step 3: `.github/workflows/Examples.yml`를 등록한다.** optimization container job에
  `:optimization-shift-coverage:test`와 test XML/report artifact path를 추가한다. path
  filter와 summary `needs`를 읽어 기존 job skip이 coverage proof가 되지 않도록 한다.
- [ ] **Step 4: `scripts/smoke-validate.sh`를 등록한다.** `optimization)`에 module test를
  추가하고 stale-check required module list/help를 갱신한다. `settings.gradle.kts`는
  수정하지 않고 `./gradlew projects --console=plain`의 auto-registration을 evidence로
  남긴다.
- [ ] **Step 5: Korean lesson을 작성한다.** ecosystem reuse map, provider fake boundary,
  PostgreSQL authority, CAS/outbox fencing, Testcontainers serialization, skipped/PENDING
  interpretation, rollback path와 fresh command evidence를 기록한다.

## Task 10: 구현 후 검증, plan delta review, commit과 workflow DoD

- [ ] **Step 1: 구현 후 plan self-review를 실행한다.** spec §2.2 capability selection과
  acceptance 1–12 각각에 구현 Task/test evidence가 있는지 표로 확인한다. 계획과 코드의
  type/method/path 이름, UUID repository, canonical lock tuple, retry/redrive 상태를
  cross-reference해 불일치를 수정한다. 미완성 표식과 모호한 구현 지시는 제거한다.
- [ ] **Step 2: implementation delta review를 갱신한다.**
  `docs/superpowers/reviews/2026-08-24-issue-526-shift-coverage-plan-review.md`에
  preflight 이후 바뀐 구현·검증 증거를 추가하고 Performance, Stability, Security,
  Operator/Ops, Developer/API, User/caller 관점과 main integration을 다시 확인한다.
  P0/P1은 0이어야 하며 P2/P3는 정확한 Task와 검증 명령을 갖거나 범위 밖 사유를 가진다.
- [ ] **Step 3: fresh verification을 순서대로 실행한다.** Docker/Colima 상태를 먼저
  확인하고 Testcontainers를 다른 container suite와 병렬 실행하지 않는다.

```bash
colima status
docker context show
docker info
./gradlew :optimization-shift-coverage:cleanTest \
  :optimization-shift-coverage:test \
  --no-build-cache --max-workers=1 --console=plain
./gradlew :optimization-shift-coverage:build --max-workers=1 --console=plain
./gradlew :optimization-shift-coverage:detekt --max-workers=1 --console=plain
./gradlew projects --console=plain
bash scripts/smoke-validate.sh optimization
bash scripts/smoke-validate.sh stale-check
actionlint .github/workflows/Examples.yml
git diff --check
```

  detekt task가 등록되지 않으면 exact error를 `PENDING`으로 기록하고 PASS로 바꾸지 않는다.
  Testcontainers가 unavailable이면 Docker/Colima 로그와 report를 보존하고 해당 검증을
  `PENDING`으로 남긴다.
- [ ] **Step 4: static/ABI boundary를 검사한다.** 신규 production Kotlin에서 `println`,
  `synchronized`, monitor, `!!`, suspend `runCatching`, deprecated Exposed
  `SqlExpressionBuilder.eq`, `project(":optimization-planning-contracts")`, raw Bluetape
  version pin, credential/default URL, provider network call을 검색한다. dependency graph가
  planning-contracts implementation을 포함하지 않는지 `dependencyInsight`로 확인한다.
  root `build.gradle.kts`의 `test-mutex` 등록과 `usesService(testMutex)`가 표준 `test`
  task에 유지되는지 read-back하고, custom integration task를 만들지 않았는지 확인한다.
- [ ] **Step 5: Lore commit을 만든다.** 구현/문서/검증이 통과한 뒤 한국어 intent line과
  `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`,
  `Not-tested` trailers를 포함한다. push/PR/merge/release/Epic closure는 이 계획 범위에
  포함하지 않는다.

## Acceptance traceability

| Spec acceptance | 구현 Task | 최소 증거 |
|---|---:|---|
| hard rule reason codes | 2, 4 | planner/domain tests |
| canonical v1/golden digest | 3 | canonical bytes/digest fixture |
| envelope/deadline/no-write | 2, 4, 8 | limit/complexity/HTTP 413/429 tests |
| approval revision CAS | 5, 6 | PostgreSQL stale approval no-write |
| swap idempotency/concurrency | 5, 6 | MultithreadingTester + unique fingerprint |
| six event duplicate/order safety | 6 | inbox monotonic/terminal history tests |
| UTC/DST/start/pin/restart | 4, 6, 7 | boundary/restart fixtures |
| lock/timeout/virtual lifecycle | 5, 7 | DB timeout/lifecycle/permit tests |
| HMAC/target/replay/role/redaction | 3, 8 | callback/security canary/live HTTP |
| query/command/error/metrics/ABI | 3, 8 | DTO/actuator/metrics tests |
| module/workflow/smoke/matrix/docs | 9, 10 | projects/smoke/actionlint/parity |
| no provider credential/autonomous reassignment | 1, 3, 8, 10 | dependency/config/static scan |

## Stop condition

`optimization-shift-coverage` module test/build/static checks, PostgreSQL Testcontainers,
canonical/ABI/security/lifecycle evidence, optimization smoke/stale-check, README parity,
workflow lint, plan review P0/P1=0, Lore commit, clean worktree가 모두 확인될 때 #526
implementation unit을 `DONE`으로 표시한다. 어느 container/lifecycle check라도 실행되지
않으면 해당 항목은 `PENDING`으로 남기며 전체 DoD를 `DONE`으로 보고하지 않는다. #527,
#528, #529는 이 module의 DoD 이후에만 순서대로 시작한다.

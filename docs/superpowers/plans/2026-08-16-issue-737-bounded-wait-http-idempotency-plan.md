# Job Console bounded-wait HTTP idempotency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Issue #737의 Spring MVC와 Ktor `POST /v1/jobs`에 PostgreSQL 전역 상태 머신 기반 bounded-wait HTTP idempotency를 추가하고, upstream conformance·durable persistence·migration·rollback·문서 증거를 모두 확보한다.

**Architecture:** 기존 `job_requests`를 V002 additive migration으로 확장하고 `job_request_waiters`를 추가한다. framework-neutral `JobSubmissionIdempotencyCoordinator`가 짧은 Reserve/Finalize transaction과 connection-free Prepare/Wait를 소유하며, Spring과 Ktor는 같은 core outcome을 HTTP로 변환한다. production endpoint는 성공한 202 snapshot만 terminal replay하고, test-only endpoint는 upstream conformance의 synthetic 201/422를 별도로 제공한다.

**Tech Stack:** Kotlin 2.4.0, Java 25, Spring Boot 4.0.6 MVC, Ktor Netty, PostgreSQL, JDBC/HikariCP, Jackson 3, kotlinx-coroutines, JUnit 5, Testcontainers, `bluetape4k-junit5:1.12.1` conformance API, Gradle version catalog/BOM.

---

## 0. 구현 전제와 승인 경계

### 기준과 입력

- 작업 디렉터리: `/Users/debop/work/bluetape4k/bluetape4k-workshop/.worktrees/feat/issue-737-bounded-wait-http-idempotency`
- 브랜치: `feat/issue-737-bounded-wait-http-idempotency`
- 기준 SHA: `8fc6cd375d20c3b448f7224d84d9cea3d5ae8080`
- 승인 명세: [`docs/superpowers/specs/2026-08-16-issue-737-bounded-wait-http-idempotency-design.md`](../specs/2026-08-16-issue-737-bounded-wait-http-idempotency-design.md)
- GitHub issue: [#737](https://github.com/bluetape4k/bluetape4k-workshop/issues/737)
- 이미 승인된 설계 commit: `063a2b97c5b8fd1ea11f6713a5cee5e435244c52`
- 명세 review 수렴 commit: `f3b324134`

### 실행 규칙

1. 이 계획 파일의 사용자 승인이 Step 4 구현의 별도 게이트다. 계획 승인 전에는 Kotlin, SQL, README, workflow를 수정하지 않는다.
2. 구현자는 `$test-driven-development`와 `$bluetape-kotlin-patterns`를 먼저 읽고, Spring/Ktor adapter를 만질 때 coroutine cancellation과 blocking boundary를 다시 확인한다. 문서/diagram 단계에서는 `$bluetape-writer`와 `$bluetape-diagram`을 적용한다.
3. 구현은 core 계약/저장소를 먼저 완료하고, 그 뒤 Spring과 Ktor adapter를 서로 겹치지 않는 소유 범위로 병렬화할 수 있다. Testcontainers, migration, high-contention 검증은 `TestMutexService` 아래 순차 실행한다.
4. 모든 commit은 Lore protocol을 따른다. 의도 line은 한국어로 작성하고 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested` trailer를 실제 검증 내용에 맞춰 채운다.
5. PR 생성·merge·branch 삭제·release는 이 계획의 구현 완료와 별도 권한 단계다. exact head, CI, review/thread, mergeability를 다시 읽은 뒤 fresh merge approval 없이는 merge하지 않는다.
6. 각 Task의 GREEN 검증 직후 해당 Task 소유 파일만 stage하고 Lore commit을 만든다. 다음 Task는 `git status --short`가 예상 파일 외에 깨끗하고 직전 commit의 targeted command가 PASS일 때 시작한다.

### 계획 소유와 병렬화 경계

| 소유 lane | 책임 파일 범위 | 선행 조건 | 겹치지 않는 이유 |
|---|---|---|---|
| Core 계약/상태 머신 | `operations/job-console-core/src/main/kotlin/**/idempotency/**`, `JobConsoleService.kt`, `JobRepository.kt`, `JobTables.kt`, core tests/fixtures | Task 1–2 | DB 상태와 public service outcome의 단일 소유자 |
| Spring adapter | `operations/job-console-spring/src/main/**`, Spring tests, Spring README | Core Task 5, fixture 계약 | Ktor 파일을 수정하지 않음 |
| Ktor adapter | `operations/job-console-ktor/src/main/**`, Ktor tests, Ktor README | Core Task 5, fixture 계약 | Spring 파일을 수정하지 않음 |
| Integration/CI | core Testcontainers/high-contention fixture, `.github/workflows`, scripts | Core·adapter green | DB 및 workflow 변경을 단일 lane에서 직렬화 |
| Docs/diagram | 세 module README, `docs/images/readme-diagrams/*` | HTTP contract와 검증 결과 | source code와 분리된 reader-facing 산출물 |

동일 파일을 두 lane이 동시에 편집하지 않는다. lane이 다른 파일에서 compile 오류를 발견해도 상대 lane의 diff를 되돌리지 않고 mailbox로 전달한 뒤 통합자가 조정한다.

---

## 1. 파일 구조와 책임 고정

구현 시작 전에 아래 파일 목록을 기준으로 `rg`로 현재 symbol과 모든 caller를 확인한다. 이름을 바꿀 때는 이 계획의 이후 task와 테스트명을 함께 갱신한다.

### Core 신규 파일

- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyModels.kt` — `JobSubmissionCommand`, `JobSubmissionOwnership`, `PreparedJobSubmission`, `ReplayableJobSubmission`, `JobSubmissionOutcome` sealed hierarchy.
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyPolicy.kt` — key/body/header/waiter/lease/retention/pool bound와 동일 policy fingerprint.
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionCanonicalizer.kt` — strict submit request canonical tuple과 domain-separated SHA-256.
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionSnapshotPolicy.kt` — 저장 가능한 status/content-type/header와 body/aggregate byte 검증.
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyRepository.kt` — reserve/waiter/poll/finalize/abandon/janitor/legacy CAS의 JDBC 경계.
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyCoordinator.kt` — Reserve → connection-free Prepare/Wait → Finalize orchestration과 cancellation cleanup.
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyObservability.kt` — low-cardinality outcome/ready/cleanup 계측 adapter.
- `operations/job-console-core/src/main/resources/db/job-console/V002__bounded_wait_http_idempotency.sql` — V001을 수정하지 않는 additive schema/index/constraint.

### Core 변경/테스트 파일

- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/api/JobApiModels.kt` — production submission response와 `JobProblem` wire model에 필요한 안정 필드만 추가.
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/domain/JobModels.kt` — `idempotency_*` problem code와 wire value 추가.
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobTables.kt` — Exposed schema mirror에 V002 column/table/index를 반영하되 SQL migration이 권위다.
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobRepository.kt` — 기존 `submit`의 advisory-lock 일괄 동작을 coordinator가 호출할 transaction-aware job creation primitive로 분해.
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/application/JobConsoleService.kt` — `submit`이 replay bit와 검증된 response snapshot을 유지하는 outcome을 반환하도록 변경.
- `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/` — canonicalization, policy, coordinator unit test.
- `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyRepositoryTest.kt` — V002 schema/상태 전이/GC/legacy CAS 통합 test.
- `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyMultiInstanceTest.kt` — 서로 다른 datasource/coordinator 인스턴스의 global waiter/CAS test.
- `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyMigrationTest.kt` — V001→V002, rerun/checksum/disabled rollback 조건.
- `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/BoundedWaitHttpIdempotencyFixture.kt` — upstream adapter 공통 설정/clock/barrier/response recorder.
- `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/JobConsoleContract.kt` — first/replay HTTP contract를 새 header와 problem schema에 맞춰 갱신.
- `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/JobConsoleHighContentionAdapter.kt` — V002 migration과 pool/DB semaphore/owner prepare bound 검증 fixture.
- `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobConsoleDatabaseFixture.kt` — V002 ordered migration과 pool size 8.

### Framework 및 문서 변경 파일

- Spring: `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/JobConsoleSpringConfiguration.kt`, `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/JobConsoleSpringController.kt`, `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/JobConsoleProblemHandler.kt`, `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/JobConsoleSpringLifecycle.kt`, `operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringJobSubmissionIdempotencyHttpTest.kt`, `operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringJobConsoleProfileApplication.kt`.
- Spring 입력 경계: `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringBoundedJsonBodyReader.kt` — `HttpServletRequest.inputStream`을 64 KiB에서 중단하고 strict Jackson 3 typed DTO로 변환.
- Spring runtime property: `operations/job-console-spring/src/main/resources/application.properties` — `spring.threads.virtual.enabled=true`와 bounded-wait 기본 feature/policy key.
- Ktor: `operations/job-console-ktor/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/JobConsoleKtorApplication.kt`, `operations/job-console-ktor/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/JobSubmissionKtorHttp.kt`, `operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/KtorJobSubmissionIdempotencyHttpTest.kt`, `operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/KtorJobConsoleProfileServer.kt`.
- 문서: `operations/job-console-core/{README.md,README.ko.md}`, `operations/job-console-spring/{README.md,README.ko.md}`, `operations/job-console-ktor/{README.md,README.ko.md}`.
- Diagram: `docs/images/readme-diagrams/operations-job-console-bounded-wait-idempotency-01.svg`, `.ko.svg`, 각 2x PNG.
- CI/stale audit 대상: `.github/workflows/ci.yml`, `.github/workflows/nightly.yml`, `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`, `settings.gradle.kts`, `build.gradle.kts`, 관련 stale/readme validator. task/module 이름이 이미 포함되면 수정하지 않고 audit 증거만 남긴다.

---

## 2. Task 1 — RED: 입력 계약·정책·canonical fingerprint

**Files:**

- Create: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyModels.kt`
- Create: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyPolicy.kt`
- Create: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionCanonicalizer.kt`
- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/domain/JobModels.kt`
- Test: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionCanonicalizerTest.kt`
- Test: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionPolicyTest.kt`

- [ ] **Step 1: 실제 upstream와 현재 DTO를 고정하는 failing test를 작성한다.** `SubmitJobRequest`의 omitted/default `failureMode`와 explicit `none`이 같은 fingerprint가 되고, field order/whitespace만 다른 JSON이 같은 typed request로 수렴하며, enum 이외 값·`workUnits` 0/10001·duplicate/unknown field는 `invalid_idempotency_request`가 되는 test를 먼저 추가한다. raw `Idempotency-Key`는 trim/case-fold/Unicode normalization하지 않고 ASCII `0x21..0x7E` 문자만 허용하며 1..255 bytes 범위를 지킨다. empty, whitespace-only, tab, 256-byte, non-ASCII, comma-joined 값과 case-insensitive duplicate header를 각각 거절한다. forged scope, cross-tenant/cross-submitter key, unauthenticated oversized body의 precedence도 negative matrix에 포함한다.

  ```kotlin
  @Test
  fun `omitted failure mode has the same canonical fingerprint as explicit none`() {
      val omitted = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10)
      val explicit = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)
      canonicalizer.fingerprint(omitted) shouldBe canonicalizer.fingerprint(explicit)
  }

  @Test
  fun `raw key bytes are preserved and bounded`() {
      shouldThrow<IllegalArgumentException> { canonicalizer.keyHash(scope, " ") }
      shouldThrow<IllegalArgumentException> { canonicalizer.keyHash(scope, "x".repeat(256)) }
      canonicalizer.keyHash(scope, "Key-A") shouldNotBe canonicalizer.keyHash(scope, "key-a")
  }
  ```

- [ ] **Step 2: 실패를 확인한다.** 실행: `./gradlew :operations-job-console-core:test --tests '*JobSubmissionCanonicalizerTest' --tests '*JobSubmissionPolicyTest'`. 예상 결과: 새 type/function이 없어 compile 또는 assertion failure가 발생하고 기존 test는 변경하지 않은 상태로 남는다.

- [ ] **Step 3: 최소 모델과 정책을 구현한다.** 다음 경계를 유지한다. 모든 duration/count/byte bound는 startup 시 양수·내부 상한을 검증하고 동일 policy fingerprint를 계산하며, owner/waiter token은 CSPRNG `UUID`로 생성하고 client 입력으로 받지 않으며 log/metric에 전달하지 않는다.

  ```kotlin
  internal data class JobSubmissionCommand(
      val scope: DemoCallerScope,
      val keyHash: String,
      val requestFingerprint: String,
      val request: SubmitJobRequest,
      val policyFingerprint: String,
  )

  internal data class JobSubmissionOwnership(
      val scope: DemoCallerScope,
      val keyHash: String,
      val generation: Long,
      val jobId: UUID,
      val ownerToken: UUID,
      val leaseExpiresAt: Instant,
  )

  internal data class PreparedJobSubmission(
      val request: SubmitJobRequest,
      val responseStatus: Int = 202,
      val responseBody: ByteArray,
      val responseContentType: String = "application/json",
      val responseHeaders: Map<String, List<String>> = emptyMap(),
  )

  internal data class ReplayableJobSubmission(
      val jobId: UUID,
      val enqueueSequence: Long,
      val responseStatus: Int,
      val responseBody: ByteArray,
      val responseContentType: String,
      val responseHeaders: Map<String, List<String>>,
  )

  internal sealed interface JobSubmissionOutcome {
      data class OwnerCompleted(val snapshot: ReplayableJobSubmission) : JobSubmissionOutcome
      data class Replayed(val snapshot: ReplayableJobSubmission) : JobSubmissionOutcome
      data object Conflict : JobSubmissionOutcome
      data object InFlightTimeout : JobSubmissionOutcome
      data object WaiterOverflow : JobSubmissionOutcome
      data object Abandoned : JobSubmissionOutcome
  }

  internal sealed interface Reservation {
      data class Owner(val ownership: JobSubmissionOwnership) : Reservation
      data class Wait(val ownership: JobSubmissionOwnership) : Reservation
      data class Replay(val snapshot: ReplayableJobSubmission) : Reservation
      data object Conflict : Reservation
      data object Overflow : Reservation
      data object Abandoned : Reservation
  }

  internal data class InFlightOwnership(val ownership: JobSubmissionOwnership)

  internal sealed interface WaiterRegistration {
      data class Registered(val waiterToken: UUID, val generation: Long) : WaiterRegistration
      data object Overflow : WaiterRegistration
  }

  internal sealed interface PollResult {
      data class Terminal(val snapshot: ReplayableJobSubmission) : PollResult
      data class Abandoned(val generation: Long) : PollResult
      data object StillInFlight : PollResult
  }

  internal enum class AbandonReason { PREPARE_FAILED, PREPARE_DEADLINE, CANCELLED, OWNER_DISCONNECTED }

  internal data class CleanupReport(val waitersDeleted: Int, val requestsDeleted: Int)

  internal data class JobSubmissionIdempotencyPolicy(
      val ownerLease: Duration = Duration.ofSeconds(30),
      val prepareDeadline: Duration = Duration.ofSeconds(10),
      val waiterTimeout: Duration = Duration.ofSeconds(2),
      val maxWaitersPerKey: Int = 2,
      val maxWaitersPerInstance: Int = 32,
      val datasourcePoolSize: Int = 8,
      val idempotencyDbConcurrency: Int = 4,
      val ownerPrepareConcurrency: Int = 8,
      val connectionAcquireTimeout: Duration = Duration.ofMillis(250),
      val statementTimeout: Duration = Duration.ofMillis(500),
      val pollInitialInterval: Duration = Duration.ofMillis(25),
      val pollMaxInterval: Duration = Duration.ofMillis(100),
      val janitorBatchSize: Int = 100,
      val retention: Duration = Duration.ofHours(1),
      val maxKeyBytes: Int = 255,
      val maxBodyBytes: Int = 64 * 1024,
      val maxReplayBytes: Int = 64 * 1024,
      val maxHeaderNames: Int = 8,
      val maxHeaderValues: Int = 4,
      val maxHeaderValueBytes: Int = 4 * 1024,
      val maxAggregateHeaderBytes: Int = 16 * 1024,
  )
  ```

  canonical fingerprint는 `job-console-submit-v1` domain tag와 length-prefix tuple `(jobType.wireValue, decimal(workUnits), failureMode.wireValue)`를 SHA-256하고, key hash는 `job-console-key-v1`과 `(tenantId, submitterHash, rawKey)`를 length-prefix해 SHA-256한다. 이는 secret HMAC가 아니므로 scope isolation identifier라는 문구를 API KDoc에 남긴다.

- [ ] **Step 4: 통과를 확인하고 core contract commit을 만든다.** 실행: `./gradlew :operations-job-console-core:test --tests '*JobSubmissionCanonicalizerTest' --tests '*JobSubmissionPolicyTest'`. 예상 결과: PASS. commit에는 새 정책 값, problem code wire value, canonicalization test만 포함하고 SQL/adapter는 포함하지 않는다.

---

## 3. Task 2 — RED/GREEN: V002 additive migration과 schema mirror

**Files:**

- Create: `operations/job-console-core/src/main/resources/db/job-console/V002__bounded_wait_http_idempotency.sql`
- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobTables.kt`
- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobMigrationRunner.kt`
- Modify: `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/JobConsoleSpringConfiguration.kt`
- Modify: `operations/job-console-ktor/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/JobConsoleKtorApplication.kt`
- Modify: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobConsoleDatabaseFixture.kt`
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/JobConsoleHighContentionAdapter.kt`
- Modify: `operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringJobConsoleProfileApplication.kt`
- Modify: `operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/KtorJobConsoleProfileServer.kt`
- Test: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyMigrationTest.kt`
- Modify: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobMigrationRunnerTest.kt` — lock/statement timeout과 history checksum read-back.

- [ ] **Step 1: V001 보존과 재실행 실패를 검증하는 RED test를 작성한다.** Testcontainers에서 V001 fixture row를 하나 넣고 `[001, 002]` ordered migration을 실행한 뒤 PK, row count, 기존 `job_id`, `request_fingerprint`가 동일한지 확인한다. 같은 list를 다시 실행하면 no-op이어야 하며, 같은 version의 SQL bytes를 바꾼 runner는 checksum mismatch로 실패해야 한다.

- [ ] **Step 2: SQL을 작성한다.** V001은 수정하지 않는다. `job_requests`에 `state VARCHAR(16) NOT NULL DEFAULT 'TERMINAL'`, `generation BIGINT NOT NULL DEFAULT 1`, owner/response/terminal/retention/abandoned/update transition column을 additive로 추가하고 state별 `CHECK`를 둔다. `job_request_waiters`는 full scope tuple + generation + UUID waiter token을 PK로 하고 parent composite FK `ON DELETE CASCADE`를 둔다. waiter admission/expiry, terminal retention, abandoned GC index를 다음 네 개로 고정한다.

  ```sql
  ALTER TABLE job_requests
      ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'TERMINAL',
      ADD COLUMN generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
      ADD COLUMN owner_token UUID,
      ADD COLUMN owner_lease_expires_at TIMESTAMPTZ,
      ADD COLUMN response_status INTEGER,
      ADD COLUMN response_body BYTEA,
      ADD COLUMN response_content_type VARCHAR(128),
      ADD COLUMN response_headers JSONB,
      ADD COLUMN terminal_at TIMESTAMPTZ,
      ADD COLUMN retained_until TIMESTAMPTZ,
      ADD COLUMN abandoned_until TIMESTAMPTZ,
      ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

  ALTER TABLE job_requests
      ADD CONSTRAINT ck_job_requests_state
          CHECK (state IN ('IN_FLIGHT', 'TERMINAL', 'ABANDONED')),
      ADD CONSTRAINT ck_job_requests_owner_fields
          CHECK (state <> 'IN_FLIGHT' OR (owner_token IS NOT NULL AND owner_lease_expires_at IS NOT NULL)),
      ADD CONSTRAINT ck_job_requests_abandoned_until
          CHECK (state <> 'ABANDONED' OR abandoned_until IS NOT NULL),
      ADD CONSTRAINT ck_job_requests_response_status
          CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
      ADD CONSTRAINT ck_job_requests_response_body
          CHECK (response_body IS NULL OR octet_length(response_body) <= 65536),
      ADD CONSTRAINT ck_job_requests_response_content_type
          CHECK (response_content_type IS NULL OR response_content_type IN ('application/json', 'application/problem+json'));

  CREATE TABLE job_request_waiters (
      tenant_id VARCHAR(64) NOT NULL,
      submitter_hash CHAR(64) NOT NULL,
      key_hash CHAR(64) NOT NULL,
      generation BIGINT NOT NULL CHECK (generation > 0),
      waiter_token UUID NOT NULL,
      expires_at TIMESTAMPTZ NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (tenant_id, submitter_hash, key_hash, generation, waiter_token),
      FOREIGN KEY (tenant_id, submitter_hash, key_hash)
          REFERENCES job_requests(tenant_id, submitter_hash, key_hash)
          ON DELETE CASCADE
  );
  ```

  ```sql
  CREATE INDEX ix_job_request_waiters_admission
      ON job_request_waiters(tenant_id, submitter_hash, key_hash, generation, expires_at);
  CREATE INDEX ix_job_request_waiters_expiry ON job_request_waiters(expires_at);
  CREATE INDEX ix_job_requests_terminal_retention ON job_requests(state, retained_until);
  CREATE INDEX ix_job_requests_abandoned_until ON job_requests(state, abandoned_until);
  ```

  기존 row와 구 binary insert는 허용하고, legacy terminal row는 `created_at + interval '1 hour'`로 읽는다. V002 SQL에는 범용 backfill framework나 V001 수정이 없다.

- [ ] **Step 3: migration runner의 제한과 ordered list를 고정한다.** `JobMigrationRunner`가 migration transaction 시작 직후 `SET LOCAL lock_timeout = '2s'`와 `SET LOCAL statement_timeout = '30s'`를 실행하고, V002 전후 `job_requests` row count와 `job_schema_history` checksum을 redacted migration log로 기록하도록 optional constructor defaults를 추가한다. Spring `JobConsoleSpringConfiguration`, Ktor `jobConsoleModule`, core database fixture, high-contention adapter의 `listOf(JobMigration.classpath("001", "db/job-console/V001__job_console.sql"), JobMigration.classpath("002", "db/job-console/V002__bounded_wait_http_idempotency.sql"))`를 동일하게 유지한다. migration runner의 advisory lock/ordering/checksum 구현은 복제하지 않는다.

- [ ] **Step 4: migration test를 GREEN으로 만들고 schema plan을 확인한다.** 실행: `./gradlew :operations-job-console-core:integrationTest --tests '*JobSubmissionIdempotencyMigrationTest'`. 예상 결과: V001 row 보존, V002 rerun no-op, checksum mismatch failure, `\d+ job_request_waiters`/constraint/index assertion PASS. `EXPLAIN (ANALYZE, BUFFERS)` query shape와 planner assertion은 Task 8에서 고정하며 이 단계에서는 index 존재만 검증한다.

---

## 4. Task 3 — RED/GREEN: PostgreSQL 상태 repository와 transaction 경계

**Files:**

- Create: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyRepository.kt`
- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobRepository.kt`
- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobTables.kt`
- Test: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyRepositoryTest.kt`
- Test: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyMultiInstanceTest.kt`

- [ ] **Step 1: transaction/상태 전이 RED test를 작성한다.** 같은 full scope tuple과 fingerprint를 두 coordinator instance가 동시에 reserve하면 owner 한 개만 생기고 나머지는 waiter 또는 overflow가 된다. 다른 fingerprint는 `Conflict`, retention exact boundary에서는 한 owner만 generation을 증가시키며, stale token finalize는 job/outbox/history/snapshot을 만들지 못해야 한다. test는 각 phase의 connection count와 lock hold를 instrumentation datasource로 관찰한다.

- [ ] **Step 2: repository transaction API를 고정한다.** 구현할 내부 함수는 아래 의미를 유지하고 application clock을 SQL 비교에 사용하지 않는다.

  ```kotlin
  internal interface JobSubmissionIdempotencyRepository {
      fun reserve(command: JobSubmissionCommand, now: Instant): Reservation
      fun registerWaiter(ownership: InFlightOwnership, now: Instant): WaiterRegistration
      fun poll(scope: DemoCallerScope, keyHash: String, generation: Long, now: Instant): PollResult
      fun finalizeOwner(
          ownership: JobSubmissionOwnership,
          prepared: PreparedJobSubmission,
          now: Instant,
      ): ReplayableJobSubmission
      fun abandon(ownership: JobSubmissionOwnership, reason: AbandonReason, now: Instant): Boolean
      fun cleanupExpired(now: Instant, batchSize: Int = 100): CleanupReport
  }
  ```

  `reserve`, `registerWaiter`, `poll`, `finalizeOwner`, `abandon`, `cleanupExpired` 각각은 connection을 열고 짧게 commit/rollback한다. interface의 `now`는 fake repository가 virtual-time unit test를 구동할 때만 사용하고, JDBC implementation은 모든 lease/retention/expiry 비교에서 PostgreSQL `CURRENT_TIMESTAMP`를 사용한다. `PreparedJobSubmission` 준비나 polling sleep은 repository transaction 안에 들어가지 않는다. `finalizeOwner`는 `WHERE tenant_id=? AND submitter_hash=? AND key_hash=? AND state='IN_FLIGHT' AND generation=? AND owner_token=?`를 모든 job/request update에 포함하고, job·outbox·history·response snapshot을 한 JDBC transaction에서 기록한다.

  모든 상태 변경은 `job_requests` full-scope row lock → 같은 generation의 expired waiter delete → active waiter count → waiter insert 또는 generation CAS 순서로 lock을 잡는다. janitor와 GC도 request parent row를 먼저 `FOR UPDATE SKIP LOCKED`한 뒤 child waiter delete와 retention/abandoned 판정을 수행하며, takeover는 child row를 먼저 잠그지 않는다. 이 단일 lock order를 reserve/register/takeover/janitor SQL에 그대로 사용한다.

  legacy `TERMINAL` row의 `response_status IS NULL`은 `loadLegacySnapshot`에서 현재 `JobSnapshot`을 한 번 읽어 snapshot policy를 검증한 뒤 `WHERE full_scope_tuple AND state='TERMINAL' AND generation=? AND response_status IS NULL` CAS로 채운다. CAS loser는 저장된 bytes를 다시 읽고, job이 없거나 unsafe이면 `idempotency_snapshot_rejected`를 반환하며 row 삭제/자동 재실행을 하지 않는다.

- [ ] **Step 3: 기존 submit primitive를 분해한다.** `JobRepository.submit`의 key advisory lock과 일괄 생성 경로를 제거하고, queue sequence/job insert/outbox/history를 coordinator가 넘긴 `Connection`에서 수행하는 internal `insertSubmittedJob(connection, scope, request, jobId, enqueueSequence, now)`로 추출한다. `JobRepository` 밖에 SQL을 복제하지 않으며 기존 worker/lifecycle/claim API는 그대로 둔다. 모든 call site를 `rg 'repository\.submit|service\.submit'`로 이전한다.

- [ ] **Step 4: GREEN 통합 검증을 실행한다.** 실행(순차):

  ```bash
  ./gradlew :operations-job-console-core:integrationTest --tests '*JobSubmissionIdempotencyRepositoryTest'
  ./gradlew :operations-job-console-core:integrationTest --tests '*JobSubmissionIdempotencyMultiInstanceTest'
  ```

  예상 결과: atomic commit/rollback, global waiter cap, generation/lease CAS, stale owner rejection, cross-key isolation PASS. 실패 시 다음 task로 넘어가지 않고 SQL/transaction 경계를 먼저 수정한다.

---

## 5. Task 4 — RED/GREEN: bounded-wait coordinator와 cancellation

**Files:**

- Create: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyCoordinator.kt`
- Create: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionSnapshotPolicy.kt`
- Create: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyObservability.kt`
- Test: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyCoordinatorTest.kt`
- Test: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionSnapshotPolicyTest.kt`

- [ ] **Step 1: deterministic virtual-time RED test를 작성한다.** reservation classification, waiter count 0/1/2/3, 25→50→100ms polling, 2초 timeout, 30초 lease takeover, 10초 prepare deadline, retention 직전/정확히/직후, `ABANDONED` drain 후 generation 증가를 fake repository/clock으로 고정한다. owner prepare permit은 Reserve 전에 획득해야 하며 8개가 모두 사용 중이면 request/waiter row 없이 429가 되는지 확인한다. `action.prepare`와 waiter loop에서 repository connection count가 0인지 assertion한다. 각 취소 장벽을 별도 test로 둔다: Reserve 직전 취소=무행, Prepare 중 취소=`ABANDONED`, Poll 중 취소=waiter row 삭제, Finalize 중 취소=transaction rollback. owner/waiter token이 재사용되지 않고 scope 위조가 거절되며 local policy 누락·범위 오류가 startup에서 fail-closed인지 확인한다.

- [ ] **Step 2: owner action API와 orchestration을 구현한다.** 아래 interface를 package-internal로 두고 generic endpoint SPI로 공개하지 않는다.

  ```kotlin
  internal interface JobSubmissionOwnerAction {
      fun prepare(ownership: JobSubmissionOwnership): PreparedJobSubmission
      fun commit(
          connection: Connection,
          ownership: JobSubmissionOwnership,
          prepared: PreparedJobSubmission,
      ): ReplayableJobSubmission
  }

  internal interface JobSubmissionClock {
      fun databaseNow(): Instant
      fun monotonicNanos(): Long
  }

  internal interface InterruptibleWaitStrategy {
      fun await(interval: Duration)
  }

  internal class JobSubmissionIdempotencyCoordinator(
      private val repository: JobSubmissionIdempotencyRepository,
      private val policy: JobSubmissionIdempotencyPolicy,
      private val clock: JobSubmissionClock,
      private val waiter: InterruptibleWaitStrategy,
  ) {
      fun execute(command: JobSubmissionCommand, action: JobSubmissionOwnerAction): JobSubmissionOutcome
  }
  ```

  owner prepare permit을 먼저 `try/finally`로 획득/반납한 뒤 Reserve를 수행하고, 그 다음 Reserve → connection-free `prepare` → token/generation 재검증 Finalize 순서를 지킨다. owner lease는 30초, prepare deadline은 10초로 고정하고 V1에서는 heartbeat/renewal을 사용하지 않는다. DB semaphore, owner prepare semaphore, instance waiter permit은 모든 정상/예외/취소 경로에서 `finally`로 반환한다. waiter는 자기 token을 `finally`에서 삭제하고, cancellation은 broad `Throwable` mapping 전에 재전파한다. owner prepare 실패/취소는 `ABANDONED` CAS를 한 번 시도하며, PostgreSQL 장애 시 lease expiry takeover가 남은 복구 경로다. Ktor cleanup은 `withContext(NonCancellable)`에서 bounded하게 실행하고 Spring interruption cleanup은 같은 finally 경계를 사용한다. `ABANDONED`에는 항상 `abandoned_until = now + 1 minute`를 기록하고 janitor가 current-generation waiter와 경쟁해 삭제하지 않도록 한다.

- [ ] **Step 3: snapshot 안전성 test를 GREEN으로 만든다.** production 202 response는 allowlisted headers/status/content type/body bytes만 저장한다. `Authorization`, `Cookie`, `Set-Cookie`, `X-Demo-*`, CRLF/control character, case-insensitive duplicate/reserved header, oversized body/headers는 저장 전에 거절하고 finalize transaction을 rollback한다. production에서는 validation 4xx, conflict, timeout, overflow, abandon을 terminal cache하지 않는다. test-only fixture만 synthetic 201/422 replay snapshot을 허용한다.

  connection permit은 250ms 안에 얻지 못하면 request row/waiter row를 만들지 않고 `idempotency_waiters_exceeded`/429와 `Retry-After: 2`를 반환한다. 이미 등록된 waiter의 poll은 permit을 기다리되 2초 HTTP deadline을 넘기지 않는다.

- [ ] **Step 4: coordinator test를 실행한다.** 실행: `./gradlew :operations-job-console-core:test --tests '*JobSubmissionIdempotencyCoordinatorTest' --tests '*JobSubmissionSnapshotPolicyTest'`. 예상 결과: 모든 virtual-time, cancellation, stale owner, snapshot negative test PASS. 이 task 완료 전에는 HTTP adapter를 연결하지 않는다.

---

## 6. Task 5 — RED/GREEN: service outcome와 공통 HTTP problem/response mapping

**Files:**

- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/api/JobApiModels.kt`
- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/application/JobConsoleService.kt`
- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/domain/JobModels.kt`
- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobRepository.kt`
- Test: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/application/JobConsoleSubmissionOutcomeTest.kt`
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/JobConsoleContract.kt`
- Create: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/BoundedWaitHttpIdempotencyFixture.kt`

- [ ] **Step 1: 기존 service caller를 보존하면서 replay bit를 검증하는 RED test를 작성한다.** 동일 요청의 first/replay가 같은 status/body/content type을 만들고, mapper가 replay 전달 시에만 `Idempotency-Replayed: true`를 추가해야 한다. `JobConsoleService.submit`은 `JobSubmissionOutcome`을 반환하되 GET/cancel/queue caller는 기존 동작을 유지한다. duplicate job/outbox/history는 fixture query로 확인한다.

- [ ] **Step 2: API 모델과 mapper를 구현한다.** `JobSubmissionHttpResponse(status, body, contentType, headers, replayed)`와 stable `JobProblem`을 core contract로 만들고 problem code를 다음처럼 매핑한다: conflict `idempotency_key_reused`, timeout `idempotency_in_flight`, overflow `idempotency_waiters_exceeded`, malformed key/body/header `invalid_idempotency_request`, oversized body `idempotency_request_too_large`, unsafe snapshot `idempotency_snapshot_rejected`, owner/DB failure `dependency_unavailable`. `Retry-After`는 delta-seconds 정수로만 작성하고 request id만 redacted field로 유지한다.

  ```kotlin
  internal data class JobSubmissionHttpResponse(
      val status: Int,
      val body: ByteArray,
      val contentType: String,
      val headers: Map<String, List<String>>,
      val replayed: Boolean,
  )
  ```

  mapper test의 canonical matrix는 다음 값으로 고정한다. 모든 problem response는 `Content-Type: application/problem+json`이고 `Idempotency-Replayed`가 없으며, `requestId`만 매 요청 새 UUIDv7이다.

  | Status | `code` | `title` | `retryAfterSeconds` | Header |
  |---:|---|---|---:|---|
  | 400 | `invalid_idempotency_request` | `Bad Request` | `null` | 없음 |
  | 413 | `idempotency_request_too_large` | `Payload Too Large` | `null` | 없음 |
  | 409 | `idempotency_key_reused` | `Conflict` | `null` | 없음 |
  | 409 | `idempotency_in_flight` | `Conflict` | `1` | `Retry-After: 1` |
  | 429 | `idempotency_waiters_exceeded` | `Too Many Requests` | `2` | `Retry-After: 2` |
  | 500 | `idempotency_snapshot_rejected` | `Internal Server Error` | `null` | 없음 |
  | 503 | `dependency_unavailable` | `Service Unavailable` | `null` | 없음 |

  timeout assertion body는 `{"status":409,"code":"idempotency_in_flight","title":"Conflict","requestId":"0198af23-7b9c-7000-8000-000000000003","retryAfterSeconds":1}` 형태로 직렬화하고, fixture에서는 `requestId`만 UUIDv7 정규식으로 검증한다. 나머지 bytes와 field order를 고정한다. production 202 body는 `JobSnapshot` canonical JSON이고 first header는 `Idempotency-Replayed: false`, terminal replay header는 `true`다. `Abandoned`, owner prepare failure/cancellation, transient PostgreSQL failure는 모두 503 `dependency_unavailable`, `retryAfterSeconds=null`, problem content type, replay header 없음으로 매핑하고 terminal snapshot을 만들지 않는다. caller는 같은 key와 canonical payload로 재시도한다. coordinator 바깥의 기존 scope guard는 `403 Forbidden`, `scope_denied`, `retryAfterSeconds=null`, 추가 header 없음, 자동 재시도 금지로 고정하며 idempotency matrix와 별도 행으로 문서화한다.

  모든 adapter의 공통 ingress precedence는 `scope resolver → Idempotency-Key 정확히 1회/형식 검증 → Content-Type과 streaming body bound → strict JSON → canonical fingerprint/lookup`이다. 따라서 잘못된 key와 oversized body가 함께 오면 두 adapter 모두 400 `invalid_idempotency_request`를 반환하고 body를 소비하거나 lookup하지 않는다. 이 precedence와 `scope_denied` 행은 shared fixture와 Spring/Ktor 혼합 오류 테스트로 고정한다.

- [ ] **Step 3: core unit/fixture test를 GREEN으로 실행한다.** 실행: `./gradlew :operations-job-console-core:test --tests '*JobConsoleSubmissionOutcomeTest' --tests '*JobConsoleContract*'`. 예상 결과: first/replay/header/problem schema와 기존 UI/lifecycle test PASS. upstream conformance 호출은 framework task에서 한다.

- [ ] **Step 4: adapter 선행 fixture 계약을 고정한다.** `BoundedWaitHttpIdempotencyFixture`의 request builder, response recorder, virtual clock, `beforeFinalizeCommit`/`afterCommitBeforeResponse` barrier, side-effect counter, reset/await hook을 testFixtures에 먼저 만든다. wait timeout 2s, scenario 15s, max waiters 2, retention 1h, retry-after 1/2s, key 255 bytes, body/replay 64KiB, header names 8, values 4, value 4KiB, aggregate 16KiB를 immutable configuration으로 노출하고 upstream assertion은 호출하지 않는다. 이 단계가 PASS한 뒤에만 Spring/Ktor adapter가 fixture를 소비한다.

---

## 7. Task 6 — Spring MVC adapter와 live production/test-only HTTP host

**Files:**

- Modify: `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/JobConsoleSpringConfiguration.kt`
- Modify: `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/JobConsoleSpringController.kt`
- Modify: `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/JobConsoleProblemHandler.kt`
- Create: `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/JobConsoleSpringLifecycle.kt`
- No change: `operations/job-console-spring/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/JobConsoleSpringApplication.kt` — virtual-thread property는 `application.properties`에서 고정한다.
- Create: `operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringJobSubmissionIdempotencyHttpTest.kt`
- Modify: `operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringJobConsoleProfileApplication.kt`

- [ ] **Step 1: Spring HTTP RED test를 작성한다.** `MockMvc`가 아닌 실제 profile server를 통해 공통 precedence인 trusted demo scope resolver → exact key validation → request size/content-type/strict JSON validation → service outcome → response mapper 순서를 확인한다. first/replay는 202와 동일 JSON을 반환하고 replay에만 `Idempotency-Replayed: true`를 보낸다. malformed key와 oversized body를 함께 보낸 경우 400이 413보다 우선하고, invalid scope는 body/key를 소비하지 않는 403 `scope_denied`가 되는 혼합 오류, timeout/overflow/abandon `JobProblem`, `Retry-After`, production profile의 test-only route 404를 먼저 고정한다.

- [ ] **Step 2: Spring adapter를 구현한다.** 공통 precedence인 `scope resolver → Idempotency-Key 정확히 1회/형식 검증 → Content-Type과 streaming body bound → strict JSON → canonical fingerprint/lookup`을 적용한다. `JobConsoleSpringController.submit`이 먼저 trusted scope resolver를 실행해 immutable `(tenantId, submitterHash)`를 확정하고, scope가 유효하지 않으면 body/key를 읽거나 hash/lookup하지 않고 즉시 기존 `403 Forbidden`/`scope_denied`를 반환한다. 그 다음 `Idempotency-Key`를 case-insensitive로 정확히 한 번 읽어 ASCII `0x21..0x7E`/255-byte 조건과 comma-joined value를 확인한다. `SpringBoundedJsonBodyReader.read(request: HttpServletRequest): SubmitJobRequest`는 `request.inputStream`을 chunk 단위로 읽어 64 KiB를 초과하는 순간 `idempotency_request_too_large`/413을 반환하고, `Content-Type`의 media type과 charset을 먼저 검사해 `application/json` 및 UTF-8만 통과시킨다. strict Jackson 3 parser로 duplicate/unknown/trailing/depth/scalar 오류를 `invalid_idempotency_request`/400으로 거절한 뒤 typed `SubmitJobRequest`만 service에 넘긴다. `spring.threads.virtual.enabled=true`를 production/test profile property로 고정하고 caller-owned virtual thread에서 blocking coordinator를 호출하며 interruption을 `JobProblem`으로 덮어쓰지 않는다. `JobConsoleProblemHandler`는 coordinator outcome별 exact status/body/header를 단일 mapper로 반환한다.

- [ ] **Step 3: Spring configuration/lifecycle을 연결한다.** `operations/job-console-spring/src/main/resources/application.properties`에 `spring.threads.virtual.enabled=true`와 disabled-by-default `job-console.bounded-wait.enabled=false`를 기록하고, `JobConsoleSpringConfiguration`에서 V002 migration list, policy bean, coordinator/repository bean, per-instance waiter 32, datasource pool 8, idempotency DB semaphore 4, owner prepare cap 8, janitor 1분/100건을 명시적으로 구성한다. bounded-wait beans에는 `@ConditionalOnProperty` guard와 migration 완료 후 등록 순서를 적용하고, disabled profile에서 bean/route가 생성되지 않는 registration-order test를 둔다. `JobConsoleSpringLifecycle`은 janitor, worker scheduler, outbox scheduler와 active owner/waiter handle을 소유하고 `stop()`에서 readiness close → 신규 admission 차단 → 최대 5초 `join`/bounded await → 남은 owner `ABANDONED` CAS → lifecycle이 직접 생성한 executor/Redis/DataSource만 한 번 닫는 순서를 보장한다. Spring container-owned resource는 중복 close하지 않도록 ownership callback과 idempotent shutdown test를 둔다. readiness는 migration/PostgreSQL/policy mismatch만 DOWN으로 표시한다.

- [ ] **Step 4: upstream conformance를 실제 HTTP 경계에서 실행한다.** `bluetape4k-junit5:1.12.1`의 `BoundedWaitHttpIdempotencyAdapter`를 Spring test fixture의 test-only `POST /__test/v1/jobs` route에 연결해 `assertBoundedWaitHttpIdempotencyConformance` 전체 시나리오를 실행한다. 이 route는 `src/test` application에만 등록하고 일반 `JobConsoleSpringApplication`에는 bean/route를 만들지 않는다. upstream assertion을 복제하지 않고 production durability/duplicate query만 별도 assertion으로 둔다.

- [ ] **Step 5: Spring tests를 GREEN으로 확인한다.** blocked owner/waiter에서 client disconnect를 발생시키고 waiter row=0, owner permit/DB permit 반환, `activeConnections=0`을 확인한다. finalize 직전 barrier에서 disconnect하면 503/`ABANDONED`/무효과이고, commit 직후 response-delivery barrier에서 disconnect하면 다음 같은 key/payload retry가 단일 job/outbox/history와 `Idempotency-Replayed: true`로 수렴하는 assertion을 추가한다. 실행(순차):

  ```bash
  ./gradlew :operations-job-console-spring:test --tests '*SpringJobSubmissionIdempotencyHttpTest'
  ./gradlew :operations-job-console-spring:integrationTest --tests '*SpringJobSubmissionIdempotencyHttpTest'
  ```

  예상 결과: conformance, first/replay, cancellation, profile route absence, duplicate persistence, shutdown quiescence PASS.

---

## 8. Task 7 — Ktor adapter와 coroutine lifecycle

**Files:**

- Modify: `operations/job-console-ktor/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/JobConsoleKtorApplication.kt`
- Create: `operations/job-console-ktor/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/JobSubmissionKtorHttp.kt`
- Create: `operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/KtorJobSubmissionIdempotencyHttpTest.kt`
- Modify: `operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/KtorJobConsoleProfileServer.kt`

- [ ] **Step 1: Ktor HTTP RED test를 작성한다.** `testApplication` 또는 기존 profile server의 실제 Netty route로 Spring과 같은 공통 ingress precedence, first/replay/problem/conformance/cancellation/route absence matrix를 실행한다. malformed key와 oversized body의 결과가 Spring과 동일한 400 `invalid_idempotency_request`이고 invalid scope가 403 `scope_denied`인지 확인한다. coroutine cancellation은 `CancellationException` 그대로 전파되고 waiter row가 `finally`에서 삭제되는지 DB에서 확인한다.

- [ ] **Step 2: bounded body/strict JSON와 response mapper를 구현한다.** 공통 precedence인 `scope resolver → Idempotency-Key 정확히 1회/형식 검증 → Content-Type과 streaming body bound → strict JSON → canonical fingerprint/lookup`을 그대로 적용한다. `call.demoScope()` trusted scope resolver를 body/channel을 읽기 전에 실행해 scope가 유효하지 않으면 key hash/lookup과 body parsing 없이 기존 `403 Forbidden`/`scope_denied`를 반환한다. 잘못된 key와 oversized body가 함께 오면 key 검증이 먼저 실행되어 400 `invalid_idempotency_request`를 반환한다. request body는 64 KiB를 초과하기 전에 중단해 `idempotency_request_too_large`/413을 반환하고, `Content-Type`이 `application/json`/UTF-8인지 확인한다. strict Jackson 3 parser로 duplicate/unknown/trailing token/depth/scalar 제한을 적용해 malformed input을 `invalid_idempotency_request`/400으로 매핑한다. `Idempotency-Key`는 case-insensitive header를 정확히 한 번만 허용하고 ASCII `0x21..0x7E`, 255-byte, non-comma 조건을 적용한다. `withContext(Dispatchers.IO)`가 아니라 coordinator의 blocking call을 `runInterruptible(Dispatchers.IO)`로 감싸고, cancellation 이후 broad `Throwable` status mapping을 수행하지 않는다.

- [ ] **Step 3: Ktor datasource 및 background lifecycle을 연결한다.** main과 profile test Hikari `maximumPoolSize = 8`을 고정하고 V002 migration, coordinator bounds, janitor coroutine을 등록한다. `JobConsoleKtorRuntime`이 `janitorJob`, `outboxJob`, `workerJob`, coordinator scope와 datasource를 명시적으로 소유하며 `ApplicationStopped`에서 readiness close → 신규 admission 차단 → 각 job의 `cancelAndJoin` 또는 5초 bounded await → remaining owner `ABANDONED` CAS → Redis/Hikari close 순서를 지킨다. outbox/worker job은 기존 cancellation contract를 회귀시키지 않는다.

- [ ] **Step 4: upstream conformance와 Ktor 고유 test를 GREEN으로 실행한다.** `src/test` application에만 test-only `POST /__test/v1/jobs` route를 등록하고 일반 `jobConsoleModule`에는 route를 만들지 않는다. blocked owner/waiter client cancellation에서 `NonCancellable` cleanup 후 waiter row=0, semaphore 반환, Hikari active connection=0을 확인한다. finalize 직전/commit 직후 response-delivery barrier에서 disconnect한 뒤 같은 key/payload retry가 각각 `ABANDONED`/503 또는 stable terminal replay로 수렴하는지도 검증한다. 실행(순차):

  ```bash
  ./gradlew :operations-job-console-ktor:test --tests '*KtorJobSubmissionIdempotencyHttpTest'
  ./gradlew :operations-job-console-ktor:integrationTest --tests '*KtorJobSubmissionIdempotencyHttpTest'
  ```

  예상 결과: conformance 전체, production/test-only route 분리, cancellation/cleanup, pool/readiness/shutdown assertion PASS.

---

## 9. Task 8 — PostgreSQL multi-instance, recovery, janitor, performance evidence

**Files:**

- Modify: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyMultiInstanceTest.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyRecoveryTest.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyQueryPlanTest.kt`
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyLockOrderTest.kt`
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/JobConsoleHighContentionAdapter.kt`
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/JobConsoleContainerFixture.kt`

- [ ] **Step 1: multi-instance RED scenarios를 고정한다.** 두 datasource/coordinator가 동일 key에 fan-in 32를 만들고 database waiter cap 2만 허용하는지, 33번째 instance waiter가 `idempotency_waiters_exceeded`/429이고 row를 만들지 않는지, 다른 key가 서로 blocking되지 않는지, owner crash/lease expiry/late stale finalize가 단일 terminal row와 zero duplicate job/outbox/history로 수렴하는지 기록한다. idempotency DB semaphore가 4개를 넘지 않고 connection permit을 250ms 안에 얻지 못한 신규 admission이 429/no-row가 되며, 등록된 waiter poll이 JDBC statement 500ms bound를 넘지 않는 negative scenario도 추가한다. admission–janitor, takeover–GC를 동시에 반복하고 `pg_locks`/`pg_stat_activity`를 수집해 deadlock 없이 lock timeout 또는 bounded retry로 progress하는지 확인한다.

- [ ] **Step 2: recovery/GC를 구현·검증한다.** waiter cancellation/timeout/overflow/failure의 row quiescence, `ABANDONED` current-generation waiter drain, `abandoned_until = now + 1 minute`, retention terminal cleanup, janitor batch max 100, request/waiter race-safe delete를 test한다. owner lease 30초와 prepare deadline 10초를 같은 transaction time source로 비교한다. legacy snapshot의 orphan job과 unsafe response는 fail-closed하고 자동 재실행하지 않으며, concurrent legacy lazy snapshot CAS loser가 저장된 bytes를 재사용하는지 확인한다.

- [ ] **Step 3: index/statement/resource evidence를 수집한다.** `JobSubmissionIdempotencyQueryPlanTest`가 충분한 seed row를 삽입하고 `ANALYZE job_requests; ANALYZE job_request_waiters;`를 실행한 뒤 planner 설정을 바꾸지 않은 상태에서 다음 exact prepared query를 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 실행한다: (a) full scope + generation + `expires_at > CURRENT_TIMESTAMP` waiter admission count, (b) `state='TERMINAL' AND retained_until <= CURRENT_TIMESTAMP` cleanup, (c) `state='ABANDONED' AND abandoned_until <= CURRENT_TIMESTAMP` cleanup. JSON plan artifact를 `build/reports/job-console-idempotency/query-plans.json`에 저장하고, admission plan node가 `ix_job_request_waiters_admission`, retention plan node가 `ix_job_requests_terminal_retention`, abandoned plan node가 `ix_job_requests_abandoned_until`을 사용하며 `Seq Scan`이 없는지 assert한다. 기존 fixture의 `SET enable_seqscan = off` helper는 재사용하지 않는다. execute/round-trip probe는 replay/conflict 1 read, waiter cleanup 1 delete, admission 최대 3 statement, finalize 최대 8 statement를 outcome별로 세고, key/stale waiter 수를 늘려도 statement 수가 증가하지 않는 O(1) assertion을 journal로 남긴다. Hikari pool 8, coordinator DB semaphore 4, owner prepare 8, per-instance waiter 32, connection permit 250ms, JDBC statement 500ms, two-poll-interval convergence도 같은 artifact에 기록한다. throughput/p95/capacity와 JVM allocation/GC benchmark는 #737 범위의 N/A이며 주장하지 않는다.

- [ ] **Step 4: high-contention regression을 순차 실행한다.** 실행:

  ```bash
  ./gradlew :operations-job-console-core:integrationTest --tests '*JobSubmissionIdempotencyRecoveryTest' --tests '*JobSubmissionIdempotencyQueryPlanTest'
  HIGH_CONTENTION_RUN_ID=issue-737-plan-$(date +%s) ./scripts/smoke-validate.sh high-contention-ci
  ```

  `smoke-validate.sh`가 내부에서 `./gradlew -x detekt highContentionCi -PhighContentionRunId="$HIGH_CONTENTION_RUN_ID" --max-workers=1`를 실행하고 `build/reports/high-contention/$HIGH_CONTENTION_RUN_ID/`를 생성한다. 실행 후 `find build/reports/high-contention/issue-737-plan-* -maxdepth 2 -type f -print`로 report/read-back을 확인한다. 예상 결과: state machine invariant, rollback/GC, query plan, bounded resource evidence와 기존 UUIDv7/lifecycle/heartbeat/queue/high-contention contract PASS. Testcontainers 작업은 다른 module과 병렬화하지 않는다.

---

## 10. Task 9 — rollout/rollback/readiness/observability와 compatibility matrix

**Files:**

- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/observability/JobConsoleObservability.kt`
- Modify: `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/idempotency/JobSubmissionIdempotencyObservability.kt`
- Modify: Spring/Ktor configuration files from Tasks 6–7
- Create: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/observability/JobSubmissionIdempotencyObservabilityTest.kt`
- Modify: `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobSubmissionIdempotencyMigrationTest.kt`

- [ ] **Step 1: metric/readiness RED test를 작성한다.** outcome label은 `owner|replay|conflict|timeout|overflow|abandon|recovery`만 허용하고 tenant, submitter, raw/hashed key, fingerprint, job id, body, response header가 label/log에 들어가지 않는지 검사한다. readiness reason은 `postgres|migration|policy`만 허용하며 Redis degraded는 ready를 내리지 않는다.

- [ ] **Step 2: rollout matrix를 코드/fixture로 고정한다.** `bounded-wait.enabled=false`는 구 binary-compatible terminal insert를 유지하고, V002 적용 후 all-new binary에서만 enable한다. old binary와 enabled new binary의 동시 traffic은 unsupported로 명시하고 integration test가 startup/feature gate에서 거절하는지 확인한다. 필수 policy 누락·범위 오류와 instance 간 policy fingerprint 불일치는 startup/readiness에서 fail-closed하며, 동일 fingerprint가 아니면 readiness DOWN이다.

- [ ] **Step 3: binary rollback drain 진단을 구현한다.** raw scope/key를 출력하지 않는 `IN_FLIGHT=0 && active_waiters=0` 진단을 제공한다. operator read-back은 `SELECT count(*) FILTER (WHERE state = 'IN_FLIGHT') AS in_flight, (SELECT count(*) FROM job_request_waiters WHERE expires_at > CURRENT_TIMESTAMP) AS active_waiters FROM job_requests;`처럼 집계만 반환하고 key/body/token을 출력하지 않는다. readiness close → 신규 admission 중지 → 30초 lease + 5초 drain → 남은 owner abandon CAS → waiter 0 확인 → disabled new binary smoke 순서를 README와 test fixture에서 동일하게 사용한다. schema rollback은 수행하지 않는다.

- [ ] **Step 4: observability/compatibility test를 실행한다.** 실행: `./gradlew :operations-job-console-core:test --tests '*JobSubmissionIdempotencyObservabilityTest'` 및 Task 6–8의 migration/HTTP integration test. 예상 결과: safe labels, readiness, disabled/enabled/mixed-version/rollback precondition PASS.

---

## 11. Task 10 — upstream conformance adapter와 의존성 정리

**Files:**

- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/JobConsoleContract.kt`
- Modify: `operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring/SpringJobSubmissionIdempotencyHttpTest.kt`
- Modify: `operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor/KtorJobSubmissionIdempotencyHttpTest.kt`
- No change expected: `operations/job-console-core/build.gradle.kts`, `operations/job-console-spring/build.gradle.kts`, `operations/job-console-ktor/build.gradle.kts` — current test dependencies already expose the root-BOM-resolved conformance artifact; dependencyInsight is the gate before any catalog edit.

- [ ] **Step 1: conformance integration RED test를 작성한다.** Task 5에서 만든 `BoundedWaitHttpIdempotencyFixture`를 Spring/Ktor adapter에 연결하고 test-only endpoint만 synthetic 201/422를 replay하도록 고정한다. 같은 fixture를 두 framework가 소비하는지 compile-time wiring과 barrier별 response-loss test로 확인하며 upstream assertion은 이 단계에서 처음 호출한다.

- [ ] **Step 2: dependency resolution을 확인한다.** root BOM `platform(libs.bluetape4k.dependencies)`만 사용하고 `./gradlew :operations-job-console-core:dependencyInsight --dependency bluetape4k-junit5 --configuration testRuntimeClasspath --no-daemon --console=plain`에서 `1.12.1`과 `assertBoundedWaitHttpIdempotencyConformance`를 확인한다. catalog alias에 explicit version이나 개별 Bluetape BOM을 추가하지 않는다.

- [ ] **Step 3: fixture 중복을 제거하고 GREEN으로 확인한다.** upstream assertion을 framework별로 복제하지 않고 request builder/response recorder/barrier/clock은 Task 5의 core testFixtures만 사용한다. 실행: `./gradlew :operations-job-console-core:test :operations-job-console-spring:test :operations-job-console-ktor:test --no-parallel --max-workers=1`. 예상 결과: 세 adapter의 conformance helper compile, shared fixture wiring, unit fixture PASS.

---

## 12. Task 11 — 문서와 bilingual diagram

**Files:**

- Modify: `operations/job-console-core/README.md`
- Modify: `operations/job-console-core/README.ko.md`
- Modify: `operations/job-console-spring/README.md`
- Modify: `operations/job-console-spring/README.ko.md`
- Modify: `operations/job-console-ktor/README.md`
- Modify: `operations/job-console-ktor/README.ko.md`
- Create: `docs/images/readme-diagrams/operations-job-console-bounded-wait-idempotency-01.svg`
- Create: `docs/images/readme-diagrams/operations-job-console-bounded-wait-idempotency-01.ko.svg`
- Create: matching `*.png` 2x renders
- Modify only if required: existing README validator/stale-check scripts

- [ ] **Step 1: 문서 RED parity checklist를 만든다.** 세 module의 영문/한글 문서에서 first/replay/conflict/timeout/overflow/abandon/recovery 예제, exact status/header/body/problem schema, caller retry decision table, policy defaults, PostgreSQL authority, legacy lazy snapshot, binary rollback drain, exactly-once 비보장을 같은 순서·행 수로 비교한다. request precondition 표에는 trusted scope와 기존 403 `scope_denied`, `Idempotency-Key` 정확히 1회/ASCII/255 bytes, UTF-8 `application/json`, 64 KiB streaming limit, 공통 오류 precedence를 포함하고 400/413/500/503 재시도 결정을 고정한다. test-only endpoint는 public usage로 안내하지 않는다.

- [ ] **Step 2: README를 작성한다.** 영어/한국어 모두 “저장된 PostgreSQL job/outbox/history와 terminal HTTP snapshot까지만 보장하고 caller 외부 side effect와 background job exactly-once는 보장하지 않는다”를 계약 표 바로 아래에 둔다. 예제의 raw key/body/credential header를 그대로 노출하지 않고 `Idempotency-Key: demo-001`처럼 안전한 placeholder만 쓴다. Korean prose는 `$bluetape-writer` naturalness checklist의 KO-01~KO-06을 적용한다.

- [ ] **Step 3: diagram을 생성·검증한다.** `$bluetape-diagram`으로 PostgreSQL authority, Reserve/Prepare/Finalize, owner/waiter/replay/conflict/abandon branch를 표현한 sequence/state diagram을 영문과 한글로 각각 만든다. canonical SVG와 2x PNG를 보존하고 XML validity, text hazard, connector endpoint/geometry, sequence style, full-size PNG 육안 검사를 수행한다.

- [ ] **Step 4: 문서 검증을 실행한다.** 실행: `git diff --check`, 기존 README validator/stale-check와 `./gradlew :operations-job-console-core:test`를 실행한다. 예상 결과: locale parity, 링크/코드 fence/예제 JSON, diagram asset 경로 PASS. parity가 깨지면 source code 변경을 다시 열지 않고 문서 lane에서 수정한다.

---

## 13. Task 12 — workflow/catalog/stale-check 감사

**Files:**

- Read and record: `.github/workflows/ci.yml`, `.github/workflows/nightly.yml`, `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`, `settings.gradle.kts`, `build.gradle.kts`
- Modify only when evidence shows omission: the above workflow/script/catalog files

- [ ] **Step 1: module/task inventory를 확인한다.** `settings.gradle.kts`의 authoritative `operations-job-console-core|spring|ktor` 등록과 root BOM import를 읽고, 새 module/dependency가 없음을 증명한다. `rg`로 `test`, `integrationTest`, `detekt`, build aggregation과 final summary `needs`를 찾는다.

- [ ] **Step 2: workflow RED gap을 분류한다.** 세 module의 unit/integration/migration/high-contention task가 smoke/full/nightly에서 실제 실행되는지 확인한다. 이미 포함되어 있으면 파일을 수정하지 않고 audit note와 command evidence만 남긴다. 누락된 경우에만 같은 branch에서 matrix와 final aggregator를 최소 수정하고 stale-check에 task/module 목록을 추가한다.

- [ ] **Step 3: workflow validation을 실행한다.** 실행: `./gradlew :operations-job-console-core:build :operations-job-console-spring:build :operations-job-console-ktor:build detekt --no-parallel --max-workers=1`와 workflow YAML/script validator. 예상 결과: explicit Bluetape version/BOM duplication 없음, 세 module build/detekt PASS, intended integration job이 skipped가 아님을 확인한다.

---

## 14. Task 13 — 전체 검증, diff review, 구현 완료 증거

**Files:**

- Read: 모든 변경 파일, approved spec, this plan
- Modify: 실패한 구현/test/doc 파일만 해당 lane에서 수정

- [ ] **Step 1: targeted TDD 명령을 순차 실행한다.** 다음 순서를 지키고 각 결과를 저장한다.

  ```bash
  ./gradlew :operations-job-console-core:test
  ./gradlew :operations-job-console-core:integrationTest
  ./gradlew :operations-job-console-spring:test
  ./gradlew :operations-job-console-spring:integrationTest
  ./gradlew :operations-job-console-ktor:test
  ./gradlew :operations-job-console-ktor:integrationTest
  ./gradlew detekt
  ./gradlew :operations-job-console-core:build :operations-job-console-spring:build :operations-job-console-ktor:build
  ```

  Docker-backed command는 macOS Colima context와 inherited `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 먼저 확인한다. healthy Colima를 재시작하지 않으며 container failure/skip을 PASS로 해석하지 않는다.

- [ ] **Step 2: static/source review를 수행한다.** `git diff --check`, `git status --short`, `rg`로 raw key/body/header가 log/metric/SQL trace에 남지 않는지, JDBC transaction 안에 `delay`, `sleep`, suspend/network callback이 없는지, full scope tuple이 모든 query/CAS/FK에 들어가는지 확인한다. `JobTables.kt`의 Exposed receiver-shadowing과 deprecated import를 검사하고, `JobRepository.submit`의 old advisory lock path가 남아 있지 않은지 caller search를 수행한다.

- [ ] **Step 3: spec-to-plan-to-code traceability를 완성한다.** 아래 matrix의 모든 row에 test path와 fresh command output을 연결한다.

  | 승인 명세/DoD | 구현 task | 증거 |
  |---|---|---|
  | global state machine, waiter cap, CAS takeover | 2–4, 8 | V002 schema, multi-instance/recovery test |
  | Reserve/Prepare/Finalize와 atomic job/outbox/history/snapshot | 3–4, 8 | connection/lock instrumentation, rollback/atomicity test |
  | exact production HTTP first/replay/conflict/timeout/overflow/abandon | 5–8, 10 | Spring/Ktor live HTTP 결과 |
  | commit 전/후 response loss와 같은 key retry 수렴 | 6–7, 10 | 두 barrier 기반 disconnect/retry 및 단일 effect query |
  | strict ingress, scope isolation, snapshot/header safety | 1, 4–8 | negative/security tests와 redacted observability test |
  | V001 preservation, legacy lazy snapshot, V002 checksum/rollback | 2, 8–10 | migration/recovery/compatibility evidence |
  | resource bound, janitor, query plan, readiness | 4, 8–10 | statement/pool/EXPLAIN/janitor/readiness evidence |
  | README parity와 bilingual diagram | 11 | validator, diff, rendered PNG inspection |
  | no new dependency/BOM, CI inclusion | 10, 12 | dependencyInsight, workflow output |

- [ ] **Step 4: 구현 diff를 독립 review에 넘긴다.** `$requesting-code-review`와 `$verification-before-completion`을 적용해 code review, test adequacy, exact head, branch cleanliness를 확인한다. P0/P1이 남아 있으면 수정 후 같은 검증을 재실행한다. 이 task가 완료되어도 merge 승인은 별도다.

---

## 15. 실패·회귀·롤백 결정표

| 상황 | 즉시 동작 | 금지 동작 | 재개 조건 |
|---|---|---|---|
| V002 lock/constraint/checksum 실패 | migration과 readiness를 중단하고 원인/row count 기록 | V001 수정, 강제 backfill, checksum 우회 | clean schema에서 additive migration test PASS |
| stale owner finalize | CAS 0 row와 rollback을 기록 | token 없는 job/outbox insert | lease takeover와 duplicate absence test PASS |
| owner/waiter cancellation | interruption/cancellation 재전파, `finally` abandon/delete 시도 | broad exception으로 500 변환, connection 장기 보유 | quiescence와 lease expiry recovery PASS |
| Spring/Ktor conformance 실패 | 해당 adapter lane만 멈추고 wire diff 확인 | upstream assertion 복제 또는 fixture 완화 | 동일 fixture로 전체 conformance PASS |
| Testcontainers/Colima 실패 | context/socket/로그 진단 | skipped를 성공으로 보고, healthy VM 재시작 | 실제 integration test가 PASS |
| workflow job skipped/missing | matrix와 final aggregator를 read-back | branch protection을 가정해 통과 주장 | live CI에서 required evidence 확인 |
| rollback 요청 | readiness close, 30s+5s drain, owner/waiter 0 확인 | schema rollback, old/new mixed traffic | disabled new binary smoke 후 별도 merge/release 승인 |

---

## 16. 최종 구현 DoD와 중단 조건

구현자는 다음을 모두 fresh evidence로 채우기 전 `DONE`을 선언하지 않는다.

- [ ] Spring/Ktor 실제 HTTP host가 upstream bounded-wait conformance 전체를 순차 PASS.
- [ ] production `/v1/jobs` first/replay는 202 stable body/status/content type, replay에만 header를 전달.
- [ ] conflict/timeout/overflow/abandon과 exact `JobProblem`/`Retry-After`가 두 adapter에서 동일.
- [ ] global waiter cap, lease/generation CAS, stale owner rejection, owner/waiter cancellation과 quiescence PASS.
- [ ] Reserve 전 owner permit 순서, 모든 semaphore/permit `finally` 반환, commit 전/후 response loss retry 수렴, waiter/owner/pool resource quiescence PASS.
- [ ] reserve/register/takeover/janitor/GC의 단일 lock order와 admission–janitor/takeover–GC deadlock/progress evidence PASS.
- [ ] job/outbox/history/terminal snapshot atomicity와 duplicate absence PASS.
- [ ] V001 무손실, V002 checksum/rerun, legacy lazy snapshot fail-closed, rollback drain precondition PASS.
- [ ] waiter/retention/abandoned index plan, janitor batch 100, pool 8/DB semaphore 4/owner prepare 8/instance waiter 32 evidence PASS.
- [ ] JVM allocation/GC/JMH/JFR와 throughput/p95/capacity는 #737 비목표로 명시하고 성능 주장을 하지 않는다(N/A).
- [ ] raw key/body/credential header가 DB snapshot·log·metric label에 노출되지 않음.
- [ ] 기존 lifecycle/heartbeat/UUIDv7/queue/high-contention contract 회귀 없음.
- [ ] 세 module test/integration, `detekt`, build, dependency/BOM audit PASS.
- [ ] 세 module README 영문/한글 parity와 bilingual diagram QA PASS.
- [ ] 한국어 PR body가 `## DoD Status`로 끝나며 issue #737, assignee `debop`, milestone/labels/exact head/CI를 live read-back.

다음 조건 중 하나라도 발생하면 상태는 `PENDING` 또는 `BLOCKED`로 남기고 merge-ready를 주장하지 않는다: P0/P1 review finding, 미실행 integration/CI, ambiguous worktree/dirty unrelated path, V002 rollback precondition 미충족, upstream conformance API resolution 실패, 사용자에게 별도로 필요한 merge/release 권한 부재.

---

## 17. 계획 자체의 Self-review 및 승인 게이트

### Spec coverage

- §1–§4의 core 경계, internal action API, service/repository 분리는 Tasks 1, 3–5에 연결했다.
- §5–§6의 상태 불변식, full tuple, lease/generation, waiter/GC, three-phase transaction은 Tasks 2–4, 8–9에 연결했다.
- §7–§10의 production/test-only wire 계약, snapshot 안전성, cancellation, metrics/readiness는 Tasks 4–8, 10에 연결했다.
- §11–§12의 V002/rollback, Testcontainers/EXPLAIN/resource bound, exact commands는 Tasks 2, 8–10, 13에 연결했다.
- §13–§16의 README/diagram, 예상 변경 지점, 위험/DoD는 Tasks 11–13에 연결했다.
- 이 계획 §17–§18의 승인/independent review/writer DoD는 승인 경계와 Task 13 검증에 연결했다.

### Placeholder scan

이 계획에는 구현 세부를 뒤로 미루는 미정 문구를 사용하지 않았다. workflow 변경은 “누락이 실제로 증명될 때만 수정”이라는 bounded branch로 명시했고, 그 판단 command와 expected evidence를 Task 12에 적었다.

### Type consistency

이후 task가 사용하는 핵심 이름은 Task 1에서 `JobSubmissionCommand`, `JobSubmissionOutcome`, `JobSubmissionIdempotencyPolicy`로 고정하고 Task 3–8에서 같은 이름을 반복한다. owner action은 `prepare(ownership)`와 `commit(connection, ownership, prepared)` 두 단계이며, `ReplayableJobSubmission`은 service/HTTP fixture가 공통으로 소비한다. `JobSubmissionIdempotencyRepository`의 reserve/register/poll/finalize/abandon/cleanup 책임은 coordinator가 직접 SQL을 복제하지 않는다는 경계와 일치한다.

### Writer DoD (SPW-01~05)

| Gate | 상태 | 이 계획의 증거 |
|---|---|---|
| SPW-01 | PASS | 한국어 implementation plan, primary reader는 core/Spring/Ktor 구현자와 HTTP caller, 승인 spec·현재 source·upstream 1.12.1·BOM·issue #737를 source ledger로 고정 |
| SPW-02 | PASS | exact file map, dependency order, TDD RED/GREEN, migration/rollback, test commands, docs/CI/approval gate를 Task 0–13에 포함 |
| SPW-03 | PASS | `$bluetape-writer` Korean naturalness checklist 대상, code/API/SQL/HTTP token과 숫자/명령/URL 보존, 기술적 register 사용 |
| SPW-04 | PASS | spec section/DoD-to-task matrix, current symbol/file anchors, dependency insight와 V001/V002/HTTP evidence traceability 명시 |
| SPW-05 | PASS | Markdown heading/table/fence/link read-back, placeholder/type scan 기록, 남은 gap은 사용자 계획 승인뿐 |

### 6-lens 계획 검토 기록

검토 artifact는 이 계획 파일과 승인된 설계 명세이며, 구현·migration·HTTP 실행은 하지 않았다. 각 native lane은 read-only 계획 검토만 수행했다.

| Lens | 최초 wave 결과 | 반영한 정확한 수정 | 최신 통합 판정 |
|---|---|---|---|
| Performance | P0=0, P1=4, P2=1, P3=1 | Task 1/4의 모든 bound와 permit 음성 경로, Task 8의 exact high-contention run id/`--max-workers=1`, planner 강제 금지·`ANALYZE`·JSON plan artifact, outcome별 statement budget/O(1), allocation/GC/JMH/JFR N/A와 Task cross-reference를 고정 | P0=0/P1=0; P2는 throughput·allocation 주장을 하지 않는 N/A로 명시; P3 수정 완료 |
| Stability | P0=0, P1=4 | owner permit 선취, phase별 cancellation barrier, `finally` 반환, Ktor `NonCancellable`, Spring/Ktor shutdown handle/order, 단일 lock order와 deadlock/progress, commit 전·후 response-loss retry와 quiescence 검증을 Tasks 4/6–8/13에 고정 | P0=0/P1=0 |
| Security | 두 native lane timeout; 주 세션 replacement 검토 P0=0/P1=0 | scope-first precedence, immutable full tuple, strict bounded ingress, snapshot/header denylist, redacted observability와 forged/cross-scope negative test를 Tasks 1/4–7/9/13에 고정 | P0=0/P1=0 |
| Operator/Ops | native lane timeout; 주 세션 replacement 검토 P0=0/P1=0 | V002 lock/checksum·readiness·mixed-version gate, owner/waiter drain, 집계 전용 `IN_FLIGHT`/active-waiter diagnostic SQL, lifecycle ownership과 idempotent shutdown, rollback/CI evidence를 Tasks 2/6–9/12/15에 고정 | P0=0/P1=0 |
| Developer/API | native lane timeout; 주 세션 replacement 검토 P0=0/P1=0 | Task 5에서 shared fixture를 adapter보다 먼저 생성하도록 순서를 수정하고, Spring conditional property/registration guard, Ktor dispatcher boundary, Exposed receiver-shadowing/deprecated import, BOM/dependencyInsight와 cross-module ownership을 고정 | P0=0/P1=0 |
| User/caller | P0=0, P1=3, P2=1, P3=1 | 두 adapter 공통 ingress precedence, 기존 `403 Forbidden`/`scope_denied` 행, `Abandoned`→503 `dependency_unavailable`/same-key retry, README request precondition·400/413/500/503 retry parity를 Tasks 5–7/10–11에 고정 | P0=0/P1=0; P2 수정 완료; P3 self-review 오도 가능성 제거 |

통합 stop condition은 P0/P1=0, 모든 명세·DoD row의 task/command/evidence mapping, placeholder·fence·diff 검증 PASS다. 현재 이 조건은 계획 artifact에 대해 충족했지만 구현 evidence는 아직 없으며, 사용자 계획 승인 전에는 구현으로 이동하지 않는다.

### 승인 게이트

이 계획은 구현자가 바로 실행할 수 있는 상태로 작성했지만, 사용자 계획 승인이 오기 전에는 Task 1의 RED test조차 시작하지 않는다. 계획 승인 후 Step 4에서만 `$subagent-driven-development` 또는 `$executing-plans`를 선택하고, 각 task의 checkbox와 fresh evidence를 갱신한다.

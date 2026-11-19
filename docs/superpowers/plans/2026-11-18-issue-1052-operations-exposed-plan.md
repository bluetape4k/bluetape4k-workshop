# Issue #1052 operations와 fixture Exposed 전환 구현 계획

> **범위 조정:** develop의 PR #1050이 job/outbox/idempotency 일반 CRUD 경로를
> 이미 Exposed로 통일했으므로, 이 branch에서는 같은 경로를 다시 재작성하지 않는다.
> 이번 변경은 남아 있던 일반 probe/attempt 기록과, mapping-backed commerce
> assertion fixture의 범용 RAW 조회 helper를 닫는다. operations의 공통 test fixture와
> Ktor/Spring high-contention profile에도 같은 typed authority helper를 적용한다.
> PostgreSQL 제어·migration·backup/restore·query-plan SQL은 동시성 또는 호환성 검증
> 경계로 유지한다.

**목표:** operations의 미전환 일반 data path를 Exposed transaction으로 정리하고,
operations 공통 fixture/profile과 concert-ticket·promotion-voucher·pre-generated-voucher-pool의
일반 상태/count assertion이 table-specific Exposed API만 사용하도록 고정한다.

**제약:** Kotlin 2.4, Java 25, Exposed 1.6.0, PostgreSQL, Testcontainers. 예제
repository이므로 ABI/backward compatibility shim은 추가하지 않는다.

## Task 1: 현재 branch와 기존 전환 결과 확인

- [x] PR #1050이 이미 job/outbox/idempotency 일반 CRUD를 Exposed로 제공하는지
  develop 기준으로 확인했다.
- [x] operations production SQL과 commerce SQL을 파일·함수 단위로 분류했다.
- [x] 일반 경로 후보를 `JobRepository.databaseReady()`와
  `JobRepository.activateLease()`의 attempt insert로 한정했다.
- [x] migration, retention, backup/restore, EXPLAIN, lock/timeout, advisory lock,
  statement-clock, JSONB/stream projection 경계를 유지하기로 결정했다.

## Task 2: operations 일반 후보 전환

**대상:**

- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobRepository.kt`
- `operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobExposedArchitectureTest.kt`

- [x] `databaseReady()`를 `CurrentTimestamp` 기반 Exposed probe로 전환했다.
- [x] `activateLease()`의 `job_attempts` 기록을 caller-owned Exposed transaction 안의
  `JobAttemptTable.insert`로 전환했다.
- [x] advisory lock, lease/CAS, `SKIP LOCKED`, cleanup CTE/ctid, metadata-less
  fallback은 의미 보존을 위해 기존 SQL 경계로 남겼다.
- [x] source architecture assertion으로 `SELECT 1` JDBC probe가 재도입되지 않음을
  고정했다.

## Task 3: mapping-backed commerce fixture 확장

**concert-ticket:**

- [x] `PurchaseFixture`, `AbstractTicketIntegrationTest`,
  `TicketEffectIntegrationTest`, restart/end-to-end/payment/refund/concurrency
  tests의 일반 상태/count 조회를 typed Exposed helper로 전환했다.
- [x] high-contention profile의 attempt, inventory, operation, receipt assertion을
  typed helper로 전환했다.

**promotion-voucher:**

- [x] command/reconciliation/end-to-end/concurrency/stress assertion을
  `ClaimTable`, `AuditTable`, `EventInboxTable` 기반 helper로 전환했다.
- [x] context-restart의 audit count를 `VoucherJdbcExecutor`와 `AuditTable`로
  전환했다.

**pre-generated-voucher-pool:**

- [x] `LifecycleHarness`의 entry/user/count/state/audit/depth 조회를 typed
  `VoucherPool*Table` helper로 전환했다.
- [x] batch ingest의 batch 기준 데이터/count와 idempotency의 normal count를 typed
  helper로 전환했다.
- [x] operator worker claim과 retention의 일반 retained/count/replay assertion을
  typed helper로 전환했다.
- [x] ciphertext byte-substring 검사는 암호화 저장 검증의 목적을 유지하면서
  조회 row를 Exposed로 읽고 테스트 코드에서 판정하도록 전환했다.
- [x] migration/compatibility, backup/restore clone, performance probe,
  lock telemetry, schema-health probe의 raw SQL은 검증 경계로 유지했다.

**operations 공통 fixture/profile:**

- [x] `JobConsoleDatabaseFixture`의 일반 table count와 tenant/state/status count를
  typed Exposed query로 전환했다.
- [x] `JobConsoleAuthorityQueries`를 추가해 core test fixture와 Ktor/Spring
  high-contention profile이 동일한 Exposed authority baseline/count 규칙을 공유하게
  했다.
- [x] schema 생성/삭제, seed/control SQL, EXPLAIN/query-plan SQL은 test-control
  경계로 유지했다.

## Task 4: 재도입 방지 계약과 문서

- [x] concert-ticket, promotion-voucher, pre-generated-voucher-pool에 일반 fixture가
  `query*`/`single*`/`scalar` helper로 되돌아가지 않는 architecture test를 추가했다.
- [x] operations architecture test가 `JobConsoleDatabaseFixture`의 범용 count SQL과
  shared authority helper의 JDBC count 경계를 차단하도록 확장했다.
- [x] operations와 commerce의 보존 경계 및 전환 원칙을
  `docs/lessons/2026-11-18-issue-1052-operations-exposed.md`에 기록했다.
- [x] 계획과 설계를 실제 구현 범위에 맞게 축소해 미실행 DSL 전환 항목을 남기지
  않았다.

## Task 5: fresh verification과 handoff

- [x] 변경 모듈의 test compilation을 통과시켰다.
- [x] concert-ticket full test, promotion-voucher full test,
  pre-generated-voucher-pool full test(373 tests)를 fresh head에서 통과시켰다.
- [x] pre-generated-voucher-pool, concert-ticket, promotion-voucher, operations
  architecture test를 fresh head에서 통과시켰다.
- [x] operations `check`(99 tests), `integrationTest`(51 tests), Ktor/Spring
  adapter test와 integration test를 통과시켰다.
- [x] 최종 head에서 `git diff --check`와 residual raw-helper inventory를 다시
  실행했다. 일반 fixture의 범용 helper는 사라졌고, 남은 `query*`/`single*`은
  migration/compatibility/backup-restore/performance/lock telemetry 경계에만
  남아 있다.
- [x] 전체 `detekt`를 실행해 저장소 baseline 위반을 확인했다. pre-generated
  voucher pool 및 usage metering 관련 기존 규칙 위반이 보고됐고, 이번
  fixture 전환으로 추가된 신규 위반은 확인되지 않았다.
- [x] workflow receipt/mutation-check를 갱신하고 최종 diff를 검토했다.
  mutation-check sequence 24, completion sequence 25, receipt checksum
  `f50b8864592634a32b134a123ef500eae4b52e77ea6d1eb639bd14bcc9e337f1`.
- [x] Lore commit으로 branch에 기록했다. PR 생성/merge는 별도 승인 단계로
  남긴다.

## 수용 기준

1. 일반 operations probe/attempt 기록과 mapping-backed fixture assertion이
   Exposed typed query/transaction을 사용한다.
2. 보존된 raw SQL은 migration/control/diagnostic/compatibility 목적의 분류와
   파일 근거가 있다.
3. architecture test가 범용 fixture query helper 재도입을 차단한다.
4. 변경 모듈의 compile, focused/full/integration, static 검증 결과를 fresh
   evidence로 남긴다.
5. branch는 merge하지 않고 사용자 승인 가능한 상태로 유지한다.

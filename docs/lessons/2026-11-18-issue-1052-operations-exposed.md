# Issue #1052 operations와 fixture RAW SQL 경계

## 결정

이번 작업은 `operations/job-console-core`의 일반 production 경로와 Exposed table
mapping을 이미 가진 commerce 통합 assertion을 분리해 다룬다. 일반 CRUD, readiness
probe, attempt 기록, 상태/count 검증은 Exposed DSL과 기존 transaction 경계를 사용한다.
반대로 migration history, `information_schema`, backup/restore clone, EXPLAIN,
lock/timeout 주입, PostgreSQL advisory lock과 statement-clock 제어는 SQL 자체가
검증 대상이거나 DSL로 동일한 제어 의미를 보장해야 하므로 보존한다.

## 반영한 변경

- `JobRepository.databaseReady()`의 `SELECT 1`을 `CurrentTimestamp` Exposed probe로
  바꾸고, lease activation의 `job_attempts` insert를 `JobAttemptTable.insert`로
  전환했다.
- `PurchaseFixture`의 `durableState`, inventory/state/count/operation 조회를
  `TicketTables` 기반 typed helper로 바꿨다. `TicketEffectIntegrationTest`와
  `AbstractTicketIntegrationTest`는 범용 query helper를 더 이상 사용하지 않는다.
- concert-ticket high-contention profile의 일반 상태/count assertion과
  promotion-voucher command/reconciliation/context-restart assertion도
  table-specific Exposed helper를 사용한다.
- pre-generated-voucher-pool의 `LifecycleHarness`, batch ingest, idempotency,
  operator, retention assertion도 각 `VoucherPool*Table`을 사용하는 typed helper로
  확장했다. ciphertext byte-level 검사는 row를 Exposed로 읽고 테스트 코드에서
  판정한다.
- operations `JobConsoleDatabaseFixture`의 일반 table/tenant/state/status count를
  Exposed mapping으로 전환했다. core와 Ktor/Spring high-contention profile은
  `JobConsoleAuthorityQueries`를 공유해 baseline, duplicate outbox, burst queue,
  active job, queue-version divergence를 같은 authority 규칙으로 검증한다.
- high-contention authority helper와 mapping-backed fixture의 Exposed `Database`
  manager는 fixture/profile 종료 시 `closeAndUnregister`하도록 정리했다. 프로필
  반복 실행에서 manager가 누적되지 않도록 수명주기를 명시했다.
- 각 대상 모듈에 범용 `query*`/`single*`/`scalar` helper 재도입을 막는 source
  architecture test를 추가했다.
- reservation integration fixture는 테스트 중 expiry scheduler의 첫 실행을
  지연해 Exposed schema drop/create와 scheduler 조회가 경합하지 않도록 했고,
  event-store의 existing JDBC transaction helper는 caller connection이 열린
  상태와 caller rollback 권한을 유지하는지 통합 테스트로 고정했다.

## 보존한 경계

다음 SQL은 미전환이 아니라 명시적인 test-control 또는 DB authority 경계다.

| 범위 | 보존 이유 |
| --- | --- |
| migration/compatibility history와 `information_schema` | schema authority와 이전 버전 계약을 직접 검증 |
| backup/restore clone | 별도 `DataSource`의 복제 결과를 검증하며 애플리케이션 transaction mapping이 없음 |
| EXPLAIN/query-plan | planner 선택과 PostgreSQL hint가 테스트 대상 |
| lock/timeout probe 및 claim expiry 주입 | 경쟁 조건과 세션 제어를 의도적으로 재현 |
| operations의 lease/`SKIP LOCKED`/CTE/ctid/advisory lock | row-lock 순서·statement 읽기 시점·maintenance semantics 보존 |
| pre-generated performance/lock telemetry와 schema-health probe | 성능·세션·migration 관측 자체가 검증 대상 |

독립 diff review에서 확인된 P2도 반영했다. authority helper와 변경된 fixture의
Exposed manager teardown을 추가했고, ticket effect claim 만료 주입은 JVM `Instant`가
아닌 `CURRENT_TIMESTAMP - INTERVAL '1 second'`로 복원해 DB clock 권위를 유지했다.
reservation fixture의 scheduler 지연과 existing JDBC transaction의 connection/rollback
회귀 검증도 추가해 DDL 경합과 caller-owned lifecycle 가정을 실행 시점에 확인한다.
duplicate outbox와 burst sequence 관측의 JVM 집계는 bounded high-contention
fixture 전용 P3 성능 주의사항으로 남겼으며, 대상 시나리오의 정합성 테스트는 통과했다.

추가로 확인한 다른 모듈 fixture도 일반 상태 조회는 이미 Exposed DAO/DSL을 사용하거나
raw SQL이 schema 생성·삭제 같은 lifecycle 제어에만 한정되어 있었다. 따라서 이번
확장은 operations 공통 fixture/profile까지로 닫고, 해당 lifecycle SQL을 억지로 DSL로
바꾸지 않았다.

따라서 “모든 SQL 제거”가 아니라 “일반 애플리케이션 data path와 일반 assertion을
Exposed로 단일화”가 이번 변경의 완료 조건이다.

## 검증

- 변경 대상 compilation 통과
- concert-ticket full test 통과
- promotion-voucher full test 통과
- pre-generated-voucher-pool full test 373개 통과
- operations core `check` 99개 및 `integrationTest` 51개 통과
- Ktor/Spring adapter test와 integration test 통과
- 네 모듈 Exposed architecture test 통과
- `git diff --check` 통과
- 독립 diff review: P0/P1 0, P2 manager lifecycle·DB clock 지적 보정 후 fresh full
  matrix 재통과

전체 `detekt`는 저장소 baseline 위반으로 실패했다. 현재 보고된 기존 위반은
pre-generated-voucher-pool의 `RuntimeContractTest`, `VoucherBatchIngestIntegrationTest`,
`VoucherPoolKeyRotationIntegrationTest`, `VoucherPoolIdempotencyIntegrationTest` 및
`OperatorVoucherPoolHttpCommandExecutor`의 line/size 규칙이며, 이번 Fixture 전환으로
추가된 새 detekt 위반은 확인되지 않았다. workflow receipt는 mutation-check
sequence 24와 completion sequence 25로 갱신했고, 최종 receipt checksum은
`f50b8864592634a32b134a123ef500eae4b52e77ea6d1eb639bd14bcc9e337f1`이다.

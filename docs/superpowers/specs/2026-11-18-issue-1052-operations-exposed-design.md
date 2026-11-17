# Issue #1052 operations와 fixture Exposed 전환 설계

## 문제와 목표

Issue #1052는 `operations/*`의 RAW SQL을 Exposed로 전환하자는 요청이다. develop의
PR #1050이 job/outbox/idempotency 일반 CRUD를 이미 Exposed로 통일했으므로, 이번
branch는 남아 있던 일반 probe/attempt 기록과 mapping-backed commerce assertion
fixture를 닫는 범위로 확정한다.

목표는 다음과 같다.

- `JobRepository.databaseReady()`와 `job_attempts` 기록을 기존 Exposed transaction
  경계 안에서 실행한다.
- concert-ticket, promotion-voucher, pre-generated-voucher-pool의 일반 상태/count
  assertion이 table-specific Exposed query를 사용하도록 한다.
- operations core fixture와 Ktor/Spring high-contention profile이 같은 typed
  authority query를 공유하도록 한다.
- migration, retention, backup/restore, query-plan, lock/timeout, advisory lock,
  statement-clock, 암호화 저장 검증처럼 SQL 자체가 검증 대상인 경계는 보존하고
  파일·함수 단위로 분류한다.

## 범위

### 변경 대상

- `operations/job-console-core`의 남은 일반 probe와 attempt insert
- `commerce/*`의 mapping-backed 일반 integration/profile fixture 조회 helper
- 위 범위를 지키는 source architecture regression test와 한국어 lesson

### 이미 완료되어 재작성하지 않는 범위

PR #1050에서 이미 Exposed로 전환된 job/outbox/idempotency 일반 CRUD는 이번 branch에서
중복 재작업하지 않는다. lease/CAS, `SKIP LOCKED`, cleanup CTE/ctid, metadata-less
fallback은 현재 저장소의 PostgreSQL 동시성·유지보수 경계로 유지한다.

### 의도적으로 남기는 경계

- migration SQL과 `information_schema`/schema history assertion
- backup/restore clone의 별도 `DataSource` 검증
- `EXPLAIN`, planner/lock telemetry, advisory lock, timeout/session control
- retention purge의 database-time 및 dependency-order fixture
- 암호화 ciphertext byte-level 저장 검증
- mapping이 없거나 이전 schema/compatibility를 검증하는 test fixture

이 설계는 “RAW SQL을 모두 제거”하지 않는다. 일반 애플리케이션 data path와 일반
상태/count assertion만 Exposed로 단일화하며, 제어·진단·호환성 경계는 의미를
변경하지 않는다.

## 설계

### Operations

`databaseReady()`는 `jdbc.exposedTransaction { select(CurrentTimestamp).singleOrNull()
!= null }`로 probe한다. `activateLease()`는 이미 열린 caller-owned transaction
안에서 `JobAttemptTable.insert`를 실행한다. 별도의 nested JDBC transaction을 열지
않아 lease 변경과 attempt 기록의 원자성을 유지한다.

### Commerce fixture

각 fixture는 해당 모듈의 기존 `Table` 정의를 직접 사용한다.

- ticket: inventory, attempt, order, effect operation/receipt, identity guard,
  ticket state/count
- promotion voucher: claim, audit, event inbox
- pre-generated voucher pool: campaign, batch, entry, reservation, allocation,
  idempotency, tombstone, audit, depth, worker claim, retention rows

조회 helper는 작은 typed method로 이름과 필터를 고정하고, test-control용 update,
trigger, schema seed SQL과 섞지 않는다. ciphertext byte-substring 검사는 row를
Exposed로 읽은 뒤 테스트 코드에서 byte sequence를 판정한다.

### Operations fixture/profile

`JobConsoleDatabaseFixture`의 일반 count는 허용된 table 이름을 Exposed `Table`에
매핑한 뒤 `selectAll().count()`와 table-specific predicate로 조회한다. 임의 SQL
문자열을 count helper에 전달하지 않으므로 일반 assertion이 다시 `SELECT count(*)`
경계로 회귀하지 않는다.

core test fixture와 Ktor/Spring high-contention profile은
`JobConsoleAuthorityQueries`를 공유한다. baseline, duplicate outbox, burst queue,
active job, queue-version divergence를 같은 Exposed table mapping으로 판정하고,
각 호출에서 생성한 Exposed `Database` manager는 `finally`에서
`TransactionManager.closeAndUnregister`로 정리한다. schema 생성/삭제·seed/control·EXPLAIN은
별도 test-control 경계로 남긴다.

### 재도입 방지

각 모듈의 architecture test는 일반 fixture source에서 범용 `query*`, `single*`,
`scalar` helper가 다시 등장하지 않는지 검사한다. migration/compatibility,
backup/restore, performance, lock/timeout 파일은 이 계약의 대상이 아니며 문서에
보존 이유를 남긴다.

## 실패와 복구

- Exposed query가 현재 PostgreSQL schema/enum/nullable column과 맞지 않으면 해당
  모듈 compile/test를 실패시키고 mapping 또는 typed helper만 수정한다.
- transaction 경계를 넘기 위해 raw SQL을 다시 추가하지 않는다. caller-owned
  connection이 필요한 rollback/lock 테스트는 기존 helper를 유지한다.
- 보존 경계에 대한 architecture test가 실패하면 allow-list를 넓히기 전에 해당
  SQL의 검증 목적과 범위를 다시 분류한다.

## 검증과 수용 기준

1. operations compile, focused test, architecture test, `check`, integration test와
   Ktor/Spring adapter test가 fresh head에서 통과한다.
2. concert-ticket, promotion-voucher, pre-generated-voucher-pool의 typed fixture
   compile, architecture test 및 full test가 통과한다.
3. residual raw-helper inventory가 migration/control/diagnostic/compatibility
   파일로만 남는다.
4. `git diff --check`, 독립 diff 검토에서 P0/P1이 없다. detekt는 기존 baseline
   위반과 신규 위반을 분리해 기록한다.
5. fixture/profile teardown이 Exposed manager를 해제하고 DB clock 기반 만료 주입의
   시간 권위를 보존한다.

## 제외 사항

- ABI/backward compatibility: 예제 repository 제약에 따라 별도 shim을 추가하지 않는다.
- migration SQL을 Exposed schema builder로 재작성하는 작업
- PostgreSQL control/retention/lock/query-plan/backup/restore semantics를
  일반 CRUD처럼 DSL로 바꾸는 작업

## DoD

- [x] 코드와 regression test가 branch에 반영됨
- [x] 보존 경계와 residual inventory가 문서/architecture test에 반영됨
- [ ] 대상 Gradle 검증과 workflow receipt가 최종 상태로 갱신됨
- [ ] PR 생성/merge는 별도 사용자 승인 단계로 남김

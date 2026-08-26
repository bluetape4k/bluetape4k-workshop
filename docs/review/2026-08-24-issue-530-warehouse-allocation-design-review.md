# Issue #530 Warehouse Allocation 및 Pick-Wave Planner 설계 리뷰

- 날짜: 2026-08-24
- 대상: `docs/superpowers/specs/2026-08-23-issue-530-warehouse-allocation-design.md`
- 저장소: `bluetape4k/bluetape4k-workshop`
- 기준: Epic #523, bluetape-workflow Type-A Step 2-R, 최신 live Issue #530
- 결정: P0/P1/P2 결함 없이 설계 리뷰를 통과했다. 구현 계획과 코드는 사용자 승인 뒤
  다음 gate에서 시작한다.

## 검토 범위와 증거

설계서, 현재 `optimization/planning-contracts`와 `optimization/field-service-dispatch`의
구현 경계, Epic/child issue의 live 상태, #524의 현재 wire DTO와 callback source commit
`5ec53f96e`, 그리고 GNO 전역 검색 결과를 대조했다. 설계 리뷰 단계에서는 구현 코드,
Gradle build, Testcontainers, browser smoke를 실행하지 않았다.

GNO는 collection을 제한하지 않고 다음 `gno query` 전역 명령으로 조사했다.

```bash
gno --offline query --fast --no-graph --limit 8 --json "warehouse allocation pick-wave planner"
gno --offline query --fast --no-graph --limit 8 --json "PostgreSQL reservation authority stale oversell inventory CAS"
gno --offline query --fast --no-graph --limit 8 --json "deterministic solver outbox duplicate out-of-order restart replay"
gno --offline query --fast --no-graph --limit 8 --json "planning-contracts stale callback outbox fencing aggregate version"
```

직접 사용한 GNO 원문은 Epic/Issue #530, Timefold planning reference, planning-contracts
README, #525 plan, transactional-outbox lesson이며, GNO는 탐색 근거로만 사용하고 live
GitHub와 현재 저장소 파일을 최종 권위로 삼았다. 조사 결과는 설계서의 source ledger와
결정 목록에 보존했다.

## 독립 관점 결과

| 관점 | 결과 | 핵심 증거와 처분 |
|---|---|---|
| Architecture | PASS — P0=0, P1=0, P2=0 | 독립 aggregate/lock order, `expectedOrderRevision`을 포함한 plan 실행 기준 데이터, order projection truth table, approval CAS와 stale no-write를 확인했다. #524 boundary와 PostgreSQL authority도 회귀가 없다. |
| Security/API | PASS — P0=0, P1=0, P2=0 | 닫힌 wire enum, `PIN_CONFLICT`, event conflict와 `ACTIVE_PLAN_CONFLICT`의 409 매핑, duplicate 202 replay, canonicalization, bounded DTO, callback trust boundary, redaction을 확인했다. |
| Stability/Ops | PASS — P0=0, P1=0, P2=0 | queue saturation과 `RETRY_EXHAUSTED`를 분리하고 crash 단계별 `RETRYABLE`/`COMPLETED`, startup recovery 1 worker/20 rows/2 seconds와 21-row fixture를 고정했다. |
| Developer/API | PASS — P0=0, P1=0, P2=0 | 독립 module/package, 유일한 `WarehouseAllocationFixturePort` ABI, 명시적 `WarehouseAllocationApplicationKt`, route별 닫힌 DTO와 #524 exact wire mapping, resource/module registration surface를 확인했다. |
| Performance/resource/caller | PASS — P0=0, P1=0, P2=0 | line/warehouse/wave/stock/pin cardinality, `2_000_000` candidate bound, output/response bound, executor admission, retry header와 `nextAction`을 확인했다. |
| Test-contract/verification | PASS — P0=0, P1=0, P2=0 | planner positive/negative fixture, event replay/conflict matrix, reservation CAS race, idempotency recovery, paired outbox, HTTP/browser redaction과 no-write assertion, 정확한 Gradle/README/workflow 명령을 확인했다. |

모든 lane은 최신 설계서를 다시 읽은 spec-level review이며, 아직 구현·실행 증거로 승격하지
않는다.

## 통합 판정

| 우선순위 | 건수 | 통합 처분 |
|---|---:|---|
| P0 | 0 | 없음 |
| P1 | 0 | 없음 |
| P2 | 0 | 없음 |
| P3 | 0 | 없음 |

### 통합 결정

1. planner는 deterministic reference/fake 경계에서 immutable warehouse/order/stock/wave
   기준 데이터를 읽고 proposal만 만든다. PostgreSQL reservation과 order aggregate가
   approval의 최종 권위이며, stale revision·stock CAS 실패는 전체 rollback한다.
2. `Order`는 `warehouse_alloc_orders`의 단일 write owner다. line-scoped
   `order.cancelled`도 parent order lock과 line CAS를 거치고 order revision/status를
   원자 갱신한다. approval은 plan의 `expectedOrderRevision`과 현재 order를 재검증한다.
3. event inbox는 canonical digest, source revision, target binding을 먼저 검증한다.
   duplicate는 no-op replay, key reuse/revision conflict/stale event는 고정 409 오류와
   no-write를 사용한다. 최신 event의 aggregate/audit/replan intent는 한 transaction에 묶는다.
4. idempotency는 `IN_PROGRESS`/`RETRYABLE`/`COMPLETED`/`FAILED_TERMINAL`로 닫는다.
   queue 포화만 retryable admission이며 입력/deadline/output 초과는 terminal replay,
   retryable 경계 소진은 `409 RETRY_EXHAUSTED`다. queue saturation 503에는 operationKey를
   주지 않고, 별도 durable `COMMAND_IN_PROGRESS` 202만 polling target을 가진다.
5. outbox와 local effect는 paired state와 lease/fencing/affected-row 조건을 같은
   transaction에 적용한다. 외부 relay는 at-least-once와 provider idempotency/reconciliation
   범위만 주장한다.
6. 실제 Timefold/custom solver, Kafka/WMS/carrier/provider, 자동 stock commit, 공통 SDK
   추출은 이번 child의 비목표다. provider 없는 deterministic fixture가 기본 CI 경로다.

최종 read-back에서 조건부 응답 필드도 닫았다. replan의 `STALE` 원인은
`ReplanStaleReason` 전용 enum으로 분리했고, split reservation은 최대 500개
`reservations[]`로 반환하며 없을 때 `[]`를 사용한다. `activePlanId`, `pinRevision`,
`planId`, `staleReason`, `effectState`, `nextAttemptAt`의 null/presence 규칙과 golden
fixture를 추가한 뒤 Architecture lane을 다시 PASS로 확인했다.

Test-contract lane의 마지막 traceability 점검에서 #524 seam을 검증하는
`WarehouseAllocationPlanningContractTest`, resource/output·event conflict·order projection
전용 DoD 행, 그리고 `Examples.yml`/`smoke-validate.sh`의 실제 task·artifact·stale-check
line mapping을 추가했다. event conflict 명령에는 Postgres와 HTTP contract test를 모두
명시했다. 이 수정 후 해당 lane을 다시 읽어 PASS(P0/P1/P2=0)로 확인했다.

## SPW-01..05 문서 품질 점검

| 항목 | 상태 | 증거 |
|---|---|---|
| SPW-01 독자·목적·출처·범위 | PASS | 설계서의 Issue/Epic 링크, 대상 module, GNO source ledger, production 연동 비목표 |
| SPW-02 선택지·경계·실패·수용 기준 | PASS | domain/authority, API/error DTO, resource guard, idempotency/outbox, fixture/DoD 매핑 |
| SPW-03 한국어 기술 문체·용어 | PASS | `audit-korean-terms.mjs` findings 47. 모두 `snapshot`이라는 명시적 domain/protocol term이며 의미 변경 없는 전역 치환을 하지 않았다. `대기열` 모호성은 `실행 대기 목록`으로 정리했다. |
| SPW-04 현재 소스·외부 계약 대조 | PASS | live #523/#530, #524 source `5ec53f96e`, current module/BOM/workflow surface, GNO source ledger |
| SPW-05 read-back·Markdown·공백 | PASS | 설계서와 리뷰 문서 read-back, `git diff --check`, untracked spec no-index whitespace scan, 명령/경로 재검토 |

검토 체크리스트: **Required checks 8/8; N/A 0; Blocked 0**. 위 체크는 설계 리뷰 산출물의
품질과 계약 일관성에 대한 것이며, 구현 단계의 build/test 통과를 의미하지 않는다.

## 남은 범위와 다음 gate

- 현재 변경 output은 설계서와 이 리뷰 문서뿐이다. `optimization/warehouse-allocation`
  module, tests, README/workflow/stale-check/lesson 변경은 아직 시작하지 않았다.
- 다음은 사용자에게 수정된 설계서의 명시적 승인을 받은 뒤 `writing-plans`로 구현 계획을
  작성하고, plan에 대해 동일한 여섯 관점과 main integration review를 수행하는 단계다.
- 구현 단계에서는 TDD, `./gradlew projects`, targeted test, build/detekt, smoke/stale-check,
  README parity/language, actionlint, Testcontainers와 browser contract를 fresh evidence로
  검증한다.
- 이 리뷰는 PR 생성·CI·merge·release 승인을 의미하지 않는다.

## 최종 상태

`PASS` — 설계 리뷰의 P0/P1/P2는 모두 0이다. 구현과 검증은 사용자 승인 이후의 다음 gate로
보류한다.

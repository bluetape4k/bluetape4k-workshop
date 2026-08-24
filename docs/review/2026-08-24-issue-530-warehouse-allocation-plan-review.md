# Issue #530 Warehouse Allocation 및 Pick-Wave Planner 구현 계획 리뷰

- 날짜: 2026-08-24
- 대상 계획: `docs/superpowers/plans/2026-08-24-issue-530-warehouse-allocation-plan.md`
- 기준 설계: `docs/superpowers/specs/2026-08-23-issue-530-warehouse-allocation-design.md`
- 저장소: `bluetape4k/bluetape4k-workshop`
- 기준 gate: bluetape-workflow Type-A Step 3-R
- 결정: 여섯 관점과 main integration 모두 P0/P1/P2 결함 없이 통과했다. 다음은 승인된 계획을 커밋하고 TDD 구현 Step 4로 진입한다.

## 검토 범위와 증거

승인된 설계서와 구현 계획, 현재 `optimization/planning-contracts` 및
`optimization/field-service-dispatch` 구현 경계, Epic #523/#530 live 상태를 대조했다.
현재 branch에는 구현 module이 아직 없으므로 Gradle/Testcontainers/browser 실행 결과는
계획 gate의 증거가 아니다. plan SHA-256은
`ea496864c344bab4e6d7618cf01c69c413ee6d62a47b908d27ee0dc31460008c`이며, 설계서 SHA-256은
`1e4113fe6093af4747d93529d683e25f898a983946f7abc58bfb8c7e0bcd70fc`이다.

GNO는 사용자의 지시에 따라 collection을 지정하지 않고 전역 검색했다.

```bash
gno --offline query --fast --no-graph --limit 8 --json "warehouse allocation pick-wave planner"
gno --offline query --fast --no-graph --limit 8 --json "PostgreSQL reservation authority stale oversell inventory CAS"
gno --offline query --fast --no-graph --limit 8 --json "deterministic solver outbox duplicate out-of-order restart replay"
gno --offline query --fast --no-graph --limit 8 --json "planning-contracts stale callback outbox fencing aggregate version"
```

직접 사용한 결과는 Epic/Issue #530, Timefold planning reference, planning-contracts README,
#525 구현 계획, transactional-outbox lesson이다. GNO는 조사 근거로만 사용하고 live GitHub와
현재 저장소 파일을 최종 권위로 삼았다.

## 독립 관점 결과

| 관점 | 결과 | 핵심 증거와 처분 |
|---|---|---|
| Performance/resource | PASS — P0=0, P1=0, P2=0 | planner admission 2 running + 20 waiting + 23번째 거부, outbox 4 worker/20 batch/100 queue, 다섯 PostgreSQL 경합과 `maxLockWait <= 2s`, 2M candidate/10k stock diagnostic 및 JFR 경로를 exact test/command에 연결했다. |
| Security/API | PASS — P0=0, P1=0, P2=0 | closed DTO/enum, canonical `warehouse-canonical-v1`, target-bound idempotency, HMAC/FAKE trust boundary, loopback/CORS/non-demo negative, actor/spoof guard, redaction, fixed error DTO와 `RESPONSE_TOO_LARGE`를 고정했다. |
| Stability/user-caller | PASS — P0=0, P1=0, P2=0 | DB `clock_timestamp()`/fencing, initial `PENDING` effectless 예외, paired transition matrix, attempt 5/6 및 1→30초 backoff, restart/sweep, terminal replay, #524 status/score grammar와 lifecycle shutdown을 연결했다. |
| Operator/Ops | PASS — P0=0, P1=0, P2=0 | README recovery runbook, operationKey 조회, reconciliation/redrive 허용·금지 경계, readiness/liveness, low-cardinality metrics, redacted diagnostics와 `if: always()` artifact 경로를 명시했다. |
| Developer/API | PASS — P0=0, P1=0, P2=0 | 파일 소유권, 모듈 진입점, BOM-only consumer 규칙, Exposed v1 lock/CAS, `WarehouseAllocationFixturePort` ABI, exact HTTP/#524 seam과 명령 순서를 확인했다. |
| Test-contract/verification | PASS — P0=0, P1=0, P2=0 | 모든 Task 1~9가 RED→GREEN test/class/command와 rollback 지점을 가지며, registration, README parity, workflow/stale-check, Docker PENDING evidence까지 연결된다. |

모든 lane은 계획을 독립적으로 fresh-read한 결과이며 구현 runtime PASS로 승격하지 않는다.

## Main integration 판정

| 우선순위 | 건수 | 처분 |
|---|---:|---|
| P0 | 0 | 없음 |
| P1 | 0 | 없음 |
| P2 | 0 | 없음 |
| P3 | 0 | 없음 |

1. **추적성:** 설계서의 planner hard constraint/resource, PostgreSQL reservation authority와
   order CAS, six event replay, idempotency/recovery, #524 seam, outbox/effect fencing,
   HTTP/nullability/redaction, fixture ABI, README/workflow/matrix/lesson 등록이 각각
   Task 1~9의 파일·테스트·Gradle 명령에 매핑된다. unchecked acceptance criterion은 없다.
2. **순서:** module/resources → domain/planner → persistence → event/idempotency →
   command/approval/#524 → HTTP → fixture ABI → registration/docs → full verification의
   의존 순서가 유지된다. 외부 provider나 production migration은 범위 밖이다.
3. **경계/안전:** PostgreSQL이 approval의 최종 권위이며 lock 순서와 CAS를 고정한다.
   event/idempotency/outbox는 canonical target, revision, lease/fence, affected-row 조건과
   no-write 회귀를 가진다. `X-Demo-Operator`는 인증 수단이 아닌 local guard로 문서화한다.
4. **운영 증거:** 실패 시 redacted snapshot/row-count/local-effect/audit/lock-wait와
   container log를 `optimization/warehouse-allocation/build/reports/warehouse-allocation-diagnostics/**`
   에 남기고, JFR은 `build/reports/performance/*.jfr`에 저장한다. Docker unavailable/skip은
   성공으로 처리하지 않고 PENDING으로 보고한다.
5. **롤백:** Task 1~2 module-only, Task 3~5 schema fixture, Task 6 HTTP adapter,
   Task 8 registration/docs 단위로 되돌릴 수 있고, 각 경계가 plan에 기록되어 있다.
6. **범위:** PR 생성, merge, release, 외부 provider side effect는 이 gate에 포함되지 않는다.

## SPW-01..05 계획 품질 점검

| 항목 | 상태 | 증거 |
|---|---|---|
| SPW-01 독자·목적·출처·범위 | PASS | Goal/Architecture/Tech Stack, Issue/Epic, 설계서·GNO·현재 module anchor와 비목표를 명시했다. |
| SPW-02 선택지·경계·실패·수용 기준 | PASS | Task별 파일 소유권, RED/GREEN, 상태·오류·resource·rollback·exact command가 있다. |
| SPW-03 한국어 기술 문체·용어 | PASS | `audit-korean-terms.mjs`의 계획 5건과 리뷰 3건은 fixture ABI `snapshot`, stock snapshot model, #524 aggregate ID, diagnostic snapshot artifact라는 고정 protocol/domain 용어이며 의미 손실 없는 치환 대상이 아니다. |
| SPW-04 현재 소스·외부 계약 대조 | PASS | live #523/#530, #524 route/DTO, existing optimization module/BOM/Testcontainers pattern과 GNO 조사 결과를 plan에 반영했다. |
| SPW-05 read-back·Markdown·공백 | PASS | plan read-back, placeholder scan 0, `git diff --check` 통과, exact paths/commands와 artifact 경로를 재확인했다. |

## 승인 gate와 다음 단계

- 현재 변경 output은 설계서, 설계 리뷰, 구현 계획, 이 계획 리뷰 문서다. 구현 source,
  README/workflow/stale-check/lesson은 아직 시작하지 않았다.
- 계획 gate는 **PASS — P0=0/P1=0/P2=0**이다. 승인된 범위에 따라 계획·설계 문서를 Lore
  commit protocol로 커밋한 뒤 Step 4 TDD를 시작한다.
- 구현 중에는 각 Task의 RED 실패를 관찰한 뒤 최소 구현으로 GREEN을 만들고, 마지막에
  targeted test → module build/detekt → registration/actionlint/readme parity →
  Testcontainers/performance-stability evidence 순서로 검증한다.
- PR 생성·merge·release는 이 계획 리뷰나 현재 사용자 승인의 범위에 포함하지 않으며 별도
  gate로 유지한다.

## 최종 상태

`PASS` — 계획 Step 3-R 및 main integration의 P0/P1/P2는 모두 0이다. 구현 runtime 검증은
아직 시작 전이므로 다음 Step 4에서 fresh evidence를 수집한다.

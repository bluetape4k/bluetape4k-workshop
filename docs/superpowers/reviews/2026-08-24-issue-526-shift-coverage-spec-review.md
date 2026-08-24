# #526 Shift Coverage 설계 문서 검토

- 검토일: 2026-08-24
- 대상 문서: `docs/superpowers/specs/2026-08-24-issue-526-shift-coverage-design.md`
- 대상 문서 SHA-256: `5857e564c43c4a3bc870f15ab29a19db1512c3e9dae6043061b34d522888092d`
- 근거 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/526
- 검토 범위: Type A 설계 문서의 요구사항 추적성, 결정성, 실패 수렴, 보안, 운영,
  API, 호출자 경험

## 근거 원장

| 근거 | 사용 목적 | 확인 결과 |
|---|---|---|
| Live Issue #526 및 Epic #523 | 목표·완료 조건·비목표·delivery 제약 | availability/skill/preference/demand/pin, hard rule, revision 재검증, timezone/concurrency/post-start/webhook ordering, Java 25 virtual thread 요구를 반영했다. |
| GNO 전역 query 결과 | 기존 결정과 외부 조사 연결 | GitHub #526와 Timefold Platform reference research를 collection 제한 없이 확인했다. |
| `bluetape4k-wiki` Timefold 연구 | Platform/custom Solver 경계와 PostgreSQL 권위 | provider 결과를 versioned proposal로 취급하고 deterministic fake를 기본 경로로 고정했다. |
| `optimization/field-service-dispatch` | repository/CAS/callback/outbox/virtual-thread 패턴 | domain class를 공유하지 않고 검증된 경계·테스트 수치만 채택했다. |
| `optimization/planning-contracts` | #524 adapter/fixture 및 Bluetape capability | implementation dependency 없이 normalized port와 Java 25 capability 선택을 기록했다. |

미확정 외부 전제는 tenant entitlement, API key, webhook endpoint와 실제 provider wire
계약이다. 따라서 live provider dispatch와 production credential은 이 문서의 범위에
포함하지 않았다.

## SPW writer gate

| Gate | 결과 | 증거 |
|---|---|---|
| SPW-01 source ledger | PASS | 위 근거 원장, live issue/GNO/local pattern 및 미확정 전제를 기록했다. |
| SPW-02 completeness | PASS | 문제·범위·비목표·대안·아키텍처·domain/API·failure convergence·호환성·acceptance·DoD를 모두 읽어 확인했다. |
| SPW-03 Korean naturalness | PASS | `audit-korean-terms.mjs`가 대상 문서에서 0 findings를 반환했다. |
| SPW-04 traceability | PASS | 아래 acceptance traceability 표에서 Issue #526 조건과 구현 경계를 연결했다. |
| SPW-05 final readback | PASS | 문서 전체 readback, `git diff --check`, TODO/TBD/FIXME 검색, SHA-256 재계산을 완료했다. |

## 독립 관점 검토

| Priority | Lens | 결과·근거 | 필요한 수정 |
|---|---|---|---|
| P0/P1 | Performance | PASS. canonical v1, UTF-8/NFC/UTC/decimal 정규화, golden digest, 100/500 envelope, 50,000 후보 상한, 5초 deadline, fixed 4+queue 8, lock/index/timeout, outbox 4·10·30초·5회, DST, metrics를 확인했다. | 없음 |
| P0/P1 | Stability | PASS. fixed lock order와 `affectedRows == 1`, inbox `>/=/<` monotonic CAS와 `RETRY_EXHAUSTED`, generation 상태, fenced `STARTED`/provider ACK/`DELIVERY_UNKNOWN`, DB-clock sweep, cancellation·shutdown·readiness·permit/lease release를 확인했다. | 없음 |
| P0/P1 | Security | PASS. route/scope/fingerprint idempotency, target binding, HMAC signed context와 5분 replay window, closed deserialization/limits, loopback, role fixture, redaction allowlist/canary를 확인했다. | 없음 |
| P0/P1 | Operator/Ops | PASS. health/Prometheus, bounded labels, request ID audit, readiness/liveness, disposable schema, rollback, diagnostics 경로와 Testcontainers `PENDING` 경계를 확인했다. | 없음 |
| P0/P1 | Developer/API | PASS. 독립 child module, BOM-only 소비자 계약, normalized adapter ABI, immutable plan·approval/CAS·swap 계약, stable error matrix, Kotlin/Java 25/test pattern을 확인했다. | 없음 |
| P0/P1 | User/caller | PASS. manager/worker read-model allowlist, role별 command matrix, stale caller `409`, idempotent replay, cursor/read-only route, change impact·gap·cost·fairness·reason 표시를 확인했다. | 없음 |

### 초기 blocker와 수리

초기 독립 검토에서 발견한 P1은 다음과 같았고 모두 문서에 수리했다.

1. canonicalization·계산량·lock order·timeout·outbox bound 부재 → 정규화 v1,
   envelope/deadline, fixed lock/index/timeout, queue/lease/backoff 수치와 probe 추가
2. inbox/replan/outbox/lifecycle 상태 불충분 → monotonic revision CAS, terminal
   retry state, paired effect state machine, fenced provider ACK와 restart/sweep 규칙 추가
3. idempotency route/target fingerprint 부재 → `(method, route, demoScope, key)`와
   canonical command fingerprint/no-write conflict 추가

최신 통합 결과는 P0=0, P1=0이며, 위 P2 권고도 DTO bound, DST, metrics, replay,
redaction, diagnostics, role/rollback 계약으로 반영했다.

## Acceptance traceability

| Issue #526 조건 | 설계 근거 |
|---|---|
| overlap/unavailable/skill/rest/started hard rule | Domain planner hard-constraint 순서, bounded reason code, acceptance 1 |
| revision 확인 전 recommendation no-write | immutable plan, approval/swap CAS, persistence lock/affected-row 계약, acceptance 4–5 |
| availability/sick/demand/swap/shift-start event | event fixture 목록, inbox/replan generation, convergence table, acceptance 6 |
| gap/cost/fairness/reason/revision/change impact UI | plan/read-model domain과 caller route/allowlist, acceptance 10 |
| timezone/concurrency/post-start/webhook ordering | UTC/ZoneId·DST 정책, lock/CAS, started/pin, HMAC/inbox ordering, acceptance 7–8 |
| Java 25 virtual thread lifecycle/cancellation/failure | capability selection, bounded executor/semaphore, shutdown/release contract, acceptance 8 |
| deterministic fake/fixture without tenant/API/webhook | adapter port, fake profile, non-goals, capability selection, acceptance 12 |

## Gate result

- **PASS — Step 2-R complete**
- P0: `0`
- P1: `0`
- P2/P3: `0` unresolved; deferred operational/provider concerns are explicit non-goals or
  implementation verification items, not design blockers.
- 구현·Testcontainers 실행 증거는 구현 단계에서 수집한다. 이 문서는 설계 승인 이후
  plan 작성으로 넘길 준비가 되었지만, 사용자가 작성된 설계 문서를 확인하기 전에는
  plan/code 단계로 진행하지 않는다.

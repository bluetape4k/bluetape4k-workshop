# Issue #527 Last-Mile Routing 구현 검토

- 검토일: 2026-08-24
- 대상: `feat/issue-527-last-mile-routing`
- 기준: Issue #527, 설계·계획 review, 현재 구현 diff, live Gradle/README/smoke 결과
- 검토 방식: Performance, Stability, Security, Operator/Ops, Developer/API,
  User/caller 여섯 관점과 main integration
- 판정: `DONE (synthetic reference scope)`
- 우선순위: P0=0, P1=0, P2 후속 항목만 남김

## 구현 범위와 DoD

`optimization/last-mile-routing` 모듈을 추가하고 고정 travel matrix와 deterministic
planner, PostgreSQL 권위 저장소, normalized provider callback, inbox/outbox,
approval CAS, event burst coalescing, virtual-thread lifecycle, HTTP API와 CSP-safe
redacted browser projection을 연결했다. 새 모듈은 `bluetape4k-dependencies` BOM만
사용하며 `:optimization-planning-contracts` 구현 클래스에 의존하지 않는다.

테스트는 domain/planner/provider, `bluetape4k-exposed` repository architecture와
PostgreSQL Testcontainers, callback duplicate/stale revision, event burst,
approval no-write retry, virtual-thread lifecycle, HTTP/browser 계약을 포함한다. 새
테스트의 비교·null·예외·타입 검증은 `io.bluetape4k.assertions`를 사용하며,
repository 타입 검증은 `shouldBeInstanceOf<JdbcRepository<*, *>>()`로 표현한다.

## 여섯 관점 검토

| 관점 | 결과 | 구현 근거 | 남은 범위 |
|---|---|---|---|
| Performance | P0/P1=0 | bounded jobs/vehicles/stops/edges, immutable matrix O(1) lookup, deterministic sort와 finite score | 실제 대규모 benchmark receipt는 P2 후속 |
| Stability | P0/P1=0 | `AuditableLongIdTable`/`LongJdbcRepository` adapter, plan/job/carrier/matrix CAS, callback inbox digest, outbox lease/fence/retry/dead-letter, virtual-thread admission fence | 프로세스 재시작 뒤 in-memory provider submission 재구성은 production 보장이 아니며 P2 후속 |
| Security | P0/P1=0 | bounded ASCII value class, constant-time SHA-256 digest 비교, redacted read model, CSP, DOM `textContent`/`createElementNS`, raw payload·주소·고객·secret 미노출, SHA-256 ETag | production auth/CSRF/tenant 경계는 범위 밖 |
| Operator/Ops | P0/P1=0 | Actuator/Micrometer 의존성, redacted audit, bounded retry 상태, explicit provider outage/conflict 결과, orderly executor shutdown | 실제 Prometheus dashboard와 운영 migration은 P2 후속 |
| Developer/API | P0/P1=0 | root BOM-only, 자동 module registration, `bluetape4k-exposed` facade/adapters, normalized provider port, `bluetape4k-assertions` 테스트, README/workflow/stale 등록 | 실제 provider wire contract는 후속 adapter 범위 |
| User/caller | P0/P1=0 | plan/replan/approve/callback/event/reconnect endpoint, Idempotency-Key, ETag/304, explicit conflict, capacity/window/skill/ETA/pin/revision projection | full browser/MockMvc runtime journey는 P2 후속 |

## Main integration

| 질문 | 판정 | 증거 |
|---|---|---|
| planner가 PostgreSQL 권위를 우회하는가? | PASS | proposal/committed stop 분리와 Exposed repository facade, approval transaction CAS |
| duplicate/conflicting/stale callback이 route history를 오염시키는가? | PASS | provider/event envelope 일치 검증, canonical digest, unique inbox, stale audit-only |
| pickup-before-delivery, capacity, window, skill, started pin이 보존되는가? | PASS | deterministic planner hard constraints와 approval 재검증 |
| approval 재시도가 partial write를 만드는가? | PASS | plan state CAS 후 committed stop/outbox를 같은 transaction에서 기록하고 stale retry no-write 테스트 |
| event burst가 generation을 폭증시키는가? | PASS | traffic/pickup-window 공통 coalescing key와 duplicate digest 처리 |
| browser가 raw provider/customer 데이터를 렌더링하는가? | PASS | redacted projection, synthetic coordinate, CSP/DOM source contract |
| workspace 등록이 빠졌는가? | PASS | `projects`, README pair, Examples workflow, optimization/stale smoke 등록 |

## 검증 증거

| 검증 | 결과 |
|---|---|
| `./gradlew :optimization-last-mile-routing:test --no-daemon --max-workers=1 --console=plain` | 24 tests GREEN |
| `./gradlew detekt --no-daemon --max-workers=1 --console=plain` | root task GREEN; module-local detekt는 repository policy상 N/A |
| `node scripts/validate-readme-language.mjs` | offenders 0, totalHits 0 |
| `node scripts/validate-readme-parity.mjs` | failures 0 |
| `bash scripts/smoke-validate.sh stale-check` | stale refs 없음, required modules 등록, broken image 없음 |
| `bash scripts/smoke-validate.sh optimization` | planning/field-service/last-mile test group BUILD SUCCESSFUL |
| `git diff --check` | 오류 없음 |

## P2 후속과 비목표

- 실제 Timefold/OSRM, GPS/geocoding/traffic, carrier API와 credential 연동은
  deterministic provider 뒤의 별도 adapter/contract 작업이다.
- production authentication, CSRF, tenant isolation, rate limiting, schema
  migration/rollback은 이 synthetic loopback reference의 계약이 아니다.
- outbox payload는 현재 프로세스의 normalized provider submission map을 사용한다.
  프로세스 재시작 직전 proposal이 이미 저장된 경우의 lease replay 외에, poll 전에
  죽은 프로세스의 입력 재수화는 별도 durable submission/input 설계가 필요하다.
- browser 검증은 정적 DOM/CSP 계약이며 실제 브라우저·네트워크 journey와 benchmark,
  운영 Prometheus dashboard receipt는 후속 검증이다.

위 항목을 현재 구현의 PASS로 승격하지 않는다. 따라서 이번 검토의 `DONE`은
synthetic reference DoD에 한정하며 PR 생성·push·merge·Epic 종료 승인을 의미하지
않는다.

## 문서·운영 게이트

- 한국어 reader-facing 문서와 README locale parity를 유지했다.
- code/API/command/URL token은 원문을 보존했다.
- `git diff --check`와 README language/parity/stale 검사를 재실행했다.
- 구현 branch의 PR/CI/review/mergeability는 PR 생성 시점에 새로 읽어야 한다.

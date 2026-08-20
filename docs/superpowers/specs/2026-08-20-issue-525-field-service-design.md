# Issue #525 Field Service Dispatch 설계

- 날짜: 2026-08-20
- 저장소: `bluetape4k/bluetape4k-workshop`
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/525
- 상위 Epic: https://github.com/bluetape4k/bluetape4k-workshop/issues/523
- 작업 브랜치: `feat/issue-525-field-service`
- 대상 모듈: `optimization/field-service-dispatch`

## 결정 요약

이 예제는 `optimization/planning-contracts`와 분리된 Spring Boot 애플리케이션
모듈로 만든다. #524의 내부 Kotlin 구현을 직접 import하지 않고, 버전 있는
HTTP/fixture 계약을 integration seam으로 사용한다. 기본 실행과 CI는 외부
네트워크·Timefold tenant·API key·지도 provider가 필요 없는 deterministic fake를
사용한다.

Epic #523은 반드시 하나의 stacked PR에 종속시키지 않는다. 각 child 예제는
`develop`에서 독립적으로 시작할 수 있고, 유사한 운영 planning 예제를 순차적으로
추가하는 train으로 관리한다. #525는 이후 #526/#527이 재사용할 수 있는 행위
계약과 failure-fixture를 문서로 고정하지만, 새 공통 라이브러리를 선행 조건으로
만들지 않는다.

## 배경과 현재 증거

Issue #524는 PostgreSQL inbox/outbox, callback idempotency, stale revision 거부,
aggregate version 재검증을 제공하는 실행 가능한 Spring Boot 앱이다. 현재 핵심
클래스와 서비스는 `internal` visibility이며, read model은 provider raw payload와
credential을 노출하지 않는다. 따라서 `implementation(project(":optimization-planning-contracts"))`
로 내부 구현을 재사용하는 것은 모듈 경계를 우회하고 독립 예제라는 목표와 맞지
않는다.

Timefold 공식 Field Service Routing 문서는 고객 방문을 technician/vehicle에
배정하면서 availability, time window, skill, work hours를 다루고, Platform의
real-time planning 문서는 disruption 시 freeze/pin과 revision 흐름을 별도로
관리하도록 설명한다.

- https://docs.timefold.ai/field-service-routing/latest/introduction
- https://docs.timefold.ai/timefold-platform/latest/guides/responding-to-disruptions-with-real-time-replanning
- https://docs.timefold.ai/timefold-platform/latest/api/receiving-model-api-results/server-sent-events

이 문서는 위 기능을 production integration 증거로 해석하지 않는다. live provider가
없으므로 fixed travel-time matrix와 recorded callback fixture만 사용한다.

## 목표

1. synthetic 방문과 worker를 사용한 browser 기반 dispatcher reference application을
   추가한다.
2. qualified worker, availability, time window, started-visit pin을 hard constraint로
   고정한다.
3. urgent visit, worker unavailable, no-show, travel-time change를 versioned event로
   처리하고 deterministic replan을 제공한다.
4. proposal과 최종 dispatch command를 분리한다. approval/confirmation 직전에 현재
   visit·worker version을 PostgreSQL에서 다시 검증한다.
5. duplicate urgent event, approval 중 sick call, stale callback, restart/replay를
   재현 가능한 fixture와 테스트로 고정한다.
6. assigned/unassigned 방문, score, constraint reason, plan revision, manual pin과
   approval 상태를 정적 browser UI에 표시한다.
7. Java 25, 검증된 Bluetape virtual-thread runtime, Exposed/PostgreSQL 테스트와
   기존 `bluetape4k-dependencies` BOM을 재사용한다.

## 비목표

- patient record, diagnosis, insurance, clinical advice 또는 PHI 저장
- 실제 주소, geocoding, map tile, GPS, traffic provider와 production route quality
- Timefold Platform tenant/API key/webhook을 CI 또는 기본 실행의 전제 조건으로 사용
- autonomous dispatch publication 또는 provider 결과를 domain authority로 승격
- Redis, Kafka, 별도 solver dependency, 공통 optimization SDK 추가
- #526 이후 예제가 사용할 공통 library를 이번 child에서 선행 추출

## 선택지와 결정

### 선택지 A — #524 앱에 기능을 직접 추가

내부 service와 persistence를 그대로 재사용할 수 있다. 그러나 공통 planning 계약과
Field Service 상태 모델이 한 Spring context에 결합되고, 후속 child가 같은 모듈을
수정해야 하므로 독립 예제와 순차 train의 경계가 흐려진다. 채택하지 않는다.

### 선택지 B — #524에서 public SPI를 먼저 추출

새 모듈이 public planning facade를 의존하게 만들 수 있다. 다만 이미 병합된 #524의
API/자동 구성/serialization 경계를 재설계해야 하며, 공통화가 실제 두 소비자에서
반복되는지 확인하기 전에 선행 추출이 발생한다. 향후 #526/#527에서 동일 seam이
반복될 때 별도 child로 검토한다.

### 선택지 C — 새 모듈 + 버전 있는 HTTP/fixture seam (채택)

`optimization/field-service-dispatch`가 Field Service domain과 deterministic planner를
소유한다. `PlanningContractsHttpAdapter`는 proposal을 만들지 않고 #524의
submission·callback idempotency·revision lifecycle만 검증한다. worker별 route와
assigned/unassigned 결과는 `DeterministicFieldServicePlanner`와 Field Service
fixture가 소유한다. 이 방식은 실행 가능한 독립 예제를 유지하면서 #524와의
계약을 검증하고, 실제 provider가 없어도 CI를 안정적으로 수행한다.

## 모듈과 런타임 구조

```text
browser (synthetic SVG map/timeline)
        │ REST read model + commands
        v
FieldServiceController
        │ validation / authorization boundary
        v
FieldServiceApplicationService
        ├── Visit/Worker repositories (PostgreSQL)
        ├── Event idempotency + plan audit
        ├── DeterministicFieldServicePlanner (proposal authority)
        ├── PlanningContractsHttpAdapter (optional lifecycle fixture)
        └── Approval/Dispatch validator
                 │ current version + started/manual pin CAS
                 v
        committed dispatch projection
```

모듈은 다음 진입점을 가진다.

- `FieldServiceDispatchApplication`: Spring Boot + `ConfigurationPropertiesScan`
- `domain/`: value object, visit/worker/plan 상태, event와 constraint 결과
- `application/`: command, replan, approval, dispatch confirmation, query
- `adapter/fake/`: fixed matrix 기반 deterministic planner와 Field Service result fixture
- `adapter/http/`: #524 lifecycle contract mapper. 실제 tenant 호출은 기본 비활성
- `persistence/`: Exposed table, record, repository, schema fixture
- `web/`: JSON REST controller, redacted DTO, exception handler
- `src/main/resources/static/field-service/index.html`와 `field-service.js`: 정적 browser console

모든 public HTTP 응답은 synthetic read model만 반환한다. raw provider body, callback
signature, secret, JDBC URL, 전체 outbox payload는 저장·로그·응답에 포함하지 않는다.
score는 finite한 `hardScore`, `softScore`, `assignedCount`, `unassignedCount`로
제한된 숫자 구조만 반환하고, provider의 자유 형식 `scoreSummary`와 설명 문자열은
저장하거나 read model로 전달하지 않는다.

기본 앱은 `demo` profile에서만 dispatcher route를 등록하고 `server.address=127.0.0.1`로
바인딩한다. mutation endpoint는 `X-Demo-Operator: true`와 `Idempotency-Key`를
요구하고, CORS를 외부 origin에 열지 않는다. 이는 인증 시스템이 아니라 로컬
workshop 경계다. 비-demo 환경에서 production authentication/CSRF를 제공한다고
주장하지 않으며, route를 노출하지 않는다.

입력과 실행량은 다음 상한으로 고정한다.

- JSON body 256 KiB, worker 100개, visit 500개, route stop 500개
- coordinate 100개와 matrix cell 10,000개, sparse edge 10,000개
- worker당 skill 20개, availability window 20개, JSON depth 12와 문자열 240자
- explanation 20개, 각 240자, event key/`Idempotency-Key` 200자, command event 1회당 1개
- travel time은 finite한 non-negative millisecond만 허용하고 coordinate/edge ID는
  allowlist 형식으로 제한한다.
- planner는 미리 만든 skill/availability index와 O(1) matrix lookup을 사용해
  `O(visit × worker + edge)` 범위로 실행한다.
- blocking JDBC/HTTP는 Bluetape virtual-thread executor를 사용하고, CPU-bound planner는
  별도 bounded executor와 aggregate별 단일-flight semaphore를 사용한다. replan
  queue는 8개, CPU worker는 4개, semaphore 대기와 실행 timeout은 각각 5초로
  제한한다. queue 포화는 durable outbox에 `REPLAN_REJECTED`를 남기고 HTTP에는
  429를 반환한다. shutdown은 admission close → in-flight quiescence → executor
  drain 순서로 30초 안에 완료하며, timeout/cancellation 시 permit을 반드시 반환한다.

## 도메인 모델

### Worker

- `workerId`, 표시용 synthetic name
- `skills: Set<Skill>`
- UTC 기준 availability window 목록
- `version`, `workerScheduleRevision`, `unavailable` 상태
- 현재 route에서 이미 시작한 visit의 pin 정보

### Visit

- `visitId`, synthetic `coordinateId`
- `requiredSkill`
- `windowStart`, `windowEnd`, `serviceDuration`
- `priority` (`NORMAL`, `URGENT`)
- `status` (`UNASSIGNED`, `ASSIGNED`, `CANCELLED`, `NO_SHOW`, `COMPLETED`)
- `version`, `startedAt`, `startedPin`, `manualPin`

`startedPin`은 시작된 방문의 worker와 route 순서를 변경할 수 없음을 뜻한다.
`manualPin`은 dispatcher가 시작 전에 설정할 수 있으며 `visit.pin`/`visit.unpin`
command가 version을 증가시킨다. 시작된 방문의 pin은 해제할 수 없다.

### PlanProposal

- `planId`, `planRevision`, `parentRevision`
- `providerRequestId`와 해당 request 범위의 `providerRevision`
- `requestGeneration`과 dataset/plan binding
- 기준 데이터 시점의 visit/worker version map
- assigned visit와 worker별 ordered route
- unassigned reason (`MISSING_SKILL`, `UNAVAILABLE`, `TIME_WINDOW`, `TRAVEL_TIME`,
  `PIN_CONFLICT` 등)
- `hardScore`, `softScore`, 제한된 constraint explanations
- 상태 (`DRAFT`, `APPROVED`, `REJECTED`, `STALE`)

`scoreSummary`는 로컬 `FieldServiceScoreSummary` 구조로만 변환한다. explanation은
`MISSING_SKILL`, `UNAVAILABLE`, `TIME_WINDOW`, `TRAVEL_TIME`, `PIN_CONFLICT` 같은
닫힌 `ConstraintReasonCode`와 synthetic visit ID만 보존하고 provider 원문, HTML,
주소, credential처럼 해석되지 않은 문자열은 거부한다.

### Event와 audit

event는 `(aggregateType, aggregateId, eventKey)`를 idempotency key로 사용하고
canonical payload의 SHA-256 digest를 함께 저장한다. 같은 key와 같은 digest는
no-op으로 수렴한다. 같은 key에 다른 digest가 들어오면 `EVENT_KEY_REUSED` conflict를
반환하고 side effect를 만들지 않는다. payload에는 synthetic planning field만 넣고,
append-only audit에는 event 종류, 현재 version, plan revision, decision, redacted
reason만 기록한다.
canonical JSON은 UTF-8, 정렬된 object key, 중복 key 거부, UTC ISO-8601 시간과
정규화된 finite 숫자를 사용해 직렬화한 뒤 digest한다. 이 규칙을 벗어난 payload는
digest 비교 전에 4xx로 거부한다.

지원 event는 다음과 같다.

| 이벤트 | 의미 | 재계획 효과 |
|---|---|---|
| `visit.created` | 새 방문 등록 | 해당 기준 데이터에 방문 추가 |
| `visit.cancelled` | 방문 취소 | 기존 배정 제거 |
| `visit.urgent` | 우선순위 상승 | duplicate key는 no-op |
| `visit.pin` / `visit.unpin` | 시작 전 수동 고정/해제 | version 증가, started pin은 해제 불가 |
| `worker.unavailable` | sick call 포함 | worker route에서 미배정 처리 |
| `visit.no_show` | 현장 no-show | 이후 route 영향만 재계산 |
| `travel_time.updated` | fixed matrix 값 변경 | 영향 구간 재계산 |

## 계획과 최종 명령 흐름

1. command service가 현재 aggregate version과 event key를 검증하고 event를 원자적으로
   저장한다.
2. replan은 일관된 visit/worker 기준 데이터를 생성하고 Field Service plan stream의
   다음 `planRevision`과 `parentRevision`을 기록한다. 각 proposal은 해당 기준
   데이터의 version vector와 `requestGeneration`을 함께 보존한다.
3. deterministic planner는 다음 규칙으로 결과를 생성한다.
   - urgent 우선, 이후 `windowStart`, `visitId` 오름차순
   - required skill과 worker availability를 먼저 검사
   - fixed matrix의 이동 시간과 service duration을 포함해 time window를 검사
   - `startedAt` 또는 `pinned`인 기존 방문은 원래 worker/순서를 유지
   - 만족하지 못한 방문은 자동 배정하지 않고 명시적인 unassigned reason을 남김
4. `PlanningContractsHttpAdapter`가 사용하는 callback fixture는 canonical raw body를
   만들고 test signature를 붙인다. 서명, provider, `providerRequestId`,
   `requestGeneration`, `planId`/`datasetId` binding을 상태 변경 전에 확인한다.
   unsigned·unknown·mismatch callback은 inbox/plan/audit를 변경하지 않는다.
5. callback의 `providerRevision`은 `(provider, providerRequestId)` 범위에서만
   단조성을 비교한다. 서로 다른 request의 revision을 비교하지 않는다. Field Service
   `planRevision`은 local plan stream에서만 비교하며, 이미 superseded된
   `requestGeneration`의 callback은 `STALE_REQUEST_GENERATION`으로 감사한다.
6. approval은 proposal 상태만 변경한다. 영향을 받는 visit/worker row를 안정된
   순서로 expected-version 조건부 update하고 update 개수가 vector와 다르면 전체
   transaction을 rollback한다. 이 단계에서는 업무용 visit `version`, worker
   `version`, `workerScheduleRevision`을 증가시키지 않고 proposal 상태와 audit만
   commit한다. row별 N+1 재확인 대신 set-based CAS를 사용한다.
7. proposal assignment는 immutable `field_service_plan_assignments`에 남긴다.
   dispatch confirmation 단위는 한 worker의 route 전체다. confirmation은 모든
   route visit의 현재 version, worker eligibility `version`,
   `workerScheduleRevision`을 set-based expected-version 조건으로 비교한 뒤
   `field_service_dispatch_assignments`에 route 전체를 원자적으로 commit한다.
   한 worker route의 일부만 commit하지 않으며, 다른 worker route는 별도로 확인할
   수 있다.
8. route commit이 성공하면 해당 worker의 `workerScheduleRevision`을 증가시키고,
   같은 worker의 이전 schedule revision을 참조하는 미확정 proposal assignment는
   `STALE`로 표시해 재계획 대상으로 만든다. 다른 worker의 committed route는
   되돌리지 않는다.
9. restart/replay 시 bounded outbox batch와 event digest를 다시 처리해 하나의
   terminal history로 수렴한다.

`planRevision`과 `providerRevision`은 서로 다른 namespace다. provider revision은
각 provider request의 callback 순서만 설명하고, 최종 업무 상태의 revision authority가
아니다.

callback과 replan의 상태 전이는 다음처럼 고정한다.

| 입력 | 현재 상태 | 결과 | accepted 상태 변경 |
|---|---|---|---|
| 동일 request/event/digest | any | `DUPLICATE` no-op | 없음 |
| 동일 event/다른 digest | any | `EVENT_KEY_REUSED` conflict | 없음 |
| 같은 request의 낮은 provider revision | `DRAFT`/`APPROVED` | `STALE_REVISION` audit | 없음 |
| superseded request generation | `DRAFT`/`APPROVED` | `STALE_REQUEST_GENERATION` audit | 없음 |
| 현재 request의 최신 callback | `DRAFT` | proposal result 반영 | 해당 plan만 갱신 |
| approval 중 version mutation | `DRAFT` | `VERSION_CONFLICT` 409 | transaction 전체 rollback |
| 이미 committed된 같은 worker의 route | worker-route confirmation | `SCHEDULE_CONFLICT` 409 | 해당 route 전체 rollback, 다른 worker commit 유지 |

fixed travel-time matrix는 coordinate ID를 key로 하는 immutable revision이다. update
command는 새 matrix revision을 원자적으로 추가하고 `travel_time.updated` event를
만든다. planner는 O(1) edge lookup을 사용하며 누락 edge는 임의의 외부 호출 없이
`TRAVEL_TIME` unassigned reason으로 남긴다. 같은 matrix revision에 duplicate update가
들어오면 event digest 규칙을 따른다.

## HTTP 경계

### Dispatcher API

- `GET /field-service`: static browser console
- `GET /api/field-service/visits`: redacted visit list와 assignment 상태
- `GET /api/field-service/workers`: worker availability와 route summary
- `GET /api/field-service/plans/{revision}`: score, reason, route, unassigned view
- `POST /api/field-service/visits`: synthetic visit 생성
- `POST /api/field-service/visits/{id}/cancel`
- `POST /api/field-service/visits/{id}/urgent`
- `POST /api/field-service/visits/{id}/pin`
- `POST /api/field-service/visits/{id}/unpin`
- `POST /api/field-service/visits/{id}/no-show`
- `POST /api/field-service/workers/{id}/unavailable`
- `POST /api/field-service/travel-times`
- `POST /api/field-service/plans/replan`
- `POST /api/field-service/plans/{revision}/approve`
- `POST /api/field-service/dispatch/workers/{workerId}/confirm`

입력 DTO는 field length, time window 순서, non-negative duration/version을 검증한다.
mutation body는 256 KiB를 넘을 수 없고 worker/visit/matrix/event 수 상한도 확인한다.
조회 API는 `limit`/cursor를 제한하고 `ETag`와 `If-None-Match`로 동일 revision 응답을
재전송하지 않는다. 오류 응답은 상태 코드와 안정적인 conflict code만 포함하며 내부
예외·SQL·provider 상세를 반환하지 않는다. mutation에는 `X-Demo-Operator: true`와
`Idempotency-Key`가 없으면 상태를 변경하지 않는다.

### #524 HTTP/fixture seam

선택적 adapter는 #524의 normalized request/callback contract를 lifecycle fixture로
감싼다. 이것은 Field Service assignment result contract가 아니다. Field Service는
추가 binding을 가진 `FieldServiceCallbackEnvelope`를 먼저 검증하고, 그 preflight가
실패하면 #524 endpoint를 호출하지 않으며 #525 상태도 변경하지 않는다.

- submission에는 `aggregateId`, `aggregateVersion`, `datasetId`, `provider`만 보낸다.
- field-service 기준 데이터는 `datasetId`와 local `planId`로 연결하고 raw 기준 데이터를
  provider response에 넣지 않는다.
- local callback envelope는 `provider`, `eventId`, `planningRequestId`,
  `providerRequestId`, `providerRevision`, `requestGeneration`, `planId`, `datasetId`,
  `status`, 구조화된 `FieldServiceScoreSummary`, `ConstraintReasonCode` 목록을
  가진다. 실제 assignment는 Field Service result fixture가 별도로 보관한다.
  #524 wire DTO로 보낼 때는 `planningRequestId`와 #524가 지원하는 필드만 mapping하며,
  #524의 자유 형식 score/explanation은 strict parser를 통과한 구조화 값으로만
  local state에 반영한다.
- #524 endpoint에는 현재 wire `contractVersion` field가 없으므로 “versioned API”라고
  주장하지 않는다. integration fixture는 `planning-contracts-commit-80c1f95`와
  `field-service-planning-fixture-v1`을 pin하고, additive unknown field와 incompatible
  fixture를 contract test에서 거부한다.
- callback fixture는 canonical raw body와 명시적인 test signature를 요구한다. 서명
  검증·provider/request/plan binding 전에는 Field Service inbox, plan, audit를 쓰지
  않는다. #524 endpoint를 호출한 뒤 #524 자체 inbox/audit에 provider mismatch가
  남는 것은 #524의 정상 계약이며, 이를 #525 local state 변경과 혼동하지 않는다.
  기본 fake 경로는 외부 callback HTTP endpoint를 만들지 않고 내부 fixture 호출만
  사용한다.
- `POST` 자동 재시도는 하지 않고, local outbox replay와 explicit reconciliation을
  신뢰 경계로 둔다.

실제 Timefold route payload 매핑과 webhook 인증은 entitlement/fixture 증거가 생긴
뒤 별도 child 또는 명시적인 follow-up으로 다룬다. 현재 모듈이 실제 route quality를
검증했다고 보고하지 않는다.

## Browser UI

UI는 기존 operations console 패턴처럼 정적 HTML을 classpath에서 읽어 제공한다.
외부 map SDK 없이 inline SVG 좌표 projection을 사용한다.

- 좌측: synthetic map과 route 선, worker별 색상, started/pinned marker
- 우측: timeline, assigned/unassigned visit, hard conflict reason
- 상단: current plan revision, hard/soft score, replan/approval 상태
- command panel: urgent, sick call, no-show, travel-time change, manual pin
- 승인 실패 시 현재 version과 proposal version을 나란히 표시

렌더링은 `textContent`와 명시적인 DOM API만 사용하며 `innerHTML`,
`insertAdjacentHTML`, `eval`과 문자열 기반 template 실행을 금지한다. SVG 좌표는
유한 숫자 범위로 검증하고 worker 색상은 고정 allowlist에서만 선택한다. 응답의
worker name, reason code, score 값은 모두 escape된 text node 또는 숫자 attribute로
삽입하며 외부 정적 `field-service.js`만 허용하는 CSP `script-src 'self'`를 적용한다.

REST 기준 상태가 권위이며, 현재 child에서는 foreground 2초 polling을 사용한다.
요청은 single-flight로 하나만 허용하고, 429/5xx에는 2초에서 10초까지 exponential
backoff를 적용한다. hidden tab은 polling을 중단하고 `visibilitychange` 뒤 즉시
한 번만 재조회하며 기존 timer를 취소하고 visibility epoch을 증가시킨다. visits,
workers, plans는 각각 composite 기준 데이터 revision을 ETag 입력으로 사용하고,
304에서는 기존 body를 유지한다. 로컬 SSE 알림 adapter는
후속 확장 seam으로 문서화하되 필수 runtime dependency로 만들지 않는다.

## 영속성

| 테이블 | 역할 |
|---|---|
| `field_service_workers` | skill, availability, eligibility version, worker schedule revision, unavailable 상태 |
| `field_service_visits` | visit synthetic data, status, assignment, version, pin |
| `field_service_plans` | proposal revision, score, parent, state, redacted summary |
| `field_service_plan_assignments` | immutable proposal의 worker route 순서와 기준 데이터 version/schedule revision |
| `field_service_dispatch_assignments` | approval 이후 worker route 단위 committed assignment의 권위 |
| `field_service_events` | event key unique idempotency, canonical payload digest와 payload 요약 |
| `field_service_audits` | append-only event/proposal/approval decision |
| `field_service_outbox` | replan/replay 작업과 제한된 retry 상태 |

`field_service_visits`는 현재 visit 상태와 version을 소유하지만 assignment의 최종
권위는 `field_service_dispatch_assignments`다. proposal 승인 전 assignment와
committed assignment를 같은 row에 덮어쓰지 않는다. approval은 전체 proposal을
승인할 수 있고, dispatch confirmation은 worker route 단위로 부분 완료할 수 있다.
한 route의 모든 stop이 성공할 때만 commit하며 하나라도 version 또는 eligibility
충돌이 나면 route 전체를 rollback한다. 다른 worker route의 committed 상태는
유지하고, 미확정 route는 proposal read model에 계속 표시한다.

다음 인덱스를 고정한다.

- events: unique `(aggregate_type, aggregate_id, event_key)`와 digest 조회
- outbox: `(status, next_attempt_at, id)` claim index
- plans: `(plan_id, plan_revision)` 및 `(state, plan_revision)`; plan history는
  plan당 100 revision으로 제한하고 keyset cursor와 `MAX_PAGE_SIZE=100`을 사용
- assignments: `(plan_id, plan_revision, worker_id, route_order)`와
  `(worker_id, worker_schedule_revision, route_order)` 조회, committed `(visit_id)` unique

approval/confirmation transaction은 set-based CAS와 bounded batch를 사용한다.
route CAS는 최대 500개 stop을 100행 chunk로 나누고 statement 5개 이내로 실행하며,
query count·lock wait·`EXPLAIN` index 사용을 fixture에서 검증한다. outbox claim batch는
10개를 넘지 않는다. 테스트 schema는 Exposed `SchemaUtils`
fixture로 초기화한다. production migration 도구는 이번 child에서 추가하지 않으며,
restart/replay 보장은 동일 schema version의 disposable workshop DB에만 적용한다.
column/table upgrade가 필요해지는 시점에는 reset 정책 또는 migration 도구를 별도
issue로 결정한다.

## Bluetape capability 선택

| 책임 | 재사용 capability | 선택 이유 | 미사용/제약 |
|---|---|---|---|
| 버전 | `bluetape4k-dependencies` BOM | consumer project의 유일한 version authority | 개별 Bluetape BOM/version pin 금지 |
| JDBC repository | `bluetape4k-exposed-core`, `bluetape4k-exposed-jdbc` | 기존 audited repository와 Exposed table 패턴 | custom SQL은 version compare/update에 한정 |
| JSON | repository의 Jackson 3 구성 | 닫힌 DTO와 redacted read model | provider raw payload 저장 금지 |
| 실행 | `bluetape4k-virtualthread-api` + JDK 25 runtime | blocking JDBC/HTTP 경계의 검증된 실행 모델 | JDK 21 provider 제외 |
| 관찰성 | `bluetape4k-micrometer`/logging | 낮은 카디널리티 event/decision metric | raw visit/provider payload label 금지 |
| 테스트 | `bluetape4k-testcontainers` PostgreSQL fixture, JUnit5/assertions | 실제 transaction/version/idempotency 증명 | 외부 Timefold/map credential 없음 |
| planning provider | local deterministic fake | CI와 재현 가능한 hard constraint 증거 | Timefold HTTP는 optional fixture/profile |
| admission/lifecycle | bounded planner executor + Bluetape virtual-thread lifecycle | CPU planner와 blocking I/O의 실행량 분리, shutdown drain | 무제한 raw thread/executor 생성 금지 |
| Redis/Kafka | 없음 | PostgreSQL event/outbox 계약만으로 충분 | 필요성이 반복 증명될 때 별도 child 검토 |

## 테스트 전략

### 단위/계약

- fixed matrix의 동일 입력 → 동일 plan revision/assignment/score
- skill, availability, time window, started pin hard constraint 위반 방지
- tie-break와 unassigned reason의 deterministic ordering
- #524 lifecycle request/callback fixture의 field limit, canonical signature와 redaction
- 동일 event key + 동일 digest는 no-op, 다른 digest는 `EVENT_KEY_REUSED` 409
- manual pin/unpin은 version을 증가시키고 started pin은 해제하지 못함
- immutable/versioned matrix는 O(1) lookup, missing edge는 `TRAVEL_TIME` reason으로 수렴

### PostgreSQL 통합

- event key 중복 삽입은 하나의 event와 하나의 side effect로 수렴
- event key payload 변경은 side effect 없이 `EVENT_KEY_REUSED`로 거부
- plan approval은 set-based expected-version CAS가 모두 일치할 때만 commit
- approval 중 sick call은 409와 audit을 남기고 stale proposal을 commit하지 않음
- stale callback/낮은 provider revision/request generation은 accepted plan을 덮어쓰지 않음
- dispatch confirmation은 한 worker route의 모든 committed row·audit·version을
  원자 처리하고, 같은 worker의 경쟁 route는 `SCHEDULE_CONFLICT`로 전체 rollback하며
  다른 worker의 committed 상태는 되돌리지 않음
- 동일 worker/time을 가리키는 두 proposal의 동시 route confirmation fixture에서
  먼저 성공한 route만 commit되고 나머지는 schedule revision conflict로 수렴
- worker restart/replay는 bounded outbox lease와 event idempotency를 통해 terminal
  history 하나로 수렴
- 동시 approval/sick-call fixture에서 row lock wait와 query count가 bounded contract를 지킴

### MVC/browser contract

- 명령 → replan → query → approval → dispatch confirmation 흐름
- invalid window/version, unknown id, duplicate urgent, pin/unpin, cancel/no-show 응답
- demo operator header 누락/거부, unsigned·wrong signature·provider mismatch callback
- oversized body/matrix/event와 초과 explanation이 상태 변경 없이 4xx로 거부됨
- read model에 credential, raw payload, secret, address/PHI, JDBC URL이 없는지 검증
- score/explanation에 secret·PII·HTML canary를 넣어 구조화 parser가 거부하고 상태가
  바뀌지 않는지 검증
- DOM XSS canary가 text node로만 렌더링되고 `innerHTML`/`eval` 경로가 없으며 CSP가
  적용되는지 검증
- static UI가 classpath에서 로드되고 API 기준 상태 shape와 일치하는지 검증

`small` fixture와 `max-envelope` fixture를 각각 고정한다. max-envelope는 100 worker,
500 visit, 10,000 matrix cell, 20 explanation 경계를 사용한다. benchmark는 wall-clock
절대값을 CI gate로 삼지 않고 planner input 상한, query/response bytes, Hikari active/
pending, lock wait, in-flight planner 수와 invariant 보존을 JSON artifact로 기록한다.
browser client fixture는 foreground polling 요청률, 304 비율, single-flight, hidden-tab
중단을 검증한다. benchmark JSON은 `schemaVersion`, `runId`, `fixture`, `warmup`,
`repetitions`, `queryCount`, `lockWaitMs`, `queueRejected`, `timeout`,
`cancellation`, `invariants`, `status`(`PASS`/`FAIL`/`UNAVAILABLE`)를 필수로 하고
`build/reports/field-service/benchmark.json`에 기록한다. invariant는 CI hard gate로
검증하고 wall-clock 수치는 report-only로 유지한다.

모든 PostgreSQL/Testcontainers 테스트는 repository의 `TestMutexService` 규칙에 따라
`--max-workers=1`로 순차 실행한다. 먼저 module test와 integration fixture를 실행한
뒤 optimization smoke와 전체 compile을 실행한다.

## 모듈 등록과 문서 표면

새 모듈을 추가하면 같은 change set에서 다음을 갱신한다.

- `optimization/README.md`, `optimization/README.ko.md`
- `settings.gradle.kts`가 자동 등록하는지 확인하고 `./gradlew projects`로 증명
- `.github/workflows/Examples.yml`의 container-backed test와 artifact path
- `.github/workflows/nightly.yml` smoke/full 그룹
- `scripts/smoke-validate.sh optimization`
- module `README.md`, `README.ko.md`, synthetic data와 provider 제약

새 Bluetape publication/BOM/catalog entry는 추가하지 않는다.

## 수용 기준 추적

| Issue #525 조건 | 설계 증거 | 검증 artifact |
|---|---|---|
| qualified skill/availability/time window hard constraint | deterministic planner + `unassigned reason` | unit/contract tests |
| started-visit pin | proposal 기준 데이터 + approval validator | PostgreSQL approval test |
| current visit/worker version 재확인 | set-based expected-version CAS와 committed projection | approval/dispatch MVC test |
| duplicate urgent event | unique event key + canonical payload digest | idempotency integration fixture |
| sick call during approval | worker version conflict | stale approval test |
| stale callback | provider request namespace + request generation gate | callback contract test |
| restart/replay | outbox lease + event key | restart convergence test |
| browser dispatcher map/timeline | static HTML + inline SVG projection | UI resource/contract test |
| synthetic-only delivery | DTO/table redaction, fixed matrix와 demo loopback 경계 | redaction/security assertions |

## 리스크와 후속 결정

- #524가 현재 public library가 아니므로 HTTP seam fixture가 실제 provider payload
  compatibility를 증명하지는 않는다. live tenant/API evidence가 생기면 adapter
  contract를 별도 review하고, 현재 fake 통과를 production integration PASS로
  승격하지 않는다.
- #524 endpoint에는 현재 wire schema version이 없으므로 merged commit과 local fixture
  version을 함께 pin한다. endpoint 변경 시 additive/incompatible fixture를 먼저
  검증하고, 자동으로 호환된다고 가정하지 않는다.
- SVG projection은 지도/traffic 정확도를 제공하지 않는다. 실제 route quality 주장은
  지도 provider와 Timefold dataset 증거가 있을 때만 별도 acceptance로 추가한다.
- demo loopback/operator header는 production auth 대체물이 아니다. 외부 네트워크에
  노출하거나 인증을 생략한 채 운영 환경에 재사용하지 않는다.
- #526/#527에서 동일한 revision/idempotency 코드가 반복되면 그때 public SPI 또는
  shared fixture 추출을 별도 issue로 판단한다. 이번 child에서 미리 공통화하지 않는다.

## 설계 상태

- 상태: 사용자 승인 완료, 구현 전
- 다음 gate: writer SPW-01..05 및 독립 설계 검토
- 구현 시작 조건: 승인된 설계 commit, implementation plan, plan review, TDD red test

# Issue #526 Staff Coverage 및 Shift Swap Control Center 설계

- 작성일: 2026-08-24
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/526
- Epic: https://github.com/bluetape4k/bluetape4k-workshop/issues/523
- 작업 유형: Type A Full Feature
- 대상 모듈: `optimization/shift-coverage`
- 기준 커밋: `79fd71d9a55f6b43ac78ee9bab55f9808931c8b8`
- 대상 환경: Kotlin 2.4.0, Java 25, Spring Boot 4.0.6, `bluetape4k-dependencies` BOM

## 1. 문제와 목표

Issue #526은 multi-site support center의 synthetic staffing 데이터를 사용해
coverage 계획과 사람이 확인하는 shift swap을 보여 주는 reference application을
요구한다. 목표는 planner가 만든 recommendation을 최종 배정으로 오해하지 않도록
분리하고, 현재 assignment revision을 다시 확인한 뒤에만 manager가 변경을 확정하는
운영 흐름을 실행 가능한 코드와 fixture로 고정하는 것이다.

다음 질문에 답하는 모듈을 만든다.

1. worker availability, skill, preference, coverage demand를 어떤 기준 데이터 묶음으로
   planner에 전달하는가?
2. overlap, unavailable time, skill, rest, started shift를 어떻게 hard rule로
   거부하는가?
3. sick call과 swap acceptance가 동시에 발생해도 어떤 revision을 기준으로
   no-write 또는 재계획으로 수렴하는가?
4. Timefold callback의 duplicate/out-of-order와 restart/retry가 어떻게 하나의
   terminal history로 수렴하는가?
5. manager와 worker에게 gap, fairness, reason, change impact를 어떻게 redacted
   read model로 보여 주는가?

## 2. 현재 근거와 용어

### 2.1 조사 근거

- Live Issue #526은 Employee Shift Scheduling, shift swap, hard constraints,
  revision 재검증, timezone/concurrency/post-start/webhook ordering fixture를
  요구한다.
- GNO 전역 query는 다음 자료를 반환했다.
  - `gno://bluetape4k-github/bluetape4k-workshop/issues/000526.md`
  - `gno://bluetape4k-wiki/research/2026-07-18-timefold-platform-server-reference-applications.md`
- 보존된 Timefold 연구는 Platform API와 custom Solver를 분리하고, PostgreSQL
  domain state를 상태 변경의 기준 데이터 원본으로 두며, callback 결과를 versioned
  proposal로 취급하라고 정리한다.
- `optimization/field-service-dispatch`는 deterministic planner, PostgreSQL
  revision/CAS, callback preflight, outbox fencing, redacted read model을 이미
  검증했다. 이 모듈의 경계와 테스트 패턴을 채택하되 field-service domain class나
  warehouse domain을 직접 공유하지 않는다.
- `optimization/planning-contracts`는 #524의 공통 contract 표면이다. 실제 tenant,
  API key, webhook이 없으므로 이 모듈은 구현 dependency 대신 normalized adapter
  port와 deterministic callback fixture만 사용한다.

### 2.2 Ecosystem capability selection

| 책임 | 선택한 Bluetape capability | 선택 근거와 검증 경계 |
|---|---|---|
| 버전 기준 | `bluetape4k-dependencies` BOM | consumer module이므로 개별 Bluetape 버전을 고정하지 않는다. |
| PostgreSQL persistence | `bluetape4k-exposed-jdbc`와 기존 Exposed repository 패턴 | worker/shift/assignment의 revision CAS와 Testcontainers 검증에 사용한다. |
| Provider/HTTP I/O | `bluetape4k-virtualthread-api`, `bluetape4k-virtualthread-jdk25`, 기존 `productionVirtualThreadHttpClientOf` 패턴 | Java 25 runtime만 허용하고 JDK 21 provider는 제외한다. 실제 provider는 fake/recorded fixture 뒤에 둔다. |
| 관측·로그 | 기존 Bluetape logging/observation 패턴 | metric label에는 tenant·worker·shift 식별자를 넣지 않고 request ID와 bounded code만 남긴다. |
| 동시성 검증 | 기존 JUnit 5, Kluent, `MultithreadingTester`/Testcontainers 패턴 | 새 dependency를 추가하지 않고 lock/CAS/restart fixture를 재현한다. |

`optimization/planning-contracts` 애플리케이션 구현은 직접 dependency로 가져오지
않는다. #524의 normalized adapter와 callback fixture 경계만 같은 의미로 재현하고,
실제 capability가 없으면 deterministic fake가 기본 경로가 된다.

### 2.3 용어

| 용어 | 의미 |
|---|---|
| worker | shift를 수행할 수 있는 synthetic 직원. skill, availability, preference를 가진다. |
| shift | site, 시작/종료 시각, 필요한 skill, demand 단위로 표현한 staffing 작업이다. |
| assignment | worker와 shift의 제안 또는 확정 관계다. `assignmentRevision`을 가진다. |
| pin | manager가 확정해 replan에서 이동할 수 없게 한 assignment다. |
| plan | immutable 기준 데이터 묶음에서 생성한 recommendation 묶음이다. 승인 전에는 business state를 변경하지 않는다. |
| swap | worker 간 assignment 변경 요청과 manager/정책 확인 결과다. |
| coverage gap | shift의 required headcount와 현재/추천 assignment 차이다. |
| change impact | 현재 확정 assignment와 proposal 사이의 추가·제거·이동 수와 영향받는 shift 목록이다. |

## 3. 범위와 비목표

### 포함

- synthetic worker, skill, availability, preference, site, shift demand, assignment,
  pin, swap, plan, event, audit, outbox, replan 상태
- deterministic planner와 normalized planning adapter port
- manager용 plan/coverage/swap read model과 demo mutation API
- PostgreSQL transaction, revision CAS, event inbox, idempotency, outbox lease/effect
- duplicate/out-of-order callback, sick call, concurrent swap, timezone boundary,
  started shift, restart/retry fixture
- `availability.changed`, `shift.demand_changed`, `worker.sick_called`,
  `swap.requested`, `swap.accepted`, `shift.started` event fixtures
- 영문/한글 README, smoke/workflow/validation matrix 등록

### 비목표

- payroll 계산, labor-law 법률 해석, HRIS production integration
- autonomous shift reassignment 또는 manager 확인 없는 assignment 변경
- 실제 Employee Shift Scheduling tenant, API key, webhook, production callback
- PHI, diagnosis, insurance, clinical advice, production workforce identity
- generic staffing library, cross-child common core, live fairness guarantee

## 4. 선택지와 결정

### 선택 A — 독립 child module (권장)

`optimization/shift-coverage` 안에 domain, planner, persistence, application, HTTP
adapter를 둔다. planner는 immutable `ShiftCoverageSnapshot`만 읽고
`ShiftCoveragePlan`을 반환한다. 승인 service가 현재 worker/shift/assignment revision을
transaction 안에서 재검증한다.

- 장점: #526을 독립적으로 빌드·테스트할 수 있고, sibling child 간 책임 경계가
  명확하다.
- 단점: field-service와 일부 persistence/HTTP 패턴이 중복된다.
- 결정: reference application의 독립 실행성과 domain별 hard rule을 위해 채택한다.

### 선택 B — warehouse 또는 field-service 공통 core 추출

공통 planner/repository를 먼저 만들면 코드 중복은 줄지만, assignment/stock/route의
상태 의미가 달라 generic abstraction이 실제 계약을 숨길 가능성이 크다. 추출은
두 개 이상의 child가 동일한 의미와 테스트를 요구한다는 증거가 생길 때 별도 이슈로
다룬다. 현재는 거부한다.

### 선택 C — 실제 Timefold Platform 우선 연동

Platform API를 먼저 연결하면 외부 모델의 wire fidelity를 빠르게 확인할 수 있지만,
현재 tenant entitlement, API key, webhook endpoint가 없고 CI에서 network side effect를
허용하지 않는다. recorded fixture와 fake adapter를 먼저 고정한 뒤 실제 provider는
별도 승인·credential·배포 evidence가 있을 때만 추가한다.

## 5. 아키텍처와 책임 경계

```text
PostgreSQL worker/shift/assignment state
        │ 기준 데이터 + outbox
        ▼
Deterministic planner / #524-shaped adapter port
        │ proposal + callback fixture
        ▼
Plan audit + redacted read model ── browser console
        │ manager approval / swap acceptance
        ▼
PostgreSQL transaction: revision/CAS + assignment/pin + audit + outbox
```

### 5.1 Domain

- `Worker`: `workerId`, site, skills, availability windows, preference, revision,
  status(`ACTIVE`, `SICK`, `INACTIVE`)
- `Shift`: `shiftId`, site, start/end `Instant`, required skills, required headcount,
  priority, status(`PLANNED`, `STARTED`, `CLOSED`), revision
- `Assignment`: worker/shift pair, source(`PROPOSED`, `CONFIRMED`, `PINNED`), revision
- `SwapRequest`: source assignment, target worker, request status, expected revisions,
  idempotency key, terminal reason
- `ShiftCoveragePlan`: dataset version, plan revision, score, cost/gap/fairness metrics,
  change impact, allocation reasons, assignments, manual pins, status

All public IDs and bounded strings are validated at construction. A plan is immutable;
manager approval and explicit swap acceptance are the only operations allowed to change
assignment state; planner/callback/replay paths never do so. Neither command can move a
`STARTED` shift or a pinned assignment.

### 5.2 Planner

The planner applies hard constraints in this order:

1. shift and worker are in the same site;
2. shift interval is valid and does not overlap an existing assignment;
3. worker is available for the complete interval in the declared timezone;
4. worker has every required skill;
5. minimum rest between adjacent assignments is satisfied;
6. started shift and confirmed/manual pin remain fixed.

Remaining candidates are ordered by coverage gap reduction, preference match, fairness
delta, cost, and stable IDs. Coverage, fairness delta, and cost are signed `Long`
minor-units (no floating-point arithmetic); score addition is overflow-checked. Equal
score vectors compare `(shiftId, workerId, assignmentId)` lexically. Every rejection has
a bounded reason code such as
`OVERLAP`, `UNAVAILABLE`, `MISSING_SKILL`, `REST_RULE`, `STARTED_SHIFT`, `PINNED`, or
`NO_CANDIDATE`. The same normalized 기준 데이터 묶음 produces byte-identical assignments,
metrics, reasons, and digest.

#### 정규화·실행 상한

`shift-coverage-canonical-v1`을 기준 데이터와 proposal digest에 함께 저장한다.
정규화 규칙은 다음과 같다.

- 모든 문자열은 Unicode NFC, 양끝 공백 제거, UTF-8로 표현하며 ID/코드의 대소문자는
  보존한다. null과 빈 배열은 서로 다른 값으로 유지한다.
- 시간은 `Instant`의 UTC `Z` 표현으로 직렬화하고, site `ZoneId`는 별도 필드로
  보존한다. duration은 정수 milliseconds로 표현한다.
- 유한한 decimal만 허용하고 trailing zero와 음수 0을 제거한 plain decimal로
  표현한다.
- 객체 field는 schema 순서, map key는 Unicode lexical 순서, worker/shift/
  assignment/pin 배열은 각각 `(siteId, id)`와 `(shiftId, workerId)` 오름차순으로
  정렬한다. skill은 lexical 순서, availability는 `(start, end)` 순서로 정렬한다.
- canonical UTF-8 JSON을 SHA-256으로 digest하고, golden fixture에서 canonical bytes와
  digest를 함께 고정한다. 배열 순서나 provider callback 순서는 business history에
  의미가 없으므로 정규화 전에 안정적인 순서로 바꾼다.

기본 synthetic envelope와 계산량 상한은 다음과 같다.

| 항목 | 상한/정책 | 초과 결과 |
|---|---:|---|
| worker/shift/assignment/pin 수 | `100 / 500 / 500 / 500` | `INPUT_LIMIT_EXCEEDED`, no-write |
| worker별 skill/availability | `20 / 20` | `INPUT_LIMIT_EXCEEDED`, no-write |
| 후보 평가 수 | `50,000` (`MAX_SHIFTS * MAX_WORKERS`) | planner 중단, `PLANNER_LIMIT_EXCEEDED`, no-write |
| canonical request/plan body | `256 KiB` | HTTP `413`, no-write |
| planner deadline | `5 s` | `REPLAN_TIMEOUT`, no materialization |
| query page / provider response | `100 / 64 KiB` | page clamp 또는 `RESPONSE_TOO_LARGE` |

planner는 CPU 작업을 고정 worker 4개와 `ArrayBlockingQueue(8)`로 admission하고,
동일 dataset의 진행 중 replan은 하나로 합친다. queue가 가득 차면 `REPLAN_REJECTED`
(`429`)를 반환하며 business state를 쓰지 않는다. 상한과 deadline은 complexity
fixture와 timeout/cancellation fixture에서 검증한다.

정규화 adapter port는 `submit(PlanningRequest)`와 `accept(PlanningCallback)` 두 경로만
노출한다. `PlanningRequest`에는 `provider`, `datasetId`, `generationId`, `aggregateId`,
`siteId`, `aggregateRevision`, `canonicalizationVersion`, `snapshotDigest`, callback
binding만 포함하고 raw PostgreSQL row나 credential은 포함하지 않는다. `PlanningCallback`은
`provider`, `eventId`, `requestId`, `datasetId`, `generationId`, `aggregateId`, `siteId`,
`targetAssignmentId`, `providerRevision`, `status`, proposal digest, score summary,
bounded reason codes만 가진다. Fake adapter와
recorded fixture는 이 ABI를 그대로 사용하고, live Timefold/HTTP 구현은 같은 port 뒤에
둘 때만 별도 profile에서 활성화한다.

### 5.3 Persistence and application

PostgreSQL tables hold current worker/shift/assignment state, plans, pins, swap requests,
event inbox, idempotency rows, audit rows, replans, outbox messages, and outbox effects.
Every mutating transaction first claims its event/idempotency row when present, then
locks worker rows by `(siteId, workerId)`, shift rows by `(siteId, shiftId)`, assignment
rows by `(shiftId, workerId)`, plan rows, and swap rows in that fixed ascending order.
The same lock order applies to approval, swap acceptance, and event handling; queries do
not take these locks. Within every lock class, claim keys and lock tuples are normalized
to UTF-8 lexical ascending order before acquisition: event/idempotency claim, worker
`(siteId, workerId)`, shift `(siteId, startAt, shiftId)`, assignment `(shiftId, workerId)`,
plan `(planId)`, then swap `(swapId)`. A caller-provided reverse order is never honored,
so concurrent transactions use one order and the deadlock fixture can prove it. Unique/index
contracts include assignment `(siteId, shiftId, workerId)`, shift `(siteId, startAt, shiftId)`,
event inbox `(provider, eventId)`, idempotency `(method, route, demoScope, principal, key)`,
and outbox `(status, nextAttemptAt, id)`.

The PostgreSQL session sets `lock_timeout=2s` and `statement_timeout=5s`. A failed CAS,
lock timeout, or exhausted deadlock retry performs no business write; after rollback, a
separate short transaction records a bounded conflict audit. Approval and swap acceptance
use `SELECT ... FOR UPDATE` plus expected revision CAS and require `affectedRows == 1`.
An idempotency claim stores the canonical command fingerprint (source assignment,
target worker, plan revision, dataset, generationId, and body digest) with
`(method, route, demoScope, principal, key)`. A matching retry returns the stored response; a
different fingerprint returns `409 IDEMPOTENCY_KEY_REUSED` and performs no domain write.
The idempotency row stores `fingerprintSha256 CHAR(64)` with a lowercase-hex length/check
constraint; its canonical input is source assignment, target worker, plan revision, dataset,
generationId, and body digest. Schema and restart fixtures verify the unique namespace and
preserve replay/mismatch behavior after process restart.

Event inbox rows contain `(provider, eventId, digest, requestId, datasetId, generationId,
aggregateId, siteId, targetAssignmentId, aggregateRevision, status, attempt, nextAttemptAt)`.
`status` is one of `RECEIVED`, `APPLIED`, `STALE`, `REJECTED`, or
`RETRY_EXHAUSTED`. The unique provider/event key is
inserted as `RECEIVED` before domain locks. A same-digest replay returns `DUPLICATE` and
adds only a duplicate audit/read outcome without changing the terminal inbox/business
state; a different digest returns `EVENT_KEY_REUSED` without changing the row. After the
domain revision and provider/request/dataset/generation binding pass, the handler
atomically appends the audit/outbox effects and moves the inbox to `APPLIED` or `STALE`.
A transient database/provider failure leaves `RECEIVED` with at most five attempts and
`2/4/8/16/30` second backoff; an invalid binding moves it to `REJECTED`. Availability, sick-call,
demand, swap, and shift-start events increment the affected aggregate revision and
enqueue a unique `(datasetId, generationId)` replan in the same transaction.

Callback revision comparison is monotonic: `providerRevision > storedRevision` may apply
only when the aggregate revision and signed target binding still match, `==` is a duplicate
only when the digest matches, and `<` is `STALE` audit-only. Aggregate/source revision
updates use `affectedRows == 1`; a failed comparison never changes accepted state. After
five retryable inbox failures the row becomes terminal `RETRY_EXHAUSTED` and an operator
must explicitly requeue the same digest with a new request ID; automatic replay is not
allowed. The loopback demo exposes `POST /api/shift-coverage/outbox/{effectKey}/redrive`
for the same recovery boundary. It accepts only `X-Demo-Role=manager`, a bounded printable
reason, and a new `X-Request-Id`; it writes an operator audit before redriving only a
`RETRYABLE` row produced by definitive `NOT_FOUND` reconciliation. `DELIVERY_UNKNOWN`,
`APPLIED|COMPLETED`, `DEAD_LETTER`,
unresolved provider lookup, foreign effect key, and worker callers are rejected with no write.
For inbox failures, `POST /api/shift-coverage/inbox/{provider}/{eventId}/requeue` is the
manager-only operator command. It requires the stored digest, a bounded reason, and a new
`X-Request-Id`; it records an operator audit and returns the row to `RECEIVED` only from
`RETRY_EXHAUSTED`. A digest mismatch, non-terminal row, stale provider binding, or worker
caller returns no-write.

Replan writes a durable generation row before planning. Duplicate generation requests
return the existing terminal state. A generation is `REQUESTED`, `RUNNING`, `SUCCEEDED`,
`STALE`, `CANCELLED`, or `FAILED`; materialization is allowed only when the current
aggregate revision and canonical digest still match. A stale generation records an audit
and queues the next unique generation instead of overwriting a newer plan.

Outbox delivery uses the following state machine:

| Message/effect state | Allowed transition | Recovery rule |
|---|---|---|
| `PENDING` + no effect | `CLAIMED` with owner/token/lease | claim uses DB clock and fenced token |
| `CLAIMED` + `NOT_STARTED` | fenced `STARTED` (`affectedRows == 1`) | no external send occurs before this transition commits |
| `STARTED` + provider ACK | `APPLIED` + `COMPLETED` (`affectedRows == 1` for both) | ACK must contain the same effect key and request ID |
| `STARTED` + exception before send | `RETRYABLE` or `DEAD_LETTER` | only the fenced owner may classify a pre-send failure |
| `STARTED` + timeout/uncertain send | `DELIVERY_UNKNOWN` | never silently duplicates; reconcile by the same effect key |
| `DELIVERY_UNKNOWN` | `APPLIED`/`COMPLETED` or `RETRYABLE` | definitive provider lookup; operator redrive is allowed only after `NOT_FOUND` |
| `RETRYABLE` | `CLAIMED` or `DEAD_LETTER` | `2/4/8/16/30` second backoff, max five attempts |

The message and paired effect transition are atomic in PostgreSQL. Every effect references
one message, every terminal message has one terminal effect, and an initial `PENDING`
message is the only allowed effect-less state. Before the external send, the owner
revalidates `(id, owner, token, leaseExpiresAt > dbNow)` and commits `STARTED`; a stale
owner cannot send or complete. Completion requires provider ACK evidence with the same
effect key, request ID, and `affectedRows == 1` for both effect and message updates. A
timeout or crash after `STARTED` enters `DELIVERY_UNKNOWN`; reconciliation makes a
state-changing decision only when provider lookup returns `APPLIED` or `NOT_FOUND`
(`NOT_FOUND` returns to `RETRYABLE`). An unresolved result remains operator-visible but
cannot be redriven until a definitive lookup; after `NOT_FOUND`, manager-only redrive uses
the same key and a new request ID.

Startup sweeps run before polling and every 10 seconds thereafter, in ascending message
ID order, with a two-second DB statement deadline. They reclaim only expired
`NOT_STARTED` claims; `STARTED` claims enter `DELIVERY_UNKNOWN` and are never silently
replayed. The database clock and owner token fence every transition. Outbox delivery has
at most four in-flight handlers, claims at most ten rows per batch, uses a 30-second lease
and a 5-second I/O deadline. The persisted row remains `PENDING` or `RETRYABLE` when the
bounded delivery queue is full.

Blocking JDBC/HTTP work is admitted through Bluetape Java 25 virtual threads with the
same four-slot semaphore and an `ArrayBlockingQueue(8)`. CPU planning remains on the fixed executor
above so virtual threads do not create an unbounded CPU queue. On shutdown, admission is
closed, new HTTP mutations are rejected, pending replans are cancelled, and every
permit/lease is released in `finally` before the executor drains for 30 seconds. A
timeout calls `shutdownNow()` while preserving the interrupt flag. Readiness becomes
false during shutdown while liveness remains available. A cancelled or failed plan does
not materialize assignments; its durable generation records `CANCELLED` or `FAILED` with
a redacted error code. Tests assert no permit, claimed lease, or orphan effect remains
after cancellation, timeout, restart, or forced shutdown.

### 5.4 HTTP and callback

- Query routes are available on loopback with opaque cursor pagination and redacted DTOs.
- Mutation routes are enabled only on the `demo` profile and require bounded
  `X-Demo-Operator`/`X-Demo-Role`/`X-Request-Id` headers. The fixed subjects are
  `manager-demo→manager/site-demo` and `worker-a-demo|worker-b-demo→worker/site-demo`;
  principal/role/worker/site scope is part of idempotency binding. These headers are workshop guards, not
  production authentication or authorization. The demo server binds to `127.0.0.1`;
  non-loopback mutation requests and every mutation outside `demo` fail closed.
- Callback parsing rejects duplicate/unknown keys, oversized bodies, invalid canonical
  JSON, wrong provider/request/dataset binding, stale generation, and invalid signature
  before inserting an inbox row or changing any business table.
- Provider callback signatures use HMAC-SHA-256 over canonical UTF-8 bytes plus the
  signed context `(v1, method, path, schemaVersion, provider, requestId, datasetId,
  generationId, aggregateId, siteId, eventId, issuedAt)` encoded as length-prefixed UTF-8
  fields with a constant-time comparison. The exact signature headers are
  `X-Shift-Coverage-Signature` and `X-Shift-Coverage-Key-Version`.
  `issuedAt` must be within five minutes of the database clock; outside-window or reused
  events return `CALLBACK_REPLAY` with no write. Secrets are read only from
  environment/configuration, excluded from logs and DTOs, and a provider profile fails
  closed when the secret is absent. The default fake profile uses a recorded signature
  fixture and never makes a network call.
- Callback input is bounded to `256 KiB`, provider response to `64 KiB`, query pages to
  `100`, and mutation admission to eight in-flight requests. Oversized input returns
  `413 RESPONSE_TOO_LARGE` without an inbox, plan, or assignment write; a saturated
  mutation/replan gate returns `429 REPLAN_REJECTED`.
- DTO limits are centralized: printable IDs/headers are at most `200` UTF-8 bytes,
  display/reason strings at most `240` Unicode code points, skills and availability
  arrays at most `20` per worker, reason arrays at most `20`, JSON depth is at most `12`,
  and all numbers must be finite. Jackson rejects unknown fields, duplicate keys,
  trailing tokens, non-finite numbers, and open enum values. Opaque cursors are base64url
  values of at most `256` characters and `X-Request-Id` follows the same printable
  grammar.
- `X-Request-Id` is a bounded printable identifier, is echoed in every response, and is
  stored in the audit row. Error responses expose stable code, request ID, retryability,
  `Retry-After` when applicable, and a bounded `nextAction` only:

  | HTTP | code | retryable | nextAction |
  |---:|---|---|---|
  | `400` | `REQUEST_INVALID` | no | `FIX_REQUEST` |
  | `401` | `CALLBACK_SIGNATURE_INVALID` | no | `FIX_SIGNATURE` |
  | `403` | `DEMO_ROLE_FORBIDDEN` | no | `USE_ALLOWED_ROLE` |
  | `403` | `LOOPBACK_REQUIRED` | no | `USE_LOOPBACK` |
  | `403` | `ORIGIN_FORBIDDEN` | no | `USE_SAME_ORIGIN` |
  | `409` | `REVISION_CONFLICT` | no | `REFRESH_PLAN` |
  | `409` | `IDEMPOTENCY_KEY_REUSED` | no | `USE_NEW_KEY` |
  | `409` | `CALLBACK_REPLAY` | no | `DROP_EVENT` |
  | `409` | `EVENT_KEY_REUSED` | no | `DROP_EVENT` |
  | `409` | `STALE` | no | `REFRESH_PLAN` |
  | `413` | `RESPONSE_TOO_LARGE` | no | `SHRINK_INPUT` |
  | `422` | `RETRY_EXHAUSTED` | no | `OPERATOR_REQUEUE` |
  | `429` | `REPLAN_REJECTED` | yes | `RETRY_AFTER` |
  | `202` | `REPLAN_ACCEPTED` | yes | `POLL_OPERATION` |
  | `404` | `DEMO_PROFILE_REQUIRED` | no | `ENABLE_DEMO` |

  Raw callback body, credentials, tokens, PII, JDBC URL, and internal exception text are
  excluded. The golden error matrix fixes status, code, redaction, and retry headers.
Malformed/unknown DTO, invalid or missing signature, wrong target, forbidden role,
non-loopback/non-demo request, and foreign `Origin`/CORS are negative fixtures that assert
the mapped status/code/`nextAction` and no response, audit, inbox, plan, or assignment write.

Caller-facing routes are intentionally narrow: `GET /api/shift-coverage/plans` and
`GET /api/shift-coverage/swaps` are read-only cursor queries; `POST /api/shift-coverage/
replans`, `POST /api/shift-coverage/plans/{revision}/approve`,
`POST /api/shift-coverage/swaps`, and `POST /api/shift-coverage/swaps/{id}/accept` are
demo mutations; `POST /api/shift-coverage/callbacks/{provider}` is signature-protected;
`POST /api/shift-coverage/outbox/{effectKey}/redrive` and
`POST /api/shift-coverage/inbox/{provider}/{eventId}/requeue` are manager-only operator
recovery commands.
Approval and acceptance require the plan/assignment revision and idempotency key, so a
stale caller receives `409 REVISION_CONFLICT` instead of a partial write. The browser
console uses only these redacted routes and displays coverage gap, cost, fairness, reason
codes, plan revision, and change impact; it never displays provider raw payloads.

The demo command matrix permits a `worker` role to request a swap, while only the
`manager` role may approve a plan or accept a swap; replan requests use the manager
route. Outbox redrive and inbox requeue also require the manager role, stored digest, and a
new request ID/reason. These
role checks are deterministic caller-shape fixtures, not production authz.

The manager read model allowlist is `siteId`, `shiftId`, synthetic `workerId`, shift
interval, assignment state, plan revision, gap/cost/fairness, bounded reason codes, and
change impact. The worker read model allowlist is its synthetic `workerId`, assigned
shift ID/interval, site ID, assignment state, and swap status; it excludes other
workers' availability, preferences, credentials, and raw event data. `X-Demo-Role` is a
closed `manager|worker` fixture value used only to exercise this scope matrix; it is not
production authorization. A redaction canary test serializes both DTOs and asserts that
forbidden fields, callback payloads, and secret-like values never appear. Actuator and
console endpoints stay loopback-bound with same-origin CORS disabled.

### 5.5 Observability

The module exposes health and Prometheus-compatible metrics on the existing demo
actuator surface. Counters and timers cover `plan.duration`, `plan.candidate_evaluations`,
`replan.rejected`, `replan.timeout`, `approval.conflict`, `swap.conflict`,
`callback.duplicate`, `callback.stale`, `outbox.retry`, and `outbox.dead_letter`.
Labels are limited to bounded status/reason/provider values; worker, shift, tenant,
credential, callback body, and raw SQL values never become metric labels or trace fields.
The README and tests verify the redacted response, `/actuator/health`, and
`/actuator/prometheus` contracts.

The demo profile seeds only synthetic data and uses disposable `SchemaUtils` tables; it
does not run a production migration. Startup prints the active profile and fixture
dataset ID without secrets. Rollback removes the child module's registration, smoke
entry, and disposable schema together; shared catalogs and sibling data remain untouched.

## 6. Event and failure convergence

| Scenario | Required result |
|---|---|
| Same callback event and digest replay | no second state change; duplicate audit/read result |
| Same event ID with another digest | `EVENT_KEY_REUSED`; no business write |
| Event handling retry after rollback | inbox remains `RECEIVED`; one later attempt can apply it |
| Older callback revision | audit-only stale result; current state unchanged |
| Availability change or sick call during planning | current plan becomes stale; durable replan generation is queued |
| Sick call races with swap acceptance | fixed lock order and aggregate CAS produce one winner; loser has no partial assignment write |
| Concurrent swap acceptance | one expected revision wins; loser returns conflict and cannot partially change assignment |
| UTC midnight / site timezone boundary | interval normalization uses explicit `ZoneId`; no overlap or rest calculation crosses the wrong day |
| DST spring-forward / fall-back | local wall-clock input with zero valid offsets returns `TIMEZONE_GAP`; two valid offsets returns `TIMEZONE_AMBIGUOUS` unless the caller supplies an offset; only one valid offset is normalized to `Instant` |
| Started shift or pinned assignment in a new plan | assignment remains fixed; proposed change is reason-coded and not approvable |
| Worker/process restart after outbox lease | expired lease can be reclaimed; fenced owner cannot complete the message twice |
| Crash after external delivery before DB commit | effect is `DELIVERY_UNKNOWN`; reconciliation uses the same idempotency key before redrive |
| Replan retry after materialization | same generation returns the same plan/terminal state |

## 7. Compatibility and migration

The module is a new consumer example. It uses the root `bluetape4k-dependencies` BOM and
does not publish a library artifact or change shared catalog versions. `settings.gradle.kts`
auto-registers the directory. Existing modules and #530 remain unchanged. Registration
changes are limited to optimization README locales, Examples workflow, optimization smoke,
validation matrix, and a Korean lesson.

## 8. Acceptance criteria

1. Planner rejects overlap, unavailable time, missing skill, rest violation, started shift,
   and pin movement with stable reason codes.
2. `shift-coverage-canonical-v1` normalizes Unicode, UTC instants, decimals, maps, and
   arrays deterministically; golden fixtures prove byte-identical canonical bytes and
   SHA-256 digest for equivalent input orderings.
3. The documented envelope (100 workers, 500 shifts, 50,000 candidate evaluations,
   256 KiB input, 5-second planner deadline) is enforced with bounded no-write errors.
4. Approval rechecks current assignment/worker/shift revisions in one PostgreSQL
   transaction and never applies a stale recommendation.
5. Swap request and acceptance are idempotent; concurrent acceptance has one winner and
   no partial loser write.
6. Availability change, sick call, demand change, swap events, and shift-start event are
   duplicate-safe and out-of-order-safe.
7. UTC midnight plus DST spring-forward/fall-back, post-start replan, callback ordering,
   restart/retry, and outbox
   lease/effect fixtures converge to one terminal history.
8. The fixed lock order, `2s` lock timeout, `5s` statement/I/O timeout, virtual-thread
   shutdown, cancellation, and bounded replan/outbox admission are covered by
   contention and lifecycle tests.
9. Idempotency claims bind route/scope/key and canonical command fingerprint; mismatched
   reuse is `409` with no write. Callback HMAC context, five-minute replay window, target
   binding, and wrong-target fixtures are covered.
10. Query/command DTOs, nullability, error codes, redaction allowlists, manager/worker
   demo role matrix, metrics, actuator endpoints, and fixture ABI
   are covered by tests and documented in both README locales.
11. Module registration, workflow artifacts, smoke task, validation matrix, and stale-check
   surfaces pass read-back validation.
12. No production WMS/HRIS/payroll/Timefold credential or autonomous reassignment is
   introduced.

## 9. Verification and DoD

- RED/GREEN planner and domain tests use JUnit 5 and bluetape4k assertions.
- PostgreSQL Testcontainers tests run sequentially and cover CAS, event inbox, swap,
  replan, cancellation/started pin, and outbox fencing.
- Canonicalization golden, max-envelope complexity, planner timeout, lock contention,
  deadlock/lock-timeout, DST, virtual-thread shutdown, and bounded queue probes are
  required. The performance probe records candidate evaluations, rejection counts,
  lock-wait/dead-letter counts, and response bytes without using an external benchmark
  dependency. Failed container or lifecycle runs preserve redacted diagnostics under
  `build/reports/shift-coverage/`; a skipped database check is recorded as `PENDING`.
- Testcontainers use `PostgreSQLServer.Launcher.postgres`, the module
  `junit-platform.properties` (`parallel.enabled=false`, same-thread execution), and
  `--max-workers=1`. The resolved `bluetape4k-testcontainers:1.11.0` and
  `bluetape4k-junit5:1.11.0` artifacts expose no helper, but the workspace root
  `build.gradle.kts` registers the Gradle `test-mutex` BuildService and attaches it with
  `usesService(testMutex)` to every standard `test` task. No module-specific mutex is added;
  a future custom integration task must register the same shared service. Docker/Colima
  context is inspected before a retry, and no skipped container test is counted as a pass.
- Module test, optimization smoke, `./gradlew projects`, README language/parity checks,
  `actionlint`, `git diff --check`, and fixture/production JAR boundary are required.
- `detekt` is attempted with the exact module task name; if the task is not registered,
  the gap is recorded as `PENDING` rather than treated as success.
- PR creation, merge, release, external provider dispatch, and Epic closure are outside
  this implementation unit.

## 10. Open risks and rollback

- **External provider drift:** keep provider behavior behind the adapter port and recorded
  fixtures; rollback by removing only the adapter integration, not domain approval logic.
- **Revision race:** preserve the failing concurrent swap fixture and rerun repository
  tests after every CAS change.
- **Timezone arithmetic:** use `Instant` plus explicit `ZoneId`, retain boundary fixtures,
  and revert planner changes if the same 기준 데이터 묶음 digest changes unexpectedly.
- **Container instability:** inspect Colima/Docker and raw Testcontainers logs; do not turn
  a skipped database test into a pass. Preserve the worktree and report `PENDING`.
- **Registration drift:** if workflow or README checks fail, repair registration before
  changing production module behavior.

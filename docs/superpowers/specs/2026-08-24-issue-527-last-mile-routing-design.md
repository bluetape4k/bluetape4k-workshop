# Issue #527 Last-Mile Routing 설계

- 날짜: 2026-08-24
- 저장소: `bluetape4k/bluetape4k-workshop`
- 분기: `feat/issue-527-last-mile-routing`
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/527
- 상위 Epic: https://github.com/bluetape4k/bluetape4k-workshop/issues/523
- 선행 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/524
- 대상 모듈: `optimization/last-mile-routing`
- 상태: 설계 리뷰 PASS, 구현 승인 대기

## 문제와 목표

Issue #527은 픽업·배송 쌍을 가진 라스트마일 작업을 대상으로, 차량 용량·시간
창·기사/차량 skill·픽업 선행·시작된 정류장 고정을 함께 보여 주는 결정론적
참조 애플리케이션을 요구한다. 현재 단계의 목표는 실제 지도·교통·운송사
자격증명을 붙이는 것이 아니라, 고정 travel matrix와 PostgreSQL 권위 상태로
재현 가능한 계획 제안, 승인, callback, 재연결, 브라우저 투영을 검증하는 것이다.

Timefold 문서의 경계도 이 설계를 지지한다. 애플리케이션이 입력 데이터셋을
제출하고 결과 데이터셋을 비동기적으로 받되 계획 데이터는 애플리케이션
백엔드가 보유해야 한다. 결과는 webhook, SSE 또는 polling으로 받을 수 있으며,
추천 결과는 별도 계획 버전으로 검증한 뒤 반영한다.

- [Pick-up and Delivery integration](https://docs.timefold.ai/pickup-delivery-routing/latest/user-guide/integration)
- [Planning AI concepts](https://docs.timefold.ai/timefold-platform/latest/planning-ai-concepts)
- [Timefold API integration](https://docs.timefold.ai/timefold-platform/latest/api/api-integration)

## 현재 증거

| 증거 | 설계에 반영한 사실 |
|---|---|
| Live Issue #527 | 고정 matrix 우선, OSRM/Timefold Maps는 provider-neutral seam 뒤에 두며, production credential·GPS·geocoding·실제 운송사 계약은 비목표다. |
| Epic #523/GNO `bluetape4k-github` | 형제 child는 독립 모듈로 순차 진행하고 #527은 #524 planning adapter에만 의존한다. |
| GNO `bluetape4k-wiki`의 Timefold reference note | PostgreSQL이 source of truth이고 provider 결과는 versioned proposal이며, final command 전 재검증·started stop pin·redacted telemetry가 필요하다. |
| `optimization/field-service-dispatch` | synthetic fixture, deterministic planner, Exposed/PostgreSQL CAS, callback inbox/outbox, 안전한 browser projection 패턴을 재사용한다. |
| `optimization/planning-contracts` | 공개 안정 API가 아니라 `internal` 구현 중심이므로 모듈 간 구현 의존을 만들지 않는다. 필요한 lifecycle은 이 모듈의 정규화된 port로 정의한다. |

## 선택지와 결정

### 선택 A — 독립 모듈 + 정규화된 provider port + 고정 matrix (권장)

`optimization/last-mile-routing`이 domain, planner, persistence, HTTP, browser read
model을 소유한다. `RoutingProvider`는 `submit`, `poll`, `acceptCallback`의
정규화된 입력·출력만 노출하고, 기본 구현은 네트워크가 없는
`DeterministicRoutingProvider`다. 계획에는 `matrixRevision`, `providerRevision`,
`carrierVersion`을 함께 저장한다.

이 선택은 오프라인 재현성, provider 장애 fixture, PostgreSQL CAS, 향후 OSRM 또는
Timefold Maps 도입을 동시에 만족한다. provider raw JSON, token, 주소, 고객 정보는
domain·log·SSE·metric으로 통과시키지 않는다.

### 선택 B — #524 `planning-contracts` 구현 직접 의존

공통 inbox/outbox나 DTO를 바로 재사용할 수 있지만 현재 #524 구현은 안정된
외부 API가 아니고 `internal` 타입과 application 경계가 섞여 있다. child 간
변경 결합과 공개 API 오해가 생기므로 채택하지 않는다. 필요한 의미만 이 모듈의
작은 port와 envelope로 명시한다.

### 선택 C — 실제 Timefold/OSRM 연동부터 구현

실제 provider는 품질 비교에는 유용하지만 credential, 네트워크, provider
retention, 비결정적 교통 결과 때문에 workshop의 첫 검증 단위가 될 수 없다.
향후 provider adapter 검증 때 선택 A의 port 뒤에 추가하며 이번 구현에서는
네트워크 fallback도 만들지 않는다.

## 범위와 비목표

### 포함 범위

- `optimization/last-mile-routing` Spring MVC 참조 애플리케이션
- synthetic pickup/delivery job, vehicle, driver, capacity, time window, skill
- 픽업 선행, capacity, time window, skill, started-stop pin hard constraint
- 유한하고 음수가 아닌 고정 travel matrix와 revision
- deterministic route proposal, unassigned reason, score, revision diff
- PostgreSQL 권위 상태, 계획 승인 CAS, carrier version 재검증
- request/response와 callback inbox/outbox, duplicate callback idempotency
- return request, pickup-window change, driver check-in, vehicle breakdown,
  carrier cancellation, no-show, traffic-duration update event
- matrix miss/provider outage, stale approval, driver reconnect, burst coalescing
  fixture
- 외부 지도 타일 없이 polyline/depot/stop marker/ETA/capacity/time window/skill/
  unassigned/score/revision diff/started pin을 보여 주는 안전한 browser projection
- health/readiness, bounded Micrometer metrics, Java 25 virtual-thread lifecycle

### 비목표

- 실제 Timefold Platform tenant/API key, OSRM/Map provider credential 또는 외부 호출
- live GPS, geocoding, traffic vendor, 실제 carrier contract, inventory transfer authority
- 생산 인증/CSRF, multi-tenant API, production migration, 외부 지도 tile
- provider 점수 문자열이나 raw 주소/고객 데이터를 로그·metric·SSE에 노출

## 도메인과 불변식

### 식별자와 버전

다음 값 객체는 `String`/`Long`을 직접 섞지 않고 생성 시 길이·문자 집합을
검증한다. `JobId`, `PickupStopId`, `DeliveryStopId`, `VehicleId`, `DriverId`,
`RouteId`, `PlanId`, `PlanRevision`, `MatrixRevision`, `ProviderRevision`,
`CarrierVersion`, `EventId`, `RequestGeneration`을 사용한다. 정렬과 digest는
canonical 문자열을 기준으로 하며 외부 provider id는 별도 redacted reference로
보관한다.

`DeliveryJob`은 pickup과 delivery 좌표, demand, required skill, pickup/delivery
time window, status, carrier version을 갖는다. `RouteStop`은 `PICKUP` 또는
`DELIVERY` phase, sequence, ETA, load, started/pinned 상태를 갖는다.

### Hard constraint

1. 같은 job의 pickup sequence는 delivery보다 작아야 한다.
2. 누적 load는 vehicle capacity를 넘지 않아야 하고 delivery 뒤에는 demand를 반납한다.
3. 도착·서비스 시간이 time window 안이어야 한다.
4. vehicle이 모든 required skill을 가져야 한다.
5. `started` 또는 수동 pin 정류장은 기존 sequence와 위치를 자동 변경하지 않는다.
6. matrix edge가 없거나 revision이 다르면 해당 job을 `MATRIX_MISS`로 명시적
   unassigned 처리한다. 다른 provider로 몰래 전환하지 않는다.

동점은 `priority`, window 시작, job id, stop phase 순으로 정렬한다. score는
finite numeric 값만 허용하며 provider가 보낸 설명 문자열은 계획 권위 모델이
아니다. 모든 planner 입력은 상한을 가지며 초과 요청은 `400`으로 거절한다.

## Matrix와 provider 경계

`TravelTimeMatrix`는 `MatrixRevision`과 유한한 non-negative seconds의 sparse
edge를 불변 값으로 보유한다. 좌표 id, depot, pickup, delivery의 정규화된 순서와
revision이 계획에 해시로 남는다. 누락 edge는 `MATRIX_MISS`이고, 음수·NaN·무한대·
중복 edge·알 수 없는 좌표는 fixture와 validation에서 거절한다.

```kotlin
interface RoutingProvider {
    fun submit(request: RoutingRequest): RoutingSubmission
    fun poll(submission: RoutingSubmission): RoutingResult?
    fun acceptCallback(callback: RoutingCallback): CallbackDecision
}
```

port는 domain aggregate나 Exposed row, HTTP request, credential을 받지 않는다.
`RoutingRequest`는 canonical job/vehicle/matrix revision만 포함하고,
`RoutingResult`는 정규화된 route stop, unassigned reason, numeric score,
provider revision만 반환한다. 기본 provider는 같은 입력 digest에 같은 결과와
revision을 반환한다. provider outage는 `PROVIDER_UNAVAILABLE`로 저장하고
재시도 상한·dead-letter·운영자 확인 없이 승격하지 않는다.

## 상태 흐름과 동시성

```text
command/event
  -> PostgreSQL aggregate + event inbox (digest idempotency)
  -> replan request (requestGeneration, carrierVersion, matrixRevision)
  -> deterministic/provider port submit
  -> callback/poll result -> proposal history
  -> approve(expected plan revision + carrier version)
  -> transaction revalidation -> committed route + outbox
  -> redacted query/browser projection
```

승인은 `planRevision`, `jobVersion`, `carrierVersion`, `matrixRevision`을 한
트랜잭션에서 재확인한다. 하나라도 달라졌거나 job이 취소·no-show가 되었거나
started pin과 충돌하면 변경 없이 `STALE_ROUTE_APPROVAL`을 반환한다. 여러 차량의
commit은 partial success를 허용하지 않고 lock 순서와 CAS 실패 시 전체 rollback을
보장한다. callback은 `(provider, eventId)` unique key와 payload digest로 중복·
충돌을 구분하고, 오래된 provider revision은 audit만 남긴다.

이벤트는 aggregate와 event key를 canonicalize한다. traffic-duration update와
pickup-window change는 동일 request generation 안에서 coalesce하되 최신
canonical payload digest를 보존한다. driver check-in/reconnect는 현재 route와
started pin을 읽고 재계산하며 이미 시작된 정류장을 이동시키지 않는다.

## PostgreSQL 권위 모델

Exposed table은 다음 책임을 분리한다.

- `last_mile_drivers`, `last_mile_vehicles`, `last_mile_jobs`
- `last_mile_matrix_edges`와 `last_mile_matrix_revisions`
- `last_mile_plan_proposals`, `last_mile_plan_stops`, `last_mile_unassigned`
- `last_mile_committed_stops` — `(job_id)` unique, carrier version 포함
- `last_mile_events`, `last_mile_callback_inbox`, `last_mile_outbox`, `last_mile_audits`

예제 DB는 `SchemaUtils.create`와 Testcontainers PostgreSQL을 사용한다. 운영
migration은 추가하지 않는다. 조회에는 안정적인 id/revision keyset과 bounded
page를 사용하고, update SQL에는 expected version 조건을 포함한다. raw callback
body·signature·provider secret column은 만들지 않는다.

## HTTP와 browser projection

loopback demo의 HTTP surface는 다음으로 제한한다.

- `GET /last-mile-routing/` — 정적 browser console
- `GET /api/last-mile-routing/plans/{planId}` — redacted read model
- `POST /api/last-mile-routing/replans` — idempotency key와 bounded input
- `POST /api/last-mile-routing/plans/{planId}/approve` — expected revision/version
- `POST /api/last-mile-routing/providers/{provider}/callbacks` — fixture callback
- `POST /api/last-mile-routing/events` — event canonicalization/coalescing
- `POST /api/last-mile-routing/drivers/{driverId}/reconnect` — reconnect projection

응답은 `ETag`/revision과 명시적인 `MATRIX_MISS`, `PROVIDER_UNAVAILABLE`,
`STALE_ROUTE_APPROVAL`, `DUPLICATE_CALLBACK`, `DIGEST_CONFLICT`를 사용한다.
browser는 정적 synthetic coordinate를 선분 polyline으로 투영하며 지도 tile,
주소, 고객명, token을 사용하지 않는다. JavaScript는 CSP nonce/allowlist와
`textContent`/DOM API만 사용하고 `innerHTML`, inline event handler, raw JSON
출력을 금지한다. console error 0, keyboard focus, redacted DOM, started pin
표시를 browser contract로 검증한다.

## 관측성·실행기·Bluetape 선택

| 요구 | 선택 | 적용 경계 |
|---|---|---|
| BOM/version | `platform(libs.bluetape4k.dependencies)` | 개별 Bluetape 버전 pin과 개별 BOM import 금지 |
| logging | `KLogging`/구조화 logger | job/driver/address/provider raw id를 로그에 넣지 않음 |
| ID/계약 | Bluetape id generator, Kotlin value/sealed types | 입력 검증·canonical digest·불변 모델 |
| DB | Bluetape Exposed repository/Table 패턴 | PostgreSQL CAS, bounded keyset, Testcontainers |
| concurrency | Bluetape virtual-thread runtime + bounded executor | Java 25 only, shutdown/fence/retry 명시 |
| tests | JUnit 5, Kluent/MockK, Testcontainers PostgreSQL, `TestMutexService` | deterministic fixture와 실제 SQL/CAS를 분리 검증 |
| observability | Micrometer/Actuator | route/job/provider 식별자는 metric label로 사용하지 않음 |

새 dependency는 추가하지 않는다. #525의 합성 fixture와 브라우저 안전성 패턴은
복사보다 의미를 유지하는 범위에서 재사용하며, #524 구현 모듈을 Gradle
`project(":optimization-planning-contracts")` 의존성으로 추가하지 않는다.

## 실패·보안·운영 계약

| 시나리오 | 권위 결과 | 검증 |
|---|---|---|
| matrix edge 누락 | 해당 job `MATRIX_MISS`, proposal은 승인 불가 | planner/provider fixture |
| provider outage | `PROVIDER_UNAVAILABLE`, bounded retry와 audit | callback/lifecycle test |
| duplicate callback | 동일 digest면 no-op, 다른 digest면 `DIGEST_CONFLICT` | inbox unique/digest test |
| stale approval | committed row·outbox 무변경, `STALE_ROUTE_APPROVAL` | PostgreSQL CAS test |
| carrier cancellation/no-show | job unassigned 또는 취소, started stop은 유지 | event/replan test |
| driver reconnect | 현재 carrier version과 started pin으로 read/replan | reconnect test |
| traffic/pickup burst | generation별 1회 replan, 최신 digest 보존 | coalescing test |
| browser injection | text node만 변경, CSP violation 없음 | browser contract/canary |

로그·metric·SSE에는 payload 원문, 주소, 고객, token, raw provider response를
남기지 않는다. readiness는 PostgreSQL과 lifecycle executor 상태를 반영하고,
bounded queue 포화와 callback 처리 실패를 health detail에 안전한 enum으로만
노출한다.

## 수용 기준과 rollback

1. 모듈이 `./gradlew projects`에 자동 등록되고 BOM만으로 build 된다.
2. planner가 다섯 hard constraint와 deterministic tie-break를 테스트로 증명한다.
3. matrix/provider failure, duplicate callback, stale approval, reconnect,
   burst coalescing을 모두 재현할 수 있다.
4. PostgreSQL transaction이 carrier/job/plan version을 재검증하고 stale 결과를
   쓰지 않는다.
5. HTTP/browser projection이 redaction, CSP, ETag, 오류 enum, started pin을 증명한다.
6. README, workflow group, smoke/stale validation, Korean parity를 갱신한다.
7. root `./gradlew detekt`를 repository gate로 실행하고 optimization 모듈의
   local detekt task 부재는 정책상 `N/A`로 기록한다. 모듈 detekt task를 새로
   추가하는 것은 별도 정책 변경이다.

rollback은 새 모듈, README, workflow/smoke/stale 등록, 테스트 자원을 함께
삭제하는 local revert로 충분하다. 기존 모듈 DB나 production migration은
변경하지 않으므로 데이터 rollback은 필요 없다. PR·push·merge·Epic #523 종료는
이 설계 승인에 포함되지 않는다.

## 설계 수용 추적성

| 기준 | 구현 계획 검증 |
|---|---|
| pickup-before-delivery, capacity, window, skill, started pin | planner unit/property tests, browser projection |
| fixed matrix revision과 explicit failure | matrix validation/provider fixture |
| PostgreSQL authority와 stale approval | repository CAS/integration tests |
| callback idempotency와 digest conflict | inbox test와 callback controller test |
| event burst/reconnect/driver lifecycle | event coalescer/lifecycle tests |
| redaction/CSP/ETag/health | MVC/browser/observability contract tests |
| module ecosystem 등록 | `projects`, README parity, workflow/smoke/stale checks |

## 다음 gate

설계 리뷰는 P0/P1=0으로 통과했으며, 이 문서는 구현 코드나 PR 승인을 의미하지
않는다. 다음은 이 설계를 기준으로 실행 계획과 계획 review를 작성하는 단계다.
계획 review에서 P0/P1=0과 각 Task의 RED/GREEN·검증 명령을 확인한 뒤에만 Task 1
구현을 시작한다.

## 문서 작성 게이트

| 항목 | 상태 | 증거 |
|---|---|---|
| SPW-01 독자·목적·출처·범위 | PASS | Issue/Epic/live repository/GNO/공식 Timefold 링크와 포함·비목표 범위 |
| SPW-02 선택지·경계·실패·수용 기준 | PASS | 선택 A/B/C, 불변식, 실패 표, 수용·rollback 절 |
| SPW-03 한국어 기술 문체·용어 | PASS | 한국어 독자 문체와 code/API/URL 토큰 보존, terminology audit 예정 |
| SPW-04 현재 소스·외부 계약 대조 | PASS | #524 internal 경계, #525 패턴, Timefold integration/API 문서 대조 |
| SPW-05 read-back·Markdown·공백 | PASS | 작성 후 전체 read-back, `git diff --check`, fence/placeholder scan 수행 |

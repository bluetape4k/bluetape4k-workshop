# Issue #538 Event-sourced Promotion and Voucher Campaign 설계

## 목표

정규화된 PostgreSQL 상태를 권위로 사용하는 Issue #534 예제와 동일한 외부 command,
HTTP/SSE, failure fixture를 유지하면서, append-only event stream을 유일한 도메인 권위로
사용하는 별도 reference application을 만든다.

이 예제는 다음 계약을 실행 가능한 코드와 운영 화면으로 증명한다.

- 같은 aggregate version에서 시작한 동시 command 중 하나만 event를 append한다.
- 같은 idempotency key의 retry는 하나의 terminal response를 replay하며 event를 중복
  append하지 않는다.
- campaign capacity, voucher lifecycle, review queue, audit timeline projection은 동일한
  event stream에서 언제든 결정적으로 rebuild할 수 있다.
- duplicate, delayed, interrupted projection delivery가 stable event identity와 checkpoint로
  진단되고 수렴한다.
- snapshot restore와 tail replay 결과가 full replay 결과와 같다.
- operator가 authoritative stream position, projection position, lag, rebuild generation,
  poison event를 서로 구분할 수 있다.
- event, snapshot, projection, log, metric, API가 raw voucher code, IP, device signal,
  user identifier를 노출하지 않는다.

## 승인된 결정

- 외부 HTTP/SSE command와 failure contract는 #534와 호환한다.
- event-sourcing 운영 API만 별도로 추가한다.
- PostgreSQL event stream이 유일한 도메인 권위다.
- command 성공은 projection 완료를 기다리지 않는다.
- projector는 비동기 checkpoint 방식으로 동작한다.
- generic event-store, coupon engine, 공용 production module은 만들지 않는다.
- Redis는 사용하지 않는다. PostgreSQL만 durable authority로 둔다.
- Visual Companion 없이 기존 텍스트 spec과 repository diagram 검증 흐름을 사용한다.
- Java 25를 사용한다. 저장소 root의 기본 Java 21보다 module-local Java 25 설정을 우선하며,
  전체 저장소가 Java 25로 전환되면 중복 override를 제거할 수 있다.

## 현재 근거

- 기준 module `commerce/promotion-voucher-campaign`은 #534의 normalized-state 구현,
  application-owned idempotency, WebTestClient/JDK connector, snapshot-first SSE, 공통 60초
  HTTP timeout, Awaitility 기반 convergence test를 제공한다.
- `commerce/pre-generated-voucher-pool`, `commerce/promotion-voucher-campaign`,
  `commerce/concert-ticket-flash-sale` 등은 module-local Java/Kotlin 25 toolchain override를
  이미 사용한다.
- dependency authority는 `bluetape4k-dependencies:1.3.1` 단일 BOM이며 catalog의 Exposed는
  1.3.0, Spring Boot는 4.1.0이다.
- #538 시작 기준에서 #534 module test는 `BUILD SUCCESSFUL`이다.
- Issue #538은 Java 25, Spring Boot, virtual threads, Exposed/PostgreSQL, live HTTP/SSE,
  deterministic replay와 운영 복구를 요구한다.

## 범위와 비범위

### 포함

- campaign 생성, policy 변경, 활성화, 일시정지, 재개, 종료
- eligibility review, allocation, redemption, release, expiry, revocation
- campaign과 voucher별 versioned event stream
- expected-version optimistic concurrency와 atomic multi-stream append
- application-owned HTTP idempotency acquire, replay, conflict, owner validation, finalize
- campaign capacity, voucher lifecycle, review queue, audit timeline projection
- stable event identity, global position, projector checkpoint, processed-event deduplication
- snapshot 생성, restore, tail replay, full replay 동등성 검증
- 별도 projection generation을 사용하는 online rebuild와 atomic activation
- poison event 기록, degraded health, retry와 upcaster 기반 복구
- projection lag, rebuild, reconciliation을 보여 주는 operator UI
- #534 호환 REST/SSE와 동일 black-box command/failure fixture
- redacted logging, low-cardinality metrics, readiness/health

### 제외

- #534를 event sourcing으로 전환하거나 그 module에 speculative placeholder를 남기는 작업
- #537 pre-generated voucher pool 변경
- generic event-store 또는 다른 module이 의존하는 event-sourcing library
- Kafka, Redis Streams, 외부 broker, CDC 또는 distributed transaction
- cross-service exactly-once delivery 주장
- event payload의 사후 수정, 무감사 skip, projection을 권위 상태로 승격하는 동작
- production-grade IAM, fraud provider, secret manager, public deployment
- campaign capacity sharding이나 multi-region active-active event store

## Module과 platform

- directory: `commerce/event-sourced-promotion-voucher-campaign`
- Gradle project: `:commerce-event-sourced-promotion-voucher-campaign`
- package: `io.bluetape4k.workshop.commerce.voucher.eventsourced`
- Java toolchain: 25
- Kotlin JVM target: 25
- Spring Boot MVC/Tomcat, Java virtual threads
- PostgreSQL, HikariCP, Exposed JDBC
- Jackson 3 JSON, Micrometer/Actuator, JUnit 5, WebTestClient
- `bluetape4k-dependencies` BOM만 사용하며 개별 Bluetape BOM이나 명시 module version은
  추가하지 않는다.

우선 재사용하는 capability는 `bluetape4k-exposed-jdbc`,
`bluetape4k-exposed-jdbc-tests`, `bluetape4k-exposed-spring-boot-jdbc`,
`bluetape4k-testcontainers`의 `PostgreSQLServer`, `bluetape4k-virtualthread-api`,
`bluetape4k-virtualthread-jdk25`, `bluetape4k-jackson3`, `bluetape4k-logging`,
`bluetape4k-micrometer`, `bluetape4k-junit5`, `bluetape4k-assertions`다.

`bluetape4k-exposed-spring-modulith`는 평가하지만 채택하지 않는다. Spring Modulith publication
log는 transaction 이후 event publication과 replay에 적합하지만, #538이 요구하는 aggregate
stream version, event rehydration, snapshot, projection generation/checkpoint의 권위 저장소는
아니다. #534의 publication/replay 증거와 transaction wiring은 참고하되 application 전용
event store와 projector를 직접 구현한다. 구현 plan에는 각 후보 capability의
adopt/borrow/reject 근거를 정리한 `Ecosystem capability selection` 표를 포함한다.

## 접근법 비교

### 채택: append와 비동기 projection 분리

command transaction은 event append와 idempotency terminal response만 확정한다. query와
operator view는 별도 projector가 만든 projection을 사용한다. eventual consistency,
projection lag, 중단, duplicate, poison event, rebuild를 실제로 보여 줄 수 있어 #538의
학습 목표에 가장 잘 맞는다.

### 기각: append와 projection을 같은 transaction에서 갱신

조회 일관성과 구현은 단순하지만 projection 장애와 복구 경계를 숨긴다. projection lag와
rebuild 운영을 설명할 수 없으므로 기각한다.

### 기각: #534 normalized state와 event stream dual-write

기존 repository 재사용은 쉽지만 두 authority가 생긴다. 불일치 복구가 예제의 중심이 되고
event stream source-of-truth 계약을 위반하므로 기각한다.

## 아키텍처 경계

| 경계 | 책임 | 의존성 |
|---|---|---|
| `domain` | aggregate state, command decision, event reducer, invariants | Kotlin/JDK만 의존한다. |
| `command` | idempotency, stream load, decision, append, terminal response | domain과 transaction/event-store port에 의존한다. |
| `eventstore` | stream head, append-only event, snapshot, global position | Exposed/PostgreSQL에 의존한다. |
| `projection` | event dispatch, deduplication, checkpoint, read model, rebuild | event-store reader와 projection repository에 의존한다. |
| `query` | projection 조회와 lag metadata | projection repository에만 의존한다. |
| `web` | #534 호환 REST/SSE, operator endpoint, browser UI | command/query port에 의존한다. |
| `operations` | health, metric, rebuild/retry orchestration | event-store와 projection 운영 port에 의존한다. |

domain은 Spring, Exposed, JSON tree, transaction object를 받지 않는다. repository는 호출자가
connection을 전달하는 함수 집합으로 만들지 않고 기존 `ExposedJdbcRepository`와 module의
transaction runner 패턴을 따른다. transaction boundary는 application service가 소유한다.

## Aggregate와 event model

### Campaign stream

campaign stream은 policy와 capacity의 consistency boundary다. 대표 event는 다음과 같다.

- `CampaignCreated`
- `CampaignPolicyUpdated`
- `CampaignActivated`, `CampaignPaused`, `CampaignResumed`, `CampaignEnded`
- `VoucherCapacityReserved`
- `VoucherCapacityReleased`

capacity event는 `voucherId`, policy version, bounded reason을 포함하지만 user identity나 raw
voucher code를 포함하지 않는다. `REDEEMED` voucher는 capacity를 계속 소비하며 release,
expiry, unused revoke만 capacity를 반환한다.

### Voucher stream

voucher stream은 한 voucher lifecycle과 review 상태의 consistency boundary다.

- `VoucherEligibilityRecorded`
- `VoucherReviewRequested`, `VoucherReviewApproved`, `VoucherReviewRejected`
- `VoucherAllocated`, `VoucherRedeemed`
- `VoucherReleased`, `VoucherExpired`, `VoucherRevoked`

allocation이나 capacity 반환 command는 campaign과 voucher stream을 하나의 PostgreSQL
transaction에서 append한다. event store는 stream key를 정렬해 head를 lock하고 각 stream의
expected version을 검증한 뒤 모두 append하거나 모두 rollback한다. 단일 voucher 상태만
바꾸는 command는 voucher stream만 append한다.

### Event envelope

event envelope는 다음 metadata를 가진다.

- UUID v7 `eventId`
- commit-safe contiguous PostgreSQL `globalPosition`
- tenant, aggregate type, aggregate id, stream version
- bounded `eventType`, integer `schemaVersion`
- occurred-at, correlation digest, causation event id
- redacted JSON payload와 canonical envelope checksum

event type 이름을 Kotlin class name에 직접 결합하지 않는다. 역직렬화는 bounded registry와
versioned upcaster를 사용한다. unknown type/schema는 poison event로 처리한다. JSON payload는
64 KiB, nesting depth 16, string scalar 8 KiB를 hard cap으로 두며 snapshot은 1 MiB를 넘지
못한다. upcaster는 pure/deterministic 함수이고 한 event에 최대 4단계만 적용한다.
`(eventType, fromVersion, toVersion)`별 immutable golden fixture와 반복 결정성 test를
요구하며 기존 upcaster 의미 변경은 별도 migration review 없이는 허용하지 않는다.

`globalPosition`은 PostgreSQL sequence/identity로 발급하지 않는다. `es_event_log_head`의
단일 fence row를 transaction이 lock한 뒤 현재 head 다음의 연속 position range를 예약하고,
event insert와 head 갱신을 같은 transaction에서 commit한다. lock은 commit까지 유지되므로
나중 position이 먼저 보이는 commit-order inversion이 없다. rollback은 head 갱신과 event를
함께 되돌려 영구 gap을 만들지 않는다. 이 전역 fence는 의도적인 단순화이며 아래 성능
예산과 workload에서 병목을 측정한다.

## Persistence model

- `es_event_log_head`: commit-safe contiguous global position append fence
- `es_stream_heads`: stream key, current version, updated-at
- `es_events`: immutable envelope와 payload, unique stream version, unique event id,
  global position
- `es_snapshots`: aggregate key, stream version, schema version, state payload, checksum
- `es_http_idempotency`: scoped key digest, fingerprint, owner/deadline, terminal response descriptor
- `es_projection_generations`: projection name, generation, state, target/current position
- `es_projection_checkpoints`: projection generation별 last applied global position
- `es_projection_processed_events`: projection generation과 event id deduplication
- `es_projection_failures`: failed position, bounded reason, attempt, retry state
- generation-keyed campaign, voucher, review, audit projection tables

event payload, snapshot, idempotency response에는 plaintext voucher code를 저장하지 않는다.
allocation command response에 필요한 code는 #534와 같은 versioned key/domain과 allocation
identity로 결정적으로 재구성하고 verifier와 constant-time 비교한다. terminal response
descriptor에는 allocation identity와 generation/verification key version을 저장한다. replay에
필요한 key version이 retired 또는 unavailable이면 기존
`IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE`/503으로 fail closed한다.

application DB role은 `es_events`에 `SELECT`, `INSERT`만 가진다. `UPDATE`, `DELETE`,
`TRUNCATE` 권한을 제거하고 DB-side mutation-denying trigger로 우회 변경을 거부한다.
canonical envelope checksum은 accidental corruption을 탐지하고 replay, snapshot 생성,
backup/restore 검증에서 다시 계산한다. DB administrator가 malicious하게 payload와 schema를
함께 바꾸는 위협은 이 workshop의 trust boundary 밖이며, tamper-proof ledger나 외부 서명은
범위 밖임을 문서에 명시한다.

주요 index는 다음과 같다.

- events: unique `(aggregate_type, aggregate_id, stream_version)`, unique `event_id`,
  unique `global_position`
- stream tail: `(tenant_id, aggregate_type, aggregate_id, stream_version)`
- latest snapshot: `(tenant_id, aggregate_type, aggregate_id, stream_version desc)`
- projector scan: `(global_position)` covering event identity/type/version
- processed dedup: unique `(projection_name, generation, event_id)`
- retry failure: partial `(projection_name, generation, next_attempt_at, global_position)` for
  retryable rows
- projection read models: tenant/campaign/voucher별 public lookup과 active-generation prefix

대표 seed는 100 tenants, 1,000 campaigns, 10,000 streams, 100,000 events와 projection
100,000 rows를 가진다. warm-up 뒤 stream tail, global scan, latest snapshot, dedup, retry
scan을 `EXPLAIN (ANALYZE, BUFFERS)`로 확인한다. 각 핵심 lookup/scan은 의도한
`Index Scan`/`Index Only Scan`을 사용하고 unexpected `Seq Scan`이 없어야 하며, query당
shared read buffers 512 이하와 execution time 100 ms 이하를 dedicated PostgreSQL profile의
회귀 기준으로 둔다. CI correctness gate는 plan shape와 index 사용을 검증하고 환경 민감한
buffer/time ceiling은 dedicated performance profile에서 판정한다.

## Event-store port 계약

- `load(streamKey, afterVersion)`은 active transaction 밖에서도 immutable envelope page를
  반환하되 hard cap을 적용한다.
- `appendAll(expectedAppends)`은 application-owned active transaction 안에서만 호출한다.
- 각 `expectedAppend`는 stream key, expected version, non-empty event list를 가진다.
- 신규 stream head 생성과 기존 head lock을 atomic하게 수행하고 stream key 정렬 순서를
  강제한다.
- 결과는 `Appended(streamVersions, firstPosition, lastPosition)` 또는
  `VersionConflict(streamKey, expected, actual)`로 표현한다.
- repository가 connection/transaction을 매 함수 인자로 요구하지 않는다. Spring/Exposed
  transaction context와 module transaction runner를 사용한다.

## Command transaction 흐름

1. HTTP adapter가 tenant, principal digest, operation, resource id, idempotency key digest,
   request fingerprint를 만든다.
2. 완료된 동일 key/fingerprint는 저장된 terminal response descriptor를 replay한다.
3. 다른 fingerprint는 `IDEMPOTENCY_FINGERPRINT_CONFLICT`로 거부한다.
4. 새 owner는 snapshot과 이후 event tail로 aggregate를 복원한다.
5. domain decision이 0개 이상의 event와 terminal response descriptor를 만든다.
6. 하나의 foreground transaction에서 owner-token digest, lease/deadline, fingerprint를
   재검증하고 필요한 stream head를 정렬 lock하며 expected version을 확인한다.
7. event-log append fence를 lock해 commit-safe position range를 발급하고 모든 event를 연속
   stream version/global position으로 append한 뒤 stream head와 log head를 갱신한다.
8. 같은 transaction에서 idempotency terminal response를 finalize한다.
9. commit 뒤 authoritative stream position을 포함한 response를 반환한다.

같은 expected version의 경쟁자는 `CONCURRENT_MODIFICATION`을 받는다. 의미가 바뀔 수 있는
command를 application이 자동 재실행하지 않는다. command response는 projection에서 만들지
않고 append된 event를 reducer에 적용한 결과로 만든다.

idempotency unique scope는
`(tenantId, principalDigest, operation, resourceId, keyDigest)`로 고정한다. principal이 없는
operator/system command는 별도의 bounded system principal digest를 사용한다. acquire row는
raw owner token이 아니라 owner-token digest, lease/deadline, request fingerprint를 저장하고
append와 terminal descriptor finalize를 같은 transaction에서 수행한다.

외부 request의 expected revision이 현재 projection/aggregate와 이미 다르면 기존
`STALE_REVISION`/412를 반환한다. 검증 뒤 commit 직전에 다른 writer가 앞선 경우에만
`CONCURRENT_MODIFICATION`/409를 반환한다. unknown event/schema/upcaster failure 때문에
authoritative aggregate를 rehydrate할 수 없으면 append를 금지하고
`AGGREGATE_REHYDRATION_UNAVAILABLE`/503과 affected-aggregate degraded health를 노출한다.

## Projection과 query 흐름

projector는 global position 순서로 bounded batch를 읽는다. projection row 변경,
processed-event identity 기록, checkpoint 이동은 하나의 background transaction이다.
production reader는 global position 순서를 유지한다. duplicate와 delayed delivery 계약은
같은 projection handler 앞에 deterministic fault-injection dispatcher를 두어 재현하며,
handler 자체가 delivery 순서나 단일 전달을 신뢰하지 않도록 검증한다.

- duplicate event는 processed identity로 no-op 처리한다.
- aggregate version gap은 적용하지 않고 retryable delayed state로 기록한다.
- process 중단은 row 변경과 checkpoint를 함께 rollback한다.
- query는 `projectionPosition`, `streamPosition`, `lagEvents`, `lastProjectedAt`,
  `caughtUp`, generation을 반환한다. `lagEvents`는
  `streamPosition - projectionPosition`인 아직 적용되지 않은 event 수이며 시간 지연과 섞지
  않는다.
- optional bounded wait가 timeout되면 최신인 척하지 않고 `PROJECTION_PENDING`을 반환한다.
- SSE는 현재 projection snapshot을 먼저 보내고 projection/stream position이 포함된 change와
  lag/rebuild 상태를 전송한다.

기존 #534 response body와 ETag 의미는 바꾸지 않는다. additive header
`X-Stream-Position`, `X-Projection-Position`, `X-Projection-Lag`만 추가한다. command의
`X-Stream-Position`은 위 append fence가 commit한 last position이다. query caller는 선택적
`X-Min-Stream-Position` header로 최대 5초 bounded wait를 요청한다. 그 안에 projection이
도달하지 못하면 HTTP 202, stable code `PROJECTION_PENDING`, `Retry-After: 1`, 현재 두
position과 lag가 포함된 error body를 받는다. header가 없으면 현재 projection을 즉시 읽는다.

browser/caller는 GET의 202를 일반 2xx domain snapshot과 별도로 처리한다. 마지막으로 확인된
projection을 계속 표시하면서 `command committed, view catching up` 상태, `lagEvents`를
`12 events behind` 같은 text, `lastProjectedAt`, caught-up 여부로 보여 준다. 같은
`X-Min-Stream-Position`과 server의 `Retry-After`로 GET만 최대 5회 재시도하며 command를 다시
제출하지 않는다. bounded retry가 끝나면 stale 표시와 명시적 수동 refresh를 제공한다.
pending 완료, timeout, manual refresh는 #534 browser helper의 generic 2xx 처리보다 먼저 분기한다.

## Snapshot

snapshot은 재생 최적화이며 authority가 아니다. aggregate event 수가 설정된 threshold를
넘으면 committed stream version의 state snapshot을 만든다. restore 시 checksum과 schema를
검증하고 snapshot version 이후 event를 항상 replay한다. snapshot이 없거나 손상되면 full
replay로 fallback하고 운영 metric과 redacted log를 남긴다.

full replay와 snapshot+tail 결과는 canonical state digest로 비교한다. snapshot 생성 실패는
command 성공을 rollback하지 않는다.

snapshot 기본 threshold는 250 events다. foreground rehydration은 snapshot 이후 최대
10,000 events 또는 2초 CPU/wall budget을 hard cap으로 두고 position 기반 200-event page로
읽는다. valid snapshot 없이 cap을 넘으면 `AGGREGATE_REPLAY_LIMIT_EXCEEDED`/503으로 fail
closed하고 repair/rebuild를 요구한다.

## Projection rebuild

1. 새 generation을 `BUILDING`으로 만들고 position 0에서 시작한다.
2. 현재 active generation은 query를 계속 제공한다.
3. 새 generation은 자체 checkpoint로 bounded replay하며 중단 후 재개한다.
4. stream head에 도달하면 row count, aggregate version, canonical state digest를 검증한다.
5. 검증 성공 시 active generation pointer를 transactionally 전환한다.
6. 이전 generation은 즉시 삭제하지 않고 bounded retention 뒤 cleanup한다.

rebuild 중 들어온 event는 head catch-up 단계에서 반영한다. activation transaction 직전에
target head를 다시 읽어 뒤처진 generation 전환을 막는다.

projection별 `BUILDING` generation은 하나만 허용한다. rebuild mutation은 operator
idempotency key와 expected active-generation token을 요구하며 stale token은 412로 거부한다.
이전 generation은 24시간, 최대 2개만 유지한다. per-tenant 동시 rebuild는 하나이고 전체
module 동시 rebuild도 하나다. replay page는 최대 200 events/2 MiB, transaction은 최대 2초,
전체 rebuild는 100,000 events 기준 10분 예산과 cancellation checkpoint를 가진다.

operator UI는 현재 service 중인 active generation과 새 rebuild generation을 별도 panel로
표시하며 다음 상태/action 계약을 따른다.

| 상태 | 사용자 표시 | mutation/recovery action |
|---|---|---|
| `CAUGHT_UP` | active generation, `0 events behind`, 마지막 진행 시각 | business mutation 허용, rebuild 시작 허용 |
| `CATCHING_UP` | active generation, `N events behind`, 마지막 진행 시각 | stale projected revision 기반 business mutation은 disable하고 refresh 이유를 표시 |
| `DEGRADED` | active generation과 poison position/reason class | business mutation은 affected aggregate에 대해 fail closed, 원인 수정 뒤 poison retry 또는 rebuild 선택 |
| `BUILDING` | active와 building generation, 시작/현재/target position | 중복 rebuild disable, status/cancel만 허용 |
| `VALIDATING` | active와 candidate generation, 검증 항목 진행률 | activation/rebuild 중복 action disable, status/cancel만 허용 |
| `FAILED` | active generation 유지, bounded failure summary와 resume 가능 여부 | retryable failure는 resume, invariant/digest failure는 원인 수정 후 새 rebuild |

poison event의 handler/upcaster가 수정됐고 현재 generation을 이어 갈 수 있으면 targeted retry를
우선한다. invariant/digest 불일치, 넓은 schema 변경, generation 손상이면 full rebuild를 선택한다.
projection recovery와 business reconciliation은 서로 다른 panel/action으로 표시한다. 모든
mutation action은 진행 중 중복 클릭을 disable하고 idempotency key와 expected generation token을
유지한다. 412 stale token/revision은 최신 generation/revision을 다시 읽고 기존 입력을 자동
재제출하지 않으며 사용자가 다시 확인하도록 설명한다.

## 자원과 성능 예산

- Hikari pool 기본 20: foreground 14 permits, projector 3, rebuild 1, maintenance/readiness 2
- virtual thread도 DB permit을 얻기 전에는 connection을 요청하지 않는다.
- foreground DB bulkhead는 permit 보유자와 대기자를 합쳐 최대 128 requests로 제한하고 permit
  대기는 최대 250 ms다. admission 초과나 acquire timeout은 stable code
  `DATABASE_BULKHEAD_REJECTED`/503, `Retry-After: 1`로 fail fast하며 connection pool wait로
  넘기지 않는다.
- foreground lock timeout 500 ms, statement timeout 3 s
- projector/rebuild batch 최대 200 events 또는 2 MiB, transaction 최대 2 s
- idle poll backoff 100 ms에서 2 s 사이, lag 증가 시 projector 우선순위를 높이되 foreground
  14 permits는 침범하지 않는다.
- projector는 projection별 단일 owner lease와 fencing token을 사용한다.
- rebuild는 projection lag가 10,000 events를 넘거나 foreground permit saturation이 80%를
  넘으면 새 batch 시작을 throttling한다.

성능 profile은 terminal response와 committed append를 별도 metric으로 기록하고 다음 두
workload를 분리한다.

- **Hot campaign profile:** 64 concurrent virtual-thread clients가 단일 campaign에 1,000개
  allocation command를 보낸다. 각 client는 최신 revision을 다시 읽고 bounded retry해 실제
  append 경로를 부하하며, same-version conflict fixture는 별도로 실행한다. terminal
  operations/s, successfully appended operations/s, append 성공 비율, 409 비율, stream-head
  wait p95/p99를 각각 기록한다. acceptance는 최소 20 successfully appended operations/s,
  전체 command의 95% 이상이 bounded retry 안에 append 성공, p95 2초 이하, p99 5초 이하,
  lock/statement timeout 1% 이하로 고정한다.
- **Independent streams profile:** 64 concurrent clients를 32개 독립 campaign에 균등 분배해
  campaign-head 경합과 무관하게 global append fence를 통과시킨다. 최소 40 successfully
  appended operations/s, append-fence wait p95 100 ms 이하/p99 500 ms 이하,
  lock/statement timeout 1% 이하를 acceptance로 고정한다.

same-version conflict fixture는 성공 1건과 명시적 409를 별도 집계해 business conflict를
timeout 실패나 append throughput과 섞지 않는다. 두 profile 모두 terminal operations/s와
successfully appended operations/s, Hikari wait, append-fence wait, stream-head wait를 함께
보고한다. CI correctness gate는 모든 request가 60초 안에 terminal result로 수렴하고 bounded
bulkhead와 connection starvation 없음만 검증하며, 환경 민감한 throughput/percentile target은
dedicated PostgreSQL performance profile에서 판정한다.

## 장애 처리

| 장애 | 동작 | 복구 |
|---|---|---|
| expected-version 충돌 | 전체 append/finalize rollback, `409` | caller가 최신 state를 읽고 새 command를 결정한다. |
| idempotency owner 상실 | append 전 rollback | lease/takeover 계약에 따라 retry한다. |
| append 후 response 유실 | terminal descriptor와 event는 함께 commit | 같은 key retry가 동일 response를 replay한다. |
| duplicate projection delivery | processed identity로 no-op | checkpoint가 정상 진행한다. |
| delayed aggregate event | gap을 기록하고 적용 보류 | predecessor 적용 뒤 재시도한다. |
| projector 중단 | batch transaction rollback | 같은 checkpoint에서 재시작한다. |
| unknown schema/invariant 위반 | projection `DEGRADED`, checkpoint 정지 | handler/upcaster 수정 뒤 retry 또는 새 generation rebuild |
| command rehydration schema failure | affected aggregate append 차단, `503` | handler/upcaster 수정과 replay verification 뒤 해제한다. |
| snapshot 손상 | snapshot 무시, full replay | metric/log 후 새 snapshot을 생성한다. |
| rebuild digest 불일치 | activation 거부 | 현재 generation 유지, 원인 수정 후 rebuild한다. |

poison event를 넘어 checkpoint를 몰래 이동시키지 않는다. 원본 event 수정과 무감사 skip은
지원하지 않는다.

## 보안과 개인정보

- raw user/device/IP는 request boundary에서 tenant-scoped pseudonym 또는 surrogate id로 바꾼다.
- retention 동안 안정적인 상관관계가 필요한 저엔트로 값은 purpose별 versioned HMAC-SHA256과
  tenant/domain separation을 사용한다. HMAC은 key와 원본 후보가 남아 있는 동안 다시 연결할
  수 있는 pseudonymization이며 erasure로 간주하지 않는다.
- subject erasure가 필요한 event identity는 random per-subject surrogate id를 저장하고 원본과
  surrogate의 lookup mapping을 event store 밖의 deletable mapping에 둔다. erasure는 이 mapping과
  원본을 삭제해 event의 surrogate에서 subject로 가는 연결을 끊는다.
- HMAC key version은 event/descriptor에 저장하고 active-read key ring, rotation, 최대 retention
  뒤 retirement 계약을 적용한다. shared HMAC digest의 연결 가능성은 retention/key retirement까지
  유지된다는 점을 개인정보 문서와 operator UI에 명시한다.
- raw voucher code와 idempotency key는 event, snapshot, projection, log, metric에 기록하지
  않는다.
- failure record는 event metadata, bounded reason, exception classification만 저장한다.
- metric label에는 tenant, campaign, user, voucher, event id를 넣지 않는다.
- operator API는 #534의 loopback/allowed-host, constant-time secret+guard, same-origin,
  JSON-content 조건을 fail closed로 재사용하고 bounded pagination/admission을 적용한다.
- rebuild/retry mutation은 idempotency key, expected generation, projection별 단일 BUILDING
  invariant, rate limit, immutable redacted operator audit를 요구한다.
- browser와 SSE payload는 projection 상태와 redacted audit metadata만 노출한다.

## #534 호환 계약

#534의 command route, request shape, stable error code, `Idempotency-Key`,
`Idempotency-Replayed`, `Retry-After`, snapshot-first SSE 의미를 유지한다. event-sourced variant는
다음 metadata/API만 추가한다.

- command response의 authoritative stream position
- query/SSE의 projection position, stream position, lag, generation
- projection health, failure, retry, rebuild start/status endpoint
- normalized-state baseline과 event-sourced variant 비교 fixture/report

projection lag 때문에 #534가 즉시 일관성으로 보이던 query 결과를 최신이라고 가장하지
않는다. command terminal response는 즉시 반환하고 query는 lag metadata 또는
`PROJECTION_PENDING`을 명시한다.

## 테스트 전략

### Domain

- reducer full replay 결정성
- campaign/voucher state transition과 capacity invariant
- event type/schema registry와 upcaster
- snapshot state digest
- Kotlin test는 `bluetape4k-assertions`를 우선 사용한다.

### PostgreSQL integration

- 같은 expected version에서 동시 append가 하나만 성공
- campaign/voucher multi-stream append의 all-or-nothing
- idempotency acquire/finalize cut point와 response-loss replay
- duplicate, delayed, interrupted projection delivery
- poison event 정지와 retry 복구
- rebuild interruption/resume와 generation activation race
- full replay와 snapshot+tail 동등성
- PostgreSQL Testcontainers를 권위 환경으로 사용한다.

### HTTP/SSE와 운영

- JDK `HttpClient` connector를 쓰는 live `WebTestClient`
- management와 application client 모두 공통 60초 `HTTP_TIMEOUT`
- #534와 같은 black-box command/failure fixture
- projection convergence는 Awaitility로 검증하며 `Thread.sleep`을 사용하지 않는다.
- SSE cursor, reconnect, snapshot-first, bounded queue/payload
- operator health, lag, rebuild, poison event, redaction
- GET 202 pending 전용 분기, 마지막 projection 유지, bounded GET-only retry, manual refresh
- operator 상태/action matrix, 412 reload, duplicate-action disable, retry와 rebuild 선택 기준
- `aria-live`, text status, disabled-action reason을 유지하고 lag/rebuild/degraded/pending 전환을
  색상이나 숫자만이 아니라 screen reader가 인식할 수 있는 문구로 검증
- hostile oversized/deep JSON, replay cap, rebuild quota, operator stale-generation/idempotency
- idempotency scope 간 principal/operation/resource 격리와 key rotation replay
- erasure fixture는 subject의 surrogate A를 기록한 뒤 원본/mapping을 삭제하고 reverse lookup이
  실패하며, 같은 raw identity 재등록 시 다른 surrogate B가 발급되고 기존 event/projection/API로
  mapping 또는 raw identity를 복구할 수 없음을 검증
- application DB role과 mutation-denying trigger가 event update/delete를 거부하는지 검증
- upcaster golden fixture, 반복 결정성, chain hard cap
- static browser contract와 executable scenario

### 저장소 검증

- targeted test, 전체 신규 module test/build, detekt
- `./gradlew projects`와 module registration
- smoke/full/nightly workflow matrix와 stale-check script
- README locale set, architecture/sequence diagram validation
- `git diff --check`

Testcontainers/real PostgreSQL test는 다른 heavy test와 병렬 실행하지 않는다.

## Module 등록과 문서

새 module이므로 다음 chain을 같은 branch에서 갱신한다.

- `settings.gradle.kts` 자동 등록 결과와 `./gradlew projects`
- root README와 commerce README의 module map
- English/Korean module README
- Examples/smoke/full/nightly workflow group과 summary `needs`
- smoke/stale validation script
- Kover/Codecov artifact 경로가 적용되는 경우 그 등록
- architecture와 command/projection/rebuild sequence diagram
- #534 대비 consistency, replay, migration, operational trade-off 표

container-backed full integration은 daily lightweight smoke와 분리한다.

## Migration과 rollout

#534 data를 자동으로 변환하지 않는다. 비교 fixture는 동일한 canonical command sequence를 두
module에 새로 실행한다. 문서는 실제 migration을 다음 단계로 설명한다.

1. normalized baseline에서 canonical initial event를 생성한다.
2. event stream과 projection을 shadow 구축한다.
3. state digest와 business invariant를 비교한다.
4. command routing을 전환한다.
5. rollback 시 normalized baseline 또는 이전 active projection generation을 유지한다.

이 예제는 migration 실행기를 production 기능으로 제공하지 않으며 trade-off와 검증 계약만
문서화한다.

## Acceptance criteria

1. concurrent command가 같은 expected aggregate version에 두 event를 append하지 못한다.
2. same-key retry가 하나의 terminal response를 replay하고 duplicate event를 만들지 않는다.
3. campaign capacity와 voucher lifecycle projection이 full replay마다 같은 결과를 만든다.
4. duplicate, delayed, interrupted projection delivery가 진단 가능하고 수렴한다.
5. snapshot+tail 결과가 full replay 결과와 같다.
6. poison event가 checkpoint를 조용히 건너뛰지 않고 degraded 상태와 복구 절차를 노출한다.
7. rebuild가 active generation을 유지하며 검증된 새 generation만 활성화한다.
8. #534 black-box command/failure fixture가 호환된다.
9. event/log/metric/projection/API가 금지된 민감정보를 노출하지 않는다.
10. operator UI가 stream/projection position, lag, rebuild, reconciliation을 구분한다.
11. Java/Kotlin 25 toolchain과 virtual threads가 실제 compile/runtime test로 증명된다.
12. module, workflow, README, diagram, stale-check 등록이 완전하다.
13. commit-safe global position이 concurrent commit-order inversion에서도 event를 누락하지 않는다.
14. idempotency principal scope, key rotation replay, operator fencing, append-only DB guard가
    hostile fixture에서 fail closed한다.
15. bounded replay/rebuild와 foreground/background permit 예산이 starvation 없이 지켜진다.
16. browser가 `PROJECTION_PENDING`을 snapshot으로 렌더링하지 않고 마지막 projection을 유지한 채
    GET만 bounded retry하며 command를 중복 제출하지 않는다.
17. operator UI가 projection 상태별 허용 action, stale token/revision, retry/rebuild 선택,
    접근 가능한 status와 disabled reason을 일관되게 표현한다.
18. subject mapping 삭제가 기존 surrogate의 reverse lookup을 차단하고 재등록된 identity에 새
    surrogate를 발급한다.

## Definition of Done

- 승인 spec과 구현 plan의 모든 acceptance criterion이 test/command에 추적된다.
- spec/plan/final review에서 P0=0, P1=0이다.
- targeted와 전체 module test, build, detekt, smoke/stale validation이 통과한다.
- real PostgreSQL concurrency, replay, rebuild, HTTP/SSE test가 순차 실행으로 통과한다.
- README와 diagram이 구현, endpoint, 운영 절차와 일치한다.
- lesson 문서와 Lore commit이 포함된다.
- exact authorized head를 push하고 English PR을 생성해 live CI/review를 통과한다.
- merge-ready 보고 후 fresh merge 승인을 기다린다.

## 알려진 위험과 완화

- campaign capacity stream hotspot: #534 row-lock baseline과 같은 경합을 의도적으로
  보여 주며 capacity sharding은 별도 후속 범위로 둔다.
- multi-stream deadlock: stream key 정렬 lock과 짧은 transaction으로 방지한다.
- unbounded replay: bounded batch, snapshot, generation checkpoint로 제한한다.
- projection table 증가: 이전 generation bounded retention과 cleanup metric을 둔다.
- schema evolution: stable event name, integer schema version, explicit upcaster로 제한한다.
- Java 21 root와 Java 25 module 혼합: 기존 module-local override 패턴을 재사용하고 전체
  Java 25 전환 뒤 중복 설정 제거 여부를 검토한다.

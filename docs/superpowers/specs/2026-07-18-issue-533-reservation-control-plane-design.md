# Issue #533 Reservation Control Plane 설계

## 목표

브라우저에서 capacity resource의 hold, expiry, confirmation, cancellation, waitlist
promotion을 실행하고 진단할 수 있는 Spring Boot reference application을
`commerce/reservation-control-plane`에 추가한다.

PostgreSQL이 resource capacity, hold ownership, expiry, confirmation, release,
waitlist 순서와 idempotency 결과의 유일한 권위다. Redis와 leader election은 요청
폭주와 중복 sweeper 실행을 줄이는 보조 계층이며, Redis의 상태만으로 예약 결과를
판정하지 않는다.

핵심 완료 조건은 다음과 같다.

- 같은 resource를 향한 동시 hold 요청은 PostgreSQL CAS를 통해 capacity를 초과하지 않는다.
- 같은 idempotency key와 같은 payload는 저장된 HTTP 결과를 재생한다.
- 같은 key와 다른 payload는 결정적인 `409 Conflict`를 반환한다.
- expired hold는 한 번만 release되고 waitlist의 다음 후보는 한 번만 offer를 받는다.
- sweeper가 중복 실행되거나 재시작되어도 finalization과 promotion이 중복되지 않는다.
- Redis 장애나 lease 유실이 durable reservation 결과를 바꾸지 않는다.
- 브라우저는 resource calendar/capacity, hold countdown, waitlist position/range,
  offer expiry와 stale confirmation reason을 보여준다.

## 범위와 비범위

### 포함

- resource별 capacity와 timezone/policy version 관리
- hold, confirm, cancel, extend, waitlist join, offer, accept-offer command
- owner token 원문을 저장하지 않는 hold ownership 검증
- PostgreSQL 기반 HTTP idempotency lease와 terminal replay
- Redis 기반 admission bulkhead와 짧은 in-flight suppression
- Redis Lettuce leader election으로 조정되는 expiry sweeper
- application-owned notification outbox와 deterministic fake notification adapter
- browser UI, live HTTP API, Actuator/Micrometer 운영 정보
- fake clock 기반 expiry, timezone/DST, restart fixture

### 제외

- 범용 reservation core, 전역 scheduler, 공용 idempotency repository
- Redis를 capacity/hold/waitlist의 durable authority로 사용하는 설계
- 실제 email/SMS/push, calendar, FHIR, provider integration
- 장기 실행 workflow engine과 외부 message broker
- 미배포된 `@LeaderScheduled` 또는 #1055/#391 test-only API

## 권위와 보조 계층

| 계층 | 책임 | 장애 시 동작 |
|---|---|---|
| PostgreSQL | resource version, capacity, hold, waitlist, offer, audit, idempotency, notification outbox | 명령을 완료할 수 없으므로 안정 error로 실패한다. |
| Redis admission | 분산 admission 수 제한 | node-local semaphore로 축소 운전하고 PostgreSQL 판정을 계속한다. |
| Redis suppression | 같은 command의 짧은 중복 진입 억제 | PostgreSQL idempotency/CAS로 바로 내려간다. |
| Redis leader | 여러 instance 중 sweeper trigger 하나를 우선 실행 | 해당 tick을 건너뛰고 다음 정상 tick 또는 operator sweep이 PostgreSQL expiry를 처리한다. |
| Fake notification | outbox delivery와 duplicate suppression 증명 | 실패 횟수를 결정적으로 주입하고 미전송 row를 재시도한다. |

Redis 장애 시 fail-open은 예약 권위를 Redis에 두지 않는다는 의미다. node-local bulkhead는
프로세스를 보호하고, PostgreSQL idempotency와 CAS가 최종 중복/경합 판정을 유지한다.

## 상태 모델

| Aggregate | 상태 | 권위 규칙 |
|---|---|---|
| `CapacityResource` | `OPEN`, `PAUSED`, `CLOSED` | `capacity`, `occupiedCount`, `revision`, `policyVersion`을 CAS로 갱신한다. `occupiedCount = HELD + CONFIRMED + ACTIVE_OFFER`다. |
| `ReservationHold` | `HELD`, `CONFIRMED`, `EXPIRED`, `CANCELLED`, `RELEASED_BY_OPERATOR` | reservation-session owner digest, expiry, resource revision을 보관하며 terminal 상태는 재전이하지 않는다. |
| `WaitlistEntry` | `WAITING`, `OFFERED`, `ACCEPTED`, `EXPIRED`, `CANCELLED` | 같은 reservation-session owner digest를 보관한다. position은 stable sequence로 정하고 한 entry에는 활성 offer가 하나뿐이다. |
| `ReservationOffer` | `ACTIVE`, `ACCEPTED`, `EXPIRED`, `CANCELLED` | entry의 owner digest를 계승하고 expiry와 entry/version CAS가 모두 맞을 때만 accept한다. |
| `NotificationDelivery` | `PENDING`, `DELIVERED`, `RETRYING`, `EXHAUSTED` | `(event_type, aggregate_id, aggregate_revision, channel)` unique key로 중복 전달을 막고 bounded retry 후 operator redrive만 허용한다. |

모든 상태 전이는 `(aggregate_type, aggregate_id, revision)` unique audit row로 남긴다.
업무 시간의 유일한 권위는 주입된 `Clock`이다. transaction 시작 시 `now: Instant`를 한 번
캡처하고 expiry, idempotency lease, CAS 조건과 응답 snapshot에 같은 값을 전달한다. DB
`CurrentTimestamp`는 `createdAt`/`updatedAt` audit 전용이며 업무 만료 판정에 사용하지 않는다.
resource `ZoneId`는 표시와 정책 평가에만 사용한다. DST fixture는 같은 local time이 서로
다른 offset을 갖는 경우와 존재하지 않는 local time을 명시적으로 검증한다.

## PostgreSQL CAS와 repository 패턴

공개 API인 `LongAuditableJdbcRepository`를 aggregate repository의 기반으로 사용한다.
단순 `updateById`에는 expected revision 조건이 없으므로 예약 전이에는 사용하지 않는다.

각 application repository는 `auditedUpdateAll`로 다음 조건을 한 SQL UPDATE에 둔다.

- resource/hold/offer id 일치
- expected revision과 현재 state 일치
- owner hash 또는 expiry 조건 일치
- capacity 또는 활성 offer unique constraint 유지

`affectedRows == 1`만 성공으로 판정한다. `0`이면 최신 snapshot을 다시 읽어
`CAPACITY_EXHAUSTED`, `STALE_REVISION`, `HOLD_EXPIRED`, `OWNER_MISMATCH`,
`OFFER_EXPIRED` 같은 bounded reason으로 분류한다. repository는 이 결과를 sealed outcome으로
반환하고 controller가 DB exception text를 노출하지 않게 한다.

## HTTP idempotency 계약

scope는 `(tenant_id, operation, idempotency_key_digest)`다. tenant와 audit actor는 client
입력에서 받지 않고 server-side demo principal/configuration에서만 도출한다. client key는
최소 128-bit random value 형식을 검증하고 server secret으로
`HMAC-SHA-256(tenant || operation || key)`을 계산한다. 원문 key는 저장하지 않으며 request
DTO와 owner-token digest는 canonical fingerprint에 함께 포함한다.

1. record가 없으면 `IN_PROGRESS`, idempotency owner token, lease deadline을 원자적으로 획득한다.
2. 같은 fingerprint의 terminal row는 저장된 status/body를 재생한다.
3. 다른 fingerprint는 `409 IDEMPOTENCY_FINGERPRINT_CONFLICT`다.
4. 유효한 다른 owner의 `IN_PROGRESS`는 `409 COMMAND_IN_PROGRESS`와 retry-after를 준다.
5. 만료 lease는 새 owner가 인수하고 이전 owner finalize는 owner token CAS로 거부한다.
6. 업무 상태와 terminal result는 같은 Spring/Exposed transaction에서 확정한다.
7. lease는 90초, Spring transaction timeout은 60초다. terminal finalize owner CAS가
   `affectedRows != 1`이면 예외를 발생시켜 업무 mutation 전체를 rollback한다.
8. PostgreSQL lock/statement timeout은 5초이며 lock timeout/deadlock은 stable retryable
   reason으로 분류한다.

reservation owner token은 hold 하나가 아니라 browser reservation session 전체의 credential이다.
browser가 첫 command 전에 `crypto.getRandomValues`로 256-bit 값을
생성한다. query/cookie/body가 아니라 `X-Reservation-Owner` header로만 전송하고 server는
domain-separated HMAC digest만 저장한다. 비교는 `MessageDigest.isEqual`로 수행한다. 응답과
idempotency terminal body에는 raw token을 넣지 않으므로 replay 결과가 동일하고 DB에도
원문이 남지 않는다. hold confirm/cancel/extend뿐 아니라 waitlist snapshot/cancel과 offer
snapshot/accept도 같은 header가 필요하며 다른 owner는 `403 OWNER_MISMATCH`다.

#391의 fixture는 `src/test`에만 있고 #1055도 공용 store API를 만들지 않으므로 배포
의존성으로 사용하지 않는다. #532와 같은 계약을 #533의 command scope에 맞게 좁은
application-owned repository로 재현한다. 두 예제의 실제 중복은 향후 공용 계약/fixture를
검토할 근거이지 이번 구현의 선행 조건이 아니다.

## Redis admission과 in-flight suppression

`bluetape4k-lettuce`의 공개 API를 직접 사용한다.

- `LettuceClients`가 `RedisClient`와 connection lifecycle을 관리한다.
- `LettuceSemaphore`는 HTTP command admission에만 사용한다. distributed permit은 64,
  acquire timeout은 100ms, lease는 5초로 둔다.
- `LettuceLock.tryLock(waitTime = ZERO, leaseTime = short)`은 동일 command fingerprint의
  짧은 in-flight suppression에만 사용하며 lease는 2초다.
- Redis token, semaphore count, lock lease는 resource availability를 표현하지 않는다.
- suppression hit는 PostgreSQL idempotency snapshot을 읽어 replay 또는
  `COMMAND_IN_PROGRESS`로 수렴한다.
- Redis exception은 `redis_admission_degraded` 또는 `redis_suppression_degraded`로
  기록하고 node-local semaphore + PostgreSQL 경계로 내려간다.

Redis 상태와 무관하게 node-local DB bulkhead는 항상 적용한다. Hikari 8개 중 foreground
HTTP/query에 최대 5개, sweeper/notification에 최대 1개만 허용하고 2개를 lifecycle/operator
headroom으로 남긴다. local permit 대기는 100ms 후 `429 ADMISSION_REJECTED` 또는
`503 BACKGROUND_BUSY`로 끝나며 수천 virtual thread가 Hikari connection을 기다리지 않게 한다.

ad-hoc `SET NX PX`와 Lua unlock을 다시 구현하지 않는다. Bluetape Lettuce lock이 이미
token-checked unlock과 lease를 제공한다.

## Leader-guarded expiry sweeper

`bluetape4k-leader-core`와 `bluetape4k-leader-redis-lettuce` 0.4.0을 사용한다.
Spring `@Scheduled` method가 `LettuceLeaderElector.runIfLeaderResult(
LeaderSlot("reservation-expiry-sweep", instanceId))`를 호출한다. 0.4.0의 result API는
`Elected`, `Skipped`, `ActionFailed`를 구분하고 lock 획득 전 Redis backend exception은
throw하므로 adapter가 이를 별도 `backend-failed` outcome으로 sanitize한다.
현재 develop에만 있는 `@LeaderScheduled`는 BOM 1.3.1의 배포 JAR에 없으므로 사용하지 않는다.

leader lease는 trigger 중복을 줄일 뿐 correctness 경계가 아니다. scheduler는 `fixedDelay`,
node-local single-flight와 15초 auto-extended leader lease를 사용한다. 한 tick은 최대 32개,
5초 time budget을 가지며 resource 하나를 transaction 하나로 처리한다.

canonical lock order는 `resource -> hold/offer -> waitlist -> outbox -> idempotency`다. 만료
후보 index는 `(state, expires_at, id)`, waitlist index는
`(resource_id, state, sequence, id)`다. resource를 잠근 뒤 FIFO head를
`ORDER BY sequence, id FOR UPDATE LIMIT 1`로 선택한다.

expired hold finalization, `occupiedCount` accounting, transition audit, 다음 waitlist offer와
notification outbox enqueue는 같은 PostgreSQL transaction에서 처리한다. ACTIVE offer가
생기면 해제된 capacity를 그대로 점유하므로 count를 유지하고 후보가 없을 때만 감소시킨다.
offer accept는 점유 수를 늘리지 않는다. 동일 batch가 두 번 실행되거나 process가 transaction
중간에 종료되면 전체 rollback되어 다음 sweep이 다시 처리한다.

`@Scheduled`/leader adapter와 `@Transactional` resource finalization은 서로 다른 Spring
bean으로 분리해 proxy self-invocation을 피한다. scheduler는 resource id를 고르고
`ReservationResourceTransactionService.finalizeExpiredResource(resourceId, now)`를 호출하며,
이 service만 canonical lock order와 한-resource transaction을 소유한다.

## Notification outbox와 fake adapter

실제 notification provider는 사용하지 않는다. 상태 전이 transaction에서 application-owned
delivery row를 추가하고, fake adapter는 row를 읽어 결정적인 성공/실패를 기록한다.

- stable `deliveryId` unique key로 같은 transition의 중복 enqueue를 막는다.
- `failFirstAttempts` fixture로 재시도와 restart를 재현한다.
- payload는 channel, template code, aggregate id와 bounded variables만 보관한다.
- email, phone, owner token 같은 PII는 저장/로그하지 않는다.

delivery worker는 owner/lease, attempt count, `nextAttemptAt`으로 row를 claim하고 stale owner
finalize를 거부한다. fake provider에는 stable `deliveryId`를 idempotency key로 전달하며
provider도 이를 deduplicate한다. `provider accepted -> application crash -> restart` fixture는
외부 effect가 한 번임을 검증한다. provider가 idempotency를 지원하지 않는 실제 integration의
보장은 at-least-once임을 README에 명시한다. exponential bounded backoff로 최대 5회 시도한
뒤 `EXHAUSTED`가 되며, operator redrive는 새 idempotent audit command로 attempts와
`nextAttemptAt`을 초기화한다.

Spring Modulith는 #532에서 비동기 publication 계약을 이미 보여주지만, #533의 핵심은
sweeper finalization과 waitlist notification의 동일 PostgreSQL transaction/unique constraint다.
따라서 이번 예제에는 더 작은 application-owned notification outbox를 채택한다.

## Browser와 HTTP

- `/`는 dependency 없는 static HTML/vanilla JavaScript console이다.
- resource calendar/capacity와 현재 policy version을 표시한다.
- browser는 첫 command 전에 raw owner token을 만들고 memory에만 보관한다. server 응답에는 token이 없다.
- countdown은 server `expiresAt`과 현재 snapshot time을 기준으로 표시한다.
- waitlist position은 exact position 또는 bounded range와 계산 시점을 함께 표시한다.
- stale confirm/accept는 stable reason과 current revision/expiry만 반환한다.
- operator force release와 bounded manual sweep은 별도 endpoint와 audit actor를 사용한다.

browser command state machine은 command마다 128-bit 이상의 idempotency key와 canonical
payload를 memory에 terminal 응답까지 보관한다. timeout, connection reset,
`COMMAND_IN_PROGRESS`는 같은 key와 같은 payload로 재시도하고 초 단위 `Retry-After` header를
따른다. payload가 바뀌면 새 key를 만들고 terminal response/replay를 받으면 pending state를
제거한다. resource/hold/waitlist/offer snapshot은 2초마다 polling하고 tab이
`visibilitychange`로 다시 보이면 즉시 refresh한다. client countdown이 0이 되어도 상태를
추측하지 않고 server snapshot을 다시 읽는다. fake notification은 outbox 관찰 증거일 뿐
browser delivery channel이 아니며 active offer는 polling으로 발견한다.

memory-only credential은 보안상 의도된 제약이다. active hold/offer가 있는 동안 reload/tab
close를 경고하며 reload 후 credential 복구는 지원하지 않는다. 잃어버린 session은 expiry 또는
operator 절차로만 정리된다. deterministic demo는 UTC, capacity 1, hold TTL 30초, offer TTL
20초 resource를 seed하고 두 browser contention과 reset 절차를 README에 고정한다.

### HTTP command 계약

모든 public command는 `Idempotency-Key`, `X-Reservation-Owner`, `X-Request-Id`와
`policyVersion`을 요구한다. aggregate mutation은 `expectedRevision`도 요구한다.

| Command | Method/path | 입력 | 성공/replay |
|---|---|---|---|
| hold | `POST /api/resources/{resourceId}/holds` | `policyVersion`, requested slot | `201` hold snapshot / replay도 `201` |
| confirm | `POST /api/holds/{holdId}/confirm` | `expectedRevision`, `policyVersion` | `200` hold snapshot / replay도 `200` |
| cancel/extend | `POST /api/holds/{holdId}/cancel|extend` | `expectedRevision`, `policyVersion`, extend seconds | `200` hold snapshot / replay도 `200` |
| waitlist join | `POST /api/resources/{resourceId}/waitlist` | `policyVersion` | `201` entry snapshot / replay도 `201` |
| waitlist cancel | `POST /api/waitlist/{entryId}/cancel` | `expectedRevision`, `policyVersion` | `200` entry snapshot / replay도 `200` |
| offer accept | `POST /api/offers/{offerId}/accept` | `expectedRevision`, `policyVersion` | `200` offer/hold snapshot / replay도 `200` |

owner-protected hold/waitlist/offer snapshot query도 `X-Reservation-Owner`를 요구한다. 공통 error
DTO는 `code`, `reason`, `requestId`, `retryable`, `currentRevision`, `expiresAt`,
`retryAfterSeconds`다. malformed/key validation은 `400`, owner mismatch는 `403`, missing
aggregate는 `404`, stale revision/policy/idempotency conflict/in-progress는 `409`, expired
terminal state는 `410`, admission은 `429`, PostgreSQL/background unavailable은 `503`으로
고정한다. stale confirmation reason은 `HOLD_EXPIRED`, `HOLD_CANCELLED`,
`HOLD_RELEASED_BY_OPERATOR`, `ALREADY_CONFIRMED`, `STALE_REVISION`,
`POLICY_VERSION_MISMATCH`로 구분하며 UI는 각각 refresh, terminal 안내, 재시도 금지 행동을
결정적으로 표시한다.

demo tenant는 server configuration에 고정하고 client supplied tenant/audit actor는 거부한다.
operator endpoint는 default profile에서 등록하지 않으며 `workshop.operator.enabled=true`인
local/test profile에서만 노출한다. `OperatorPrincipalResolver`는 외부 설정으로 주입된 key
digest를 server-side `actorId + roles`에 매핑하고 `MessageDigest.isEqual`로 비교한다. 모든
`/operator/**` route와 projection은 같은 filter에서 `X-Operator-Key`와 `OPERATOR` role을
검증한다. client supplied tenant/actor/role은 무시하지 않고 거부한다.

force release는 idempotency key, `expectedRevision`, bounded reason code를 요구하며 기존
resource-lock transaction 경로를 재사용해 capacity, promotion, outbox, audit를 함께 확정한다.
terminal hold 재호출은 저장된 replay/no-op 또는 stable conflict다. manual sweep은
`maxResources <= 32`, 5초 budget과 node-local single-flight를 강제하고 scheduled sweeper와
같은 application service를 호출한다. audit에는 `actorId`, reason code, requestId,
before/after revision과 outcome을 남긴다. operator panel은 local/test에만 존재하고 key를
저장하지 않으며 force-release 확인 단계와 bounded sweep 결과 요약을 제공한다.

same-origin만 허용하고 command 응답은 `Cache-Control: no-store`, CSP와
`Referrer-Policy: no-referrer`를 사용한다. UI는 token을 persistent storage/URL/DOM에 넣지 않는다.

live HTTP 검증은 `RANDOM_PORT + WebTestClient.bindToServer()`만 사용한다. MockMvc는
사용하지 않는다.

## Java 25, virtual threads와 bounded resources

모듈 toolchain은 Java 25로 고정한다. Spring MVC, Exposed JDBC, Lettuce blocking 경계,
sweeper와 fake notification worker에 virtual threads를 적용한다.

- compile: `bluetape4k-virtualthread-api`
- runtime: `bluetape4k-virtualthread-jdk25`
- JDK 21 provider는 제외
- Tomcat fallback `threads.max=8000`
- 실제 socket 경계 `max-connections=8000`, `accept-count=1000`
- connection/keep-alive timeout 60초
- Hikari maximum pool 8, minimum idle 2
- Hikari connection timeout과 Spring transaction timeout 60초
- PostgreSQL lock/statement timeout 5초

virtual thread 수에 맞춰 DB connection을 늘리지 않는다. admission, Hikari pool, bounded
sweeper batch가 PostgreSQL session과 lock 경합을 제한한다.

## Logging과 observability 계약

모든 executable/operational class는 `bluetape4k-logging`의 `KLogging`과 lazy logging
extension을 사용한다. 순수 DTO, enum, table schema처럼 실행 경계가 없는 선언형 타입에는
의미 없는 logger를 넣지 않는다.

| 경계 | 필수 log event |
|---|---|
| HTTP/filter/controller | request accepted/rejected, command, requestId, status, latency bucket |
| Idempotency | acquired/replayed/conflict/in-progress/takeover/stale-finalize |
| Redis admission | acquired/rejected/released/degraded/local-fallback |
| Redis suppression | acquired/hit/released/degraded |
| Repository/CAS | applied/stale/constraint-classified, expected/current revision |
| Hold/waitlist policy | transition, policyVersion, bounded outcome/reason |
| Leader/sweeper | elected/skipped/backend-failed, batch size, finalized/promoted/stale count |
| Notification | queued/delivered/retried/duplicate-suppressed/failed |
| Application lifecycle | schema ready, executor started/stopped, application ready/shutdown |

로그는 안정적인 `event_name key=value` 형태로 남긴다. 성공, idempotent replay, stale CAS와
정상 business rejection은 `DEBUG` 또는 `INFO`, lifecycle은 `INFO`, authorization failure와
degraded 상태 전이 및 retry exhaustion은 `WARN`, 예상하지 못한 invariant/terminal worker
failure만 `ERROR`다. Redis degraded는 상태 전이에 한 번만 기록하고 반복을 throttle한다.
admission rejection은 metric 중심이며 log는 sample/rate-limit한다. 허용 값은 requestId,
resource/hold/entry id, revision, policyVersion, command,
outcome, bounded reason, idempotency HMAC prefix다. 다음 값은 log message, exception message 재출력, metric tag에서
금지한다.

- raw idempotency key와 raw owner token
- owner hash 전체 값
- request/response payload 원문
- email, phone, 사용자 입력 메모
- Redis token/lease owner와 database credential

`OperationalLoggingTest`는 주요 성공/실패 경계의 level과 throttle/sampling이 지켜지는지,
원문 key/token/PII와 외부 exception message/stack trace가 어떤 log에도 나타나지 않는지
검증한다. 외부 예외는 bounded code로 sanitize한다. Micrometer tag는 command/outcome/bounded reason만
사용한다. `policyVersion`, resource id, key/token digest는 tag가 아니라 log field다.

Actuator 외부 노출은 `health`만 allowlist하고 detail은 숨긴다. liveness는 process 생존만,
readiness는 PostgreSQL/schema/필수 DB 경계가 사용 가능할 때만 `UP`이다. Redis나 leader 장애는
readiness를 내리지 않고 인증된 operator projection에서 `DEGRADED`로 표시한다. projection은
`observedAt`, `lastSuccessfulSweepAt`, expiry backlog count/oldest age/stale flag, Redis degraded
state, notification retry/exhausted backlog를 제공하며 host, credential, Redis key와 raw exception
message는 반환하지 않는다. 상태 전이는 live HTTP test로 고정한다.

## 검증 전략

- 순수 transition/policy 단위 테스트
- `bluetape4k-exposed-jdbc-tests`의 `withTables(TestDB.POSTGRESQL, ...)` repository 테스트
- `PostgreSQLServer.Launcher.postgres`와 `RedisServer.Launcher.redis` 통합 테스트
- `MultithreadingTester`와 virtual-thread barrier로 capacity CAS 경쟁 검증
- hold-vs-confirm/cancel, duplicate sweeper, restart, waitlist single promotion 경쟁 검증
- capacity 1/N에서 expire/new-hold/promote/accept 4-way race와 canonical lock order 검증
- slow idempotency owner/takeover/stale finalize에서 orphan mutation rollback 검증
- Redis admission/suppression 정상, rejection, expiry, backend failure/fallback 검증
- fake clock으로 hold/offer expiry와 DST overlap/gap 검증
- notification duplicate suppression과 fail-first retry 검증
- provider accepted 후 finalize 전 crash/restart의 stable delivery id 검증
- Redis outage -> local fallback -> recovery와 client/connection close 검증
- 다른 owner의 hold/waitlist/offer 조회·cancel·accept 거부 검증
- 동일 key/payload browser retry, `Retry-After`, polling/visibility refresh 검증
- live `WebTestClient`로 command status/error DTO, hold/countdown/confirm/cancel/waitlist/operator API 검증
- operator missing/wrong key, role 부족, actor/tenant injection, projection 우회 거부 검증
- duplicate/stale/partial rollback force-release와 bounded concurrent manual sweep 검증
- PostgreSQL DOWN readiness와 Redis/leader DEGRADED projection 상태 전이 검증
- logging level/throttle event 존재와 raw owner/idempotency/PII/외부 exception 부재 검증

## 운영 runbook 계약

README는 각 장애에 대해 `signal -> 판단 -> 조치 -> 복구 확인`을 제공한다. PostgreSQL
unavailable, Redis degraded/fallback/recovery, stale last-successful-sweep와 expiry backlog 증가,
notification retry/exhausted backlog, bounded manual sweep, force release, restart 후
idempotency/outbox recovery를 포함한다. 조치는 destructive SQL이나 Redis key 삭제가 아니라
인증된 bounded operator command와 상태 projection을 우선 사용한다.

## 리스크와 완화

| 리스크 | 완화 |
|---|---|
| Redis가 예약 권위처럼 사용됨 | Redis 장애/eviction 후에도 같은 PostgreSQL 결과가 나오는 contract test를 둔다. |
| leader가 단일 실행을 완전히 보장한다고 가정 | 모든 sweeper write에 revision CAS와 unique promotion constraint를 둔다. |
| clock source가 섞여 expiry가 흔들림 | transaction 시작 시 주입 `Clock`의 `Instant` 하나를 캡처하고 DB timestamp는 audit 전용으로 제한한다. |
| virtual thread 폭주가 DB를 압도 | admission, Hikari 8, bounded batch/transaction으로 제한한다. |
| 모든 class에 logger를 넣어 noise 증가 | 실행 경계만 logging하고 안정 event 목록과 level을 contract test로 고정한다. |
| notification 재시도가 중복 전달 | stable delivery id, provider idempotency, claim lease와 stale finalize CAS를 둔다. |

# Issue #534 Promotion and Voucher Campaign 설계

## 목표

Java 25와 Spring Boot MVC 위에서 한정 수량 campaign allocation과 voucher redemption을
실행하는 browser-backed reference application을 만든다. PostgreSQL이 campaign policy,
capacity, allocation, redemption, review, idempotency의 유일한 권위이며 Redis는 빠른
admission과 risk signal 보조 계층으로만 사용한다.

이 예제의 핵심은 다음 계약을 실행 가능한 코드와 deterministic fixture로 증명하는 것이다.

- retry storm에서도 voucher가 중복 발급되거나 중복 사용되지 않는다.
- pause, end, revoke와 진행 중인 allocation/redemption 경합이 PostgreSQL transaction에서
  하나의 기록된 결과로 수렴한다.
- Redis 장애나 Bloom filter false positive가 terminal allocation/redemption 결과를 결정하지
  않는다.
- operator review와 delayed event reconciliation이 원본 보안 신호를 노출하지 않고
  browser에서 관찰 가능하다.
- application-owned HTTP idempotency와 PostgreSQL concurrency fixture가 #532/#533에서
  검증한 계약과 호환된다.

## 범위와 비범위

### 포함

- campaign `DRAFT`, `ACTIVE`, `PAUSED`, `ENDED` lifecycle
- voucher claim의 eligibility, allocation, review, redemption, release, expiry, revocation
- tenant/campaign/user 경계, campaign 기간, per-user limit, capacity, policy version
- allocation 시점에 생성하는 opaque voucher code
- application-owned HTTP idempotency table과 replay/conflict/takeover 계약
- PostgreSQL row lock, unique constraint, revision/CAS 기반 동시성 제어
- Redis token-bucket admission, Lettuce Bloom filter, node-local database bulkhead
- deterministic risk fixture와 operator review queue
- leader-guarded expiry/release/reconciliation worker와 PostgreSQL CAS 안전성
- Spring Modulith transactional event publication과 delayed/duplicate event inbox
- static browser UI, live REST API, snapshot-first SSE
- `bluetape4k-logging` 기반 redacted operational logging과 low-cardinality metrics

### 제외

- pre-generated voucher pool과 batch replenishment: #537
- event-sourced aggregate와 projection rebuild: #538
- 실제 fraud scoring, identity verification, WAF/CAPTCHA, e-mail/push provider
- 재사용 가능한 공용 coupon/voucher 라이브러리 추출
- broker를 전제로 한 범용 outbox framework
- 결제 취소, redeemed voucher의 회계 reversal, 금전 보상
- public internet deployment를 위한 완전한 authentication/authorization system

## 채택한 아키텍처

PostgreSQL 중심 modular monolith를 채택한다. HTTP, application service, repository,
Redis admission, background worker를 하나의 Spring Boot application에 두되 권위 경계를
명확하게 분리한다.

| 계층 | 책임 | 장애 또는 불확실성 시 동작 |
|---|---|---|
| PostgreSQL | campaign policy/capacity, claim, review, idempotency, audit, inbox | 명령의 terminal 판정을 내리는 유일한 계층이다. |
| Redis rate limit | tenant/campaign/user별 순간 admission 제한 | 장애 시 node-local bulkhead로 축소 운전하고 PostgreSQL 판정을 계속한다. |
| Redis Bloom filter | HMAC digest signal의 이전 관찰 여부 | positive는 `REVIEW_REQUIRED` 후보일 뿐 거절 근거가 아니다. |
| Node-local bulkhead | Redis fail-open 시 DB 보호 | foreground/background lane을 분리하고 포화 시 `503`과 `Retry-After`를 반환한다. |
| Leader lease | expiry/reconciliation trigger 중복 감소 | leader가 없어도 operator command와 다음 tick이 복구하며 PostgreSQL CAS가 correctness를 유지한다. |
| Spring Modulith | transaction과 함께 domain event publication 기록 | publication 실패는 backlog로 남기고 bounded replay로 복구한다. |
| Deterministic fake | risk/provider 실패와 순서 뒤바뀜 재현 | 실제 외부 자격증명 없이 동일 입력에 동일 결과를 만든다. |

Redis fail-open은 무제한 진입을 의미하지 않는다. Redis가 unavailable이면 local bulkhead가
동시 DB 진입 수를 제한하고, PostgreSQL idempotency/lock/constraint가 중복과 capacity를
최종 판정한다.

## Software stack 결정

- Java toolchain 25, Kotlin 2.4 language level
- Spring Boot 4.1 MVC/Tomcat, Java virtual threads
- `bluetape4k-dependencies:1.3.1` 단일 BOM
- catalog가 결정하는 JetBrains Exposed 버전(현재 1.3.0)
- PostgreSQL + HikariCP + Exposed JDBC
- Redis + Lettuce + Bucket4j
- Spring Modulith event publication
- Micrometer/Actuator, JUnit 5, WebTestClient

Bluetape capability는 다음 순서로 재사용한다.

- `bluetape4k-exposed-core`: auditable table과 timestamp 모델
- `bluetape4k-exposed-jdbc`: `LongAuditableJdbcRepository` 기반 repository
- `bluetape4k-exposed-spring-boot-jdbc`: Spring transaction wiring
- `bluetape4k-exposed-spring-modulith`: durable event publication
- `bluetape4k-exposed-jdbc-tests`: PostgreSQL repository test helper
- catalog의 `jetbrains-exposed-migration-jdbc`: versioned DDL/index/constraint migration
- `bluetape4k-testcontainers`: `PostgreSQLServer`, `RedisServer`
- `bluetape4k-bucket4j`: distributed rate limiter와 Lettuce proxy manager
- `bluetape4k-lettuce`: client lifecycle과 `LettuceBloomFilter`
- `bluetape4k-leader-core`, `bluetape4k-leader-redis-lettuce`: bounded worker election
- `bluetape4k-virtualthread-api`, runtime `bluetape4k-virtualthread-jdk25`
- `bluetape4k-idgenerators`, `bluetape4k-jackson3`, `bluetape4k-exposed-jackson3`
- `bluetape4k-junit5`, `bluetape4k-assertions`, `bluetape4k-logging`,
  `bluetape4k-micrometer`

개별 Bluetape BOM이나 명시 버전은 추가하지 않는다. 공개되지 않은 #1055 fixture는 build
dependency로 가정하지 않고 같은 black-box contract를 application-owned test fixture로
실행한다. #391에서 검증된 PostgreSQL 경합 계약은 현재 공개된 Exposed test surface와
실제 PostgreSQL test로 재현한다.

module은 `commerce/promotion-voucher-campaign`, Gradle project
`:commerce-promotion-voucher-campaign`, package
`io.bluetape4k.workshop.commerce.voucher`로 고정한다. migration artifact는
`src/main/resources/db/migration`, test seed는 test source에만 둔다.

#532/#533과 공유하는 application-owned idempotency port는 acquire, replay, conflict,
takeover, finalize CAS, terminal cleanup을 동일한 black-box case로 실행한다. header
`Idempotency-Key`, `Idempotency-Replayed`, `Retry-After`와 stable code
`COMMAND_IN_PROGRESS`, `IDEMPOTENCY_FINGERPRINT_CONFLICT` 의미를 바꾸지 않는다.

## Domain model

### Campaign

`campaigns`는 다음 권위 데이터를 가진다.

- `tenant_id`, `campaign_id`
- `state`: `DRAFT`, `ACTIVE`, `PAUSED`, `ENDED`
- `starts_at`, `ends_at`
- `capacity`, `allocated_count`
- `per_user_limit`, `redemption_ttl_seconds`
- `policy_version`, `revision`
- auditable timestamps

상태 전이 계약은 다음과 같다.

- `DRAFT -> ACTIVE`: 기간과 capacity가 유효할 때만 가능하다.
- `ACTIVE -> PAUSED`: 새 allocation과 redemption을 즉시 중지한다.
- `PAUSED -> ACTIVE`: 종료 전이며 policy가 유효할 때 재개한다.
- `ACTIVE|PAUSED -> ENDED`: 새 allocation을 영구 차단한다.
- `ENDED` campaign의 이미 발급된 voucher는 claim별 expiry까지 redemption할 수 있다.
  단, operator revoke는 이를 차단한다.
- policy update는 `DRAFT`, `ACTIVE`, `PAUSED`에서 가능하지만 capacity를 이미 점유된 수보다
  낮출 수 없다. 모든 변경은 `policy_version`과 `revision`을 증가시킨다.

`allocated_count`는 누적 시도 수가 아니라 현재 capacity를 소비하는 claim 수다.
`ALLOCATED`, redemption review 중인 `REVIEW_REQUIRED`, `REDEEMED`가 각각 1을 기여한다.
allocation review, `ELIGIBLE`, `RELEASED`, `EXPIRED`, `REVOKED`, `REJECTED`는 0을
기여한다. `REDEEMED`는 캠페인의 한정 발급 수량을 영구 소비하며 회계 reversal은 #534
범위 밖이다.

새 allocation은 현재 policy version을 사용한다. 이미 발급된 claim은
`allocation_policy_version`과 당시의 per-user/redemption 조건을 snapshot으로 보존한다.
현재 campaign state와 명시적으로 safety-critical한 revoke 규칙만 기존 claim에 즉시
적용한다.

### Voucher claim

`voucher_claims`는 다음 상태를 가진다. `REVIEW_REQUIRED`일 때는 반드시
`review_kind=ALLOCATION|REDEMPTION`, `pending_from_state`, `capacity_reserved`를 함께
저장해 원래 상태와 capacity 회계를 잃지 않는다.

- `ELIGIBLE`: PostgreSQL eligibility를 통과했지만 아직 code가 발급되지 않았다.
- `REVIEW_REQUIRED`: deterministic risk 또는 Bloom positive로 operator 판단이 필요하다.
  allocation review는 `pending_from_state=ELIGIBLE`, `capacity_reserved=false`이고,
  redemption review는 `pending_from_state=ALLOCATED`, `capacity_reserved=true`다.
- `ALLOCATED`: opaque code가 발급되어 redemption 가능한 상태다.
- `REDEEMED`: 정확히 한 번 terminal redemption이 기록됐다.
- `RELEASED`: 사용자/operator가 미사용 allocation을 해제했다.
- `EXPIRED`: redemption TTL이 지나 worker/operator가 종료했다.
- `REVOKED`: operator가 미사용 voucher를 무효화했다.
- `REJECTED`: allocation review에서 발급이 거절됐다. redemption review 거절은
  `ALLOCATED`로 복귀하므로 이 terminal state를 사용하지 않는다.

주요 컬럼은 `tenant_id`, `campaign_id`, public UUID `claim_id`, UUID v7
`allocation_id`, `user_digest`, `state`, `review_kind`, `pending_from_state`,
`capacity_reserved`, `allocation_policy_version`, `code_verifier`,
`generation_key_version`, `verification_key_version`, `expires_at`,
`redemption_reference_digest`, `revision`이다. 내부 repository PK는 auditable `Long`을
사용하고 모든 public lookup은 `(tenant_id, public_uuid)` composite unique/index를 거친다.
raw user/device/IP, voucher code, idempotency key는 저장하지 않는다.

| From | Command/review | To | Capacity delta |
|---|---|---|---:|
| none/eligible | immediate allocate | `ALLOCATED` | +1 |
| `ELIGIBLE` | allocation review open | `REVIEW_REQUIRED(ALLOCATION)` | 0 |
| allocation review | approve | `ALLOCATED` | +1 |
| allocation review | reject | `REJECTED` | 0 |
| `ALLOCATED` | redemption review open | `REVIEW_REQUIRED(REDEMPTION)` | 0 |
| redemption review | approve | `REDEEMED` | 0 |
| redemption review | reject | `ALLOCATED` | 0 |
| `ALLOCATED`/redemption review | release/expire/revoke | terminal state | -1 |
| `ALLOCATED` | redeem | `REDEEMED` | 0 |

DB invariant는 `allocated_count = count(capacity-contributing claims)`이며 모든 race/restart
test가 이 식을 직접 조회해 검증한다.

동일 tenant/campaign/user에 대한 active claim 수와 campaign의 `per_user_limit`는 transaction
안에서 검사한다. unique constraint는 동일 redemption reference와 `code_verifier`의 중복
적용을 막는다. 여기서 code 식별자는 raw code가 아니라 `code_verifier`다.

### Review

`voucher_reviews`는 `OPEN`, `APPROVED`, `REJECTED` 상태와 bounded reason code,
signal summary, reviewer actor digest, expected claim revision을 가진다. raw risk signal은
저장하지 않는다.

- allocation review 승인 시 campaign과 claim을 다시 lock하고 현재 capacity, period,
  per-user limit, policy를 재검사한 뒤 `ALLOCATED`, `capacity_reserved=true`, counter `+1`로
  전이한다.
- allocation review 거절은 `REJECTED`, counter `0`이다.
- redemption review 승인 시 claim과 campaign state/revoke/expiry를 다시 검사한 뒤
  `REDEEMED`, `capacity_reserved=true`, counter delta `0`으로 전이한다.
- redemption review 거절은 terminal voucher rejection으로 처리하지 않는다.
  `ALLOCATED`, `capacity_reserved=true`, counter delta `0`으로 복귀시키고 bounded review
  reason과 cooldown을 audit한다. 명시적 revoke만 counter를 감소시킨다.
- 모든 approve/reject는 `expectedRevision`을 요구하고 stale review는 `412`로 거부한다.

### Audit와 delayed event inbox

`voucher_audits`는 append-only이며 `(aggregate_type, aggregate_id, revision)`이 유일하다.
actor type, bounded reason, policy version, correlation/request digest만 기록한다.

`campaign_event_inbox`는 external/delayed fixture의 `event_id`, aggregate key, payload digest,
observed sequence, `PENDING`, `CLAIMED`, `APPLIED`, `IGNORED`, `CONFLICT`, `FAILED` status,
attempt, next-attempt, claim lease를 저장한다. 동일 `event_id`는 exactly-once transition으로
deduplicate하고, 순서가 뒤바뀐 event는 `PENDING` 또는 `CONFLICT`로 남겨 operator
reconciliation이 재적용 또는 종결한다.

핵심 index는 다음과 같다.

- campaign: `(tenant_id, campaign_id)`, `(tenant_id, state, ends_at)`
- claim: `(tenant_id, claim_id)`, `(tenant_id, code_verifier)`,
  `(tenant_id, campaign_id, user_digest, state)` partial active index,
  `(state, expires_at)` worker index
- review: `(tenant_id, status, created_at, id)`
- idempotency: scoped unique key와 `(status, expires_at)` cleanup index
- audit: `(tenant_id, campaign_id, revision, id)` SSE cursor index
- inbox/publication: `(status, next_attempt_at, id)` worker index

대표 cardinality에서 `EXPLAIN (ANALYZE, BUFFERS)`로 per-user count, code lookup, expiry,
cleanup, review queue, audit cursor가 bounded index scan인지 확인한다.

## Voucher code 계약

voucher code는 allocation 시점에 생성한다. pre-generated pool은 사용하지 않는다.

1. UUID v7 allocation id와 tenant/campaign namespace를 generation domain 입력으로 사용한다.
2. versioned generation HMAC key로 token material을 만들고
   `V{verificationKeyVersion}-{22자 ASCII Base58}{2자 checksum}` 형식으로 encode한다.
   verification version은 최대 두 자리의 bounded decimal tag이며 secret이 아니다.
3. 저장용 verifier는 독립 verification key/domain으로
   `HMAC-SHA-256(canonicalCode)`를 계산한다. DB에는 이 verifier와 두 key version만 저장한다.
   가역 Base58 token material이나 generation digest는 저장하지 않는다.
4. idempotent replay에서는 allocation id와 동일 generation key version으로 code를
   결정적으로 다시 계산하고 DB verifier와 constant-time 비교한다. plaintext code를
   idempotency response row나 log에 영구 저장하지 않는다.
5. 입력은 bounded version tag, 정확한 ASCII Base58 길이와 checksum을 bounded buffer에서
   검증한다. tag가 지정한 active verification key로 verifier를 계산하고 constant-time
   비교한다. Unicode,
   control character, oversized code, unknown key version은 동일한 redacted failure로 거부한다.

운영 key는 환경/secret manager가 제공한다. 예제 기본값은 loopback test profile에서만
deterministic test key를 제공하며 non-test startup은 짧거나 누락된 key를 거부한다.
generation, verification, identity/risk/Redis namespace key는 용도별 독립 key 또는 강제
domain separation을 사용한다. key ring은 단일 current version과 active read versions를
검증한다. old key 배포 -> current 전환 -> 최대 voucher/idempotency replay TTL 및 참조 소멸
확인 -> retire 순서를 지킨다. 참조 중인 key 누락은 startup/readiness 실패이며 rotation
rollback/rehearsal test를 둔다. packaged non-test runtime은 known test key, 짧은 secret,
test profile과 public bind 조합을 startup에서 거부한다.

## Transaction과 lock ordering

모든 multi-row command는 다음 순서를 지킨다.

1. campaign row
2. claim row
3. review row
4. audit, idempotency finalize, inbox/publication row

서로 다른 순서로 lock하지 않는다. virtual thread 코드에서 `synchronized`와
`@Synchronized`를 사용하지 않는다.

모든 JDBC 진입은 terminal idempotency replay lookup을 포함해 connection/transaction 획득
전에 node-local permit를 bounded wait로 획득한다. foreground 12개, background 4개로
분리하고 합은 Hikari max 16을 넘지 않는다. background는 worker/reconciliation reserved 1개와
SSE/maintenance 3개를 fair scheduling으로 분리한다. permit은 각 DB query/transaction 동안만
보유하고 poll interval, Redis call, HTTP/SSE write 동안 보유하지 않는다. nested permit
acquisition을 금지하고 cancellation/exception에서도 local semaphore만 idempotent하게
반환한다. permit wait는 250ms 기본값이며 worker는 tick 후 2초 안에 reserved permit로
progress할 수 있어야 한다. 실패 시
`503 DATABASE_BULKHEAD_REJECTED`와 `Retry-After`를 반환한다.

campaign capacity 변경은 campaign row를 오래 읽고 보유하는 대신 state, period, revision,
capacity predicate를 포함한 conditional counter update를 사용한다. transaction은
`SET LOCAL lock_timeout='5s'`로 foreground lock convoy를 fail-fast하고 business transaction
timeout 60초 안에 claim/audit/publication을 완료한다. application service가 `@Transactional`
경계를 소유하며 repository는 새 transaction을 열지 않고 기존 Spring transaction에
참여한다. domain event publication도 같은 JDBC transaction이 rollback되면 함께 rollback된다.

### Allocation

1. bounded request validation과 tenant/campaign/principal/user digest를 계산한다.
2. local foreground DB permit를 획득한 뒤 terminal idempotency replay를
   tenant/principal/resource ownership으로 short-circuit한다. replay response가 끝나면 permit를
   반환하고, replay miss도 조회 직후 permit를 반환한다.
3. 새 command는 Redis rate limit, deterministic risk fixture와 Bloom signal을 계산한다.
   Redis unavailable은 rate quota를 생략하지만 다음 PostgreSQL 단계의 local permit를
   생략하지 않는다.
4. local permit를 다시 획득하고 짧은 PostgreSQL transaction에서 application-owned
   idempotency owner/lease를 acquire한 뒤 permit를 반환한다.
5. local permit를 다시 획득하고 PostgreSQL business transaction에서 campaign을
   conditional update/lock하고
   state/period/capacity/per-user limit을
   검증한다.
6. idempotency owner token, lease, fingerprint를 다시 확인한다. command deadline은 lease보다
   짧고 owner를 잃었으면 business mutation 전에 중단한다.
7. risk review가 필요하면 claim/review/audit을 allocation `REVIEW_REQUIRED`로 기록한다.
8. 그렇지 않으면 claim을 `ALLOCATED`로 만들고 `allocated_count`를 정확히 한 번 증가시킨다.
9. idempotency finalize CAS와 event publication을 business mutation과 같은 transaction에서
   확정한다. DB commit 뒤 HTTP response 전 crash는 같은 key replay가 결정적으로 code와
   original status/body를 재구성한다. transaction 종료 즉시 permit를 반환한다.

### Redemption

1. code를 strict canonical form으로 검증하고 storage verifier로 변환한다. raw code는 request
   boundary 밖에서 log하지 않는다.
2. replay lookup은 local DB permit 아래 실행하고 즉시 반환한다. replay miss이면 Redis
   rate/Bloom을 실행한 뒤 idempotency acquire와 business transaction 각각에 local permit를
   다시 획득한다.
3. business transaction에서 campaign, claim, idempotency 순서로 lock/recheck한다.
4. tenant, code verifier, claim state, campaign state, expiry, revoke, redemption reference를
   검증한다.
5. Bloom/risk positive는 terminal reject가 아니라 `REVIEW_REQUIRED` review를 생성한다.
6. 정상 경로는 `ALLOCATED -> REDEEMED`와 audit/event/idempotency finalize를 한 transaction에
   적용한다.

### Release, expiry, revoke

- `ALLOCATED -> RELEASED|EXPIRED|REVOKED`는 campaign과 claim을 lock하고
  `allocated_count`를 정확히 한 번 감소시킨다.
- allocation `REVIEW_REQUIRED`는 감소시키지 않는다. redemption `REVIEW_REQUIRED`의
  release/expiry/revoke는 counter를 한 번 감소시킨다.
- `REDEEMED` claim의 release/revoke는 conflict다. 회계 reversal은 범위 밖이다.
- leader worker는 bounded batch를 선택하지만 각 row의 expected state/revision CAS가 중복
  실행을 무효화한다.

## HTTP idempotency

`http_idempotency`는 tenant, principal, operation, campaign/resource, key digest로 scope를
분리한다. canonical fingerprint는 HTTP method, normalized path/resource id,
`Content-Type`, `X-Workshop-Tenant`, `X-Workshop-Principal` semantic header allowlist와 closed
request DTO의 canonical JSON을 포함한다. transport header와 request id는 제외한다. DTO는
schema default를 먼저 적용하고 object key를 UTF-8 byte order로 정렬하며, omitted optional
field와 explicit `null`은 schema가 둘을 같은 의미로 선언한 경우에만 동일하게 정규화한다.
number는 schema type의 canonical decimal 표현을 사용하고 unknown property는 fingerprint 생성
전에 거부한다. golden fixture가 header case/공백, omitted/null/default, key order, number 표현의
동치와 비동치를 고정한다.

- 동일 key와 동일 canonical request fingerprint: 완료 response replay
- 동일 key와 다른 fingerprint: `409 IDEMPOTENCY_FINGERPRINT_CONFLICT`
- active owner: `409 COMMAND_IN_PROGRESS`와 bounded `Retry-After`
- expired owner: 새 owner token이 takeover하고 stale finalize는 CAS 실패
- terminal record cleanup: 만료된 terminal row만 bounded batch로 삭제

owner row는 `owner_token_digest`, `lease_until`, `command_deadline`, `fingerprint`, terminal
status/header/body reconstruction metadata를 가진다. acquire는 짧은 transaction이고 business
transaction은 owner/lease를 재검증한 뒤 finalize한다. acquire 후 crash, lease expiry/takeover,
stale finalize, DB commit 후 response 전 crash를 fixture로 고정한다. terminal replay TTL은
voucher 최대 TTL보다 길며 generation key는 모든 terminal replay row가 정리되기 전에
retire할 수 없다. 필요한 key가 누락되면 새 effect를 만들지 않고
`503 IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE`로 fail closed한다.

response는 `Idempotency-Replayed: true|false`를 제공한다. replay 여부와 무관하게 raw key,
raw voucher code, user/device/IP signal은 log와 metric label에 넣지 않는다.

## Redis admission과 risk signal

rate limit key는 versioned HMAC digest namespace를 사용한다. dimension은
tenant/campaign/user와 bounded operation 종류다. IP/device 원문은 Redis key에도 사용하지
않는다.

Bloom filter는 이전에 관찰된 risk digest를 빠르게 표시하지만 false positive가 가능하다.

- negative: deterministic risk fixture와 PostgreSQL 검증을 계속한다.
- positive: review candidate를 만들고 PostgreSQL에 근거를 기록한다.
- unavailable/corrupt: `UNKNOWN` signal로 기록하고 local bulkhead 아래 PostgreSQL 검증을
  계속한다.

Bucket4j token은 성공적으로 소비되면 반환하지 않는다. 반환 대상은 node-local semaphore와
명시적 lease형 resource뿐이다. Redis timeout/unknown 결과는 token을 재발행하지 않고 local
DB permit 아래 PostgreSQL로 진행한다.

Redis admission은 `HEALTHY`, `DEGRADED`, `RECOVERING` 상태를 가진다. timeout/error threshold로
`DEGRADED`에 진입하고, bounded probe가 연속 성공한 hysteresis 뒤에만 `HEALTHY`로 복귀한다.
전환 중에도 local permit는 항상 적용되어 이중 admission 폭주를 막는다. 상태 전환과 지속
시간은 redacted log/metric으로 노출한다.

## Event publication과 reconciliation

application service는 Spring transaction 안에서 domain event를 publish한다.
`bluetape4k-exposed-spring-modulith` publication registry가 완료/실패/replay 상태를 보존한다.
listener는 aggregate revision/event id를 사용해 idempotent하게 적용한다.

reconciliation은 다음을 대상으로 bounded batch를 실행한다.

- 실패한 Modulith publication
- `campaign_event_inbox`의 delayed/duplicate/out-of-order event
- expiry candidate
- idempotency terminal cleanup

worker는 `FOR UPDATE SKIP LOCKED`, `(next_attempt_at, id)` deterministic ordering, batch 50,
run deadline 10초를 사용한다. claim/effect/inbox outcome은 한 transaction에 기록한다.
실패는 bounded exponential backoff와 최대 5회 attempt를 적용하고 poison item은
`FAILED`/terminal conflict로 남긴다. single-run guard로 scheduler/operator overlap을 막고,
operator가 호출하는 bounded run은 동기 `200`이며 category별 processed/skipped/failed 수와
last cursor를 반환한다. 10초 deadline에 도달해도 완료한 batch 결과와 `deadlineReached=true`를
반환하므로 추적 불가능한 비동기 operation resource를 만들지 않는다.

worker election 실패는 correctness 실패가 아니다. operator reconcile endpoint가 같은 service를
호출하며 PostgreSQL expected state/revision이 중복 적용을 막는다. shutdown/cancellation은 새
batch claim을 중지하고 진행 중 transaction의 bounded 종료를 기다린 뒤 lease를 반환한다.

## Schema migration과 배포 호환성

application table은 catalog의 `exposed-migration-jdbc`와 versioned SQL/DDL artifact를 사용한다.
`schema_history`와 각 migration checksum을 검증하고 startup 전에 한 instance가 migration
lock을 획득한다.

- clean DB migration과 existing DB upgrade를 모두 test한다.
- migration 실패나 checksum drift는 startup/readiness를 거부한다.
- DDL은 add nullable/default -> dual read/write -> backfill -> constraint/index -> old field 제거의
  expand/contract 순서를 따른다.
- rollback은 데이터 보존형 previous binary 호환 범위까지만 허용한다. destructive down
  migration은 제공하지 않는다.
- packaged artifact smoke는 clean start, warm restart, previous schema upgrade, failed migration,
  rollback-compatible previous binary를 검증한다.

## HTTP API

### Customer/API routes

- `POST /api/v1/campaigns/{campaignId}/claims`
- `POST /api/v1/claims/{claimId}/redeem`
- `POST /api/v1/claims/{claimId}/release`
- `POST /api/v1/claims/{claimId}/code-acknowledgements`
- `GET /api/v1/campaigns/{campaignId}`
- `GET /api/v1/claims/{claimId}`
- `GET /api/v1/campaigns/{campaignId}/events`

customer command는 `Idempotency-Key`, `X-Workshop-Tenant`, `X-Workshop-Principal`을
요구한다. tenant/principal header는 local demo identity일 뿐 production authentication이
아님을 명시한다. 1~64자 ASCII identifier만 허용하고 모든 repository API, PK/FK/unique/index,
query predicate는 tenant scope를 포함한다. tenant 불일치도 `404`로 응답한다.

| Route | Success | 핵심 request/response 계약 |
|---|---|---|
| claim allocation | `201` 또는 review `202` | request: userRef; server-owned deterministic/provider risk signal만 사용; response: claimId, state, revision, policyVersion, expiresAt, immediate allocation일 때만 one-time code, `Location` |
| redeem | `200` 또는 review `202` | request: code, redemptionReference; response: claim state/revision, authoritative outcome |
| release | `200` | expectedRevision; response: state/revision/capacity snapshot |
| code acknowledgement | `200` | review 승인 뒤 `Idempotency-Key`와 expectedRevision로 한 번 전달; response 손실 재시도는 같은 key로 동일 code replay |
| campaign GET | `200` | state/revision/policyVersion/capacity/remainingCapacity/observedAt |
| claim GET | `200` | side-effect 없는 owner-scoped claim snapshot; code 또는 acknowledgement state를 변경하지 않음 |
| campaign SSE | `200 text/event-stream` | same-origin header-capable fetch stream, tenant/principal scope와 cursor 검증 |

review 승인 뒤 code delivery는 별도 idempotent code acknowledgement command를 사용한다.
응답 code는 generation input에서 재계산하고 acknowledgement audit 뒤 새 key로는 다시 노출하지
않는다. DB commit 뒤 응답이 손실되면 같은 key replay가 terminal response metadata와 retained
generation key로 동일 code를 복구한다. original allocation replay는 review response를 그대로
반환하며 승인 뒤 code retrieval key와 scope는 별도다. expiry/revoke 뒤에는 code를 노출하지
않는다. browser preload/cache/retry가 실행하는 GET은 어떤 audit나 secret disclosure도 만들지 않는다.

### Operator routes

- `POST /operator/api/v1/campaigns/{campaignId}/activate`
- `POST /operator/api/v1/campaigns/{campaignId}/pause`
- `POST /operator/api/v1/campaigns/{campaignId}/end`
- `POST /operator/api/v1/campaigns/{campaignId}/policy`
- `POST /operator/api/v1/reviews/{reviewId}/approve`
- `POST /operator/api/v1/reviews/{reviewId}/reject`
- `POST /operator/api/v1/claims/{claimId}/revoke`
- `POST /operator/api/v1/reconciliation/run`
- `POST /operator/api/v1/campaigns`
- `GET /operator/api/v1/reviews?status=OPEN&cursor=...`
- `GET /operator/api/v1/reconciliation/backlog?cursor=...`
- `POST /operator/api/v1/fixtures/{scenario}/run`
- `POST /operator/api/v1/fixtures/reset`

operator route도 `X-Workshop-Tenant`를 요구하며 operator secret이 tenant 선택 권한을 암시하지
않는다. repository lookup과 response는 이 명시적 demo tenant에만 한정된다. route별 mutation
precondition은 다음과 같다.

| Operator mutation | Idempotency | Concurrency/precondition | Success |
|---|---|---|---|
| campaign create | required | `If-None-Match: *`; body에 revision 없음 | `201 + Location` |
| activate/pause/end/policy | required | campaign `expectedRevision` | `200` |
| review approve/reject | required | review와 claim `expectedRevision` | `200` |
| claim revoke | required | claim `expectedRevision` | `200` |
| reconciliation run | required | single-run guard; aggregate revision 없음 | bounded synchronous `200 + counts/cursor/deadlineReached` |
| fixture run/reset | required | loopback/demo guard와 scenario/reset token; aggregate revision 없음 | bounded synchronous `200 + affected resources` |

list route는 opaque cursor, 최대 100개 page, UTC timestamp를 사용한다. create/reconciliation/
fixture request에 존재하지 않는 aggregate revision을 합성하지 않는다.

기본 bind address는 `127.0.0.1`이다. operator route는 non-default high-entropy secret의
keyed digest를 constant-time 비교하고 explicit workshop guard header를 함께 요구한다.
strict `Host`/`Origin` allowlist, CORS deny-by-default, `application/json`, credential rate limit을
적용한다. cookie는 사용하지 않는다. operator secret은 browser session memory의 masked input에만
두고 URL/body/localStorage/history/DOM/log에 남기지 않는다. destructive action은 confirmation,
stale revision refresh, inactive action disable을 제공한다. public bind, multi-user IAM,
production CSRF/OAuth는 범위 밖임을 README에 명시한다.

fixture route는 loopback + test/demo profile + operator guard에서만 존재한다. customer DTO는
fixture control이나 risk 판정 override를 받지 않는다. fixture run이 demo-tenant server-owned
risk/provider state를 먼저 준비하고 이후 일반 customer command는 그 state만 관찰한다. 기본 seed는
fixed clock 기준 campaign 하나를 만들며 reset은 해당 demo tenant만 삭제한다. browser와 README는
happy path, retry/replay, capacity race, allocation review, redemption review, pause race, revoke race,
policy change, Redis outage/Bloom false positive, delayed event를 동일한 seed/input/expected
state/audit/SSE 결과로 실행하는 scenario cookbook을 제공한다.

### Error contract

- `400`: malformed/invalid input
- `404`: tenant-scoped aggregate not found
- `409`: state/idempotency/policy conflict
- `412`: stale expected revision
- `429`: Redis token-bucket rate quota rejection, `Retry-After` 포함
- `503`: local DB bulkhead 또는 authoritative backend unavailable, `Retry-After` 포함

error body는 `code`, `reason`, `requestId`, optional bounded `retryAfterSeconds`만 제공한다.
secret, voucher code, raw signal, SQL detail은 포함하지 않는다.

| Stable code | HTTP | Retry | 같은 key 재시도 | Caller action |
|---|---:|---|---|---|
| `RATE_LIMITED` | 429 | yes | yes | `Retry-After` 뒤 재시도 |
| `DATABASE_BULKHEAD_REJECTED` | 503 | yes | yes | 짧은 backoff |
| `AUTHORITATIVE_BACKEND_UNAVAILABLE` | 503 | yes | yes | health 확인 후 재시도 |
| `COMMAND_IN_PROGRESS` | 409 | yes | yes | owner lease 이내 대기 |
| `IDEMPOTENCY_FINGERPRINT_CONFLICT` | 409 | no | no | 새 key 또는 original payload |
| `CAMPAIGN_PAUSED` | 409 | conditional | yes | resume 이후 재시도 |
| `CAMPAIGN_ENDED`, `CLAIM_EXPIRED`, `CLAIM_REVOKED`, `ALREADY_REDEEMED` | 409 | no | no | terminal 상태 표시 |
| `CAPACITY_EXHAUSTED`, `PER_USER_LIMIT_REACHED` | 409 | no | no | 다른 campaign/action |
| `STALE_REVISION` | 412 | yes | no | 최신 snapshot 조회 후 새 key |
| `IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE` | 503 | conditional | yes | key 복구 전 같은 key만 재시도; 새 effect 금지 |
| `INVALID_EVENT_CURSOR` | 400 | no | n/a | cursor 제거 후 authoritative snapshot부터 재연결 |
| `SSE_CAPACITY_REJECTED` | 503 | yes | n/a | `Retry-After` 뒤 polling fallback 사용 |
| `UNSUPPORTED_REVERSAL` | 409 | no | no | #534 비범위 안내 |

unknown route/body property, oversized input, invalid ASCII/header/code/cursor는 bounded `400`이다.
request body, string, collection, nesting, SSE cursor에 명시적 상한을 적용한다. 외부 requestId는
내부 bounded error/event code와 연결하지만 SQL/Redis/leader exception은 sanitized category만
운영 log에 남긴다.

## Browser UI와 SSE

`/`는 dependency 없는 static HTML/vanilla JavaScript console이다.

- campaign state, remaining capacity, policy version
- claim allocation/redeem/release/revoke timeline
- review queue와 approve/reject action
- publication/inbox reconciliation backlog
- admission outcome과 authoritative PostgreSQL outcome의 구분
- Redis outage, Bloom false positive, delayed event deterministic fixture 실행

review UI는 allocation/redemption kind, redacted evidence, bounded reason, expected revision,
stale refresh, approve/reject 결과와 capacity delta를 보여 준다. 모든 timestamp는 UTC
ISO-8601 원본과 local display를 함께 제공하고 snapshot `revision`/`observedAt` 및 server-time
기준 expiry countdown을 표시한다. 상태별 허용 action과 다음 가능한 action을 명시한다.

SSE는 먼저 authoritative snapshot을 전송하고 audit cursor 이후 event를 전달한다.
`Last-Event-ID`, heartbeat, reconnect를 지원한다. campaign별 emitter는 하나의 poller를
공유한다. customer stream은 same-origin header-capable `fetch` streaming을 사용해 tenant와
principal을 query string에 넣지 않는다. operator stream은 별도 route/guard를 사용한다.

event type은 `snapshot`, `audit`, `heartbeat`, `reset`, `error`다. ID는 audit의 monotonic
`revision:id` cursor다. duplicate ID는 client가 무시한다. future cursor는
`400 INVALID_EVENT_CURSOR`, retention gap은 새 authoritative snapshot을 담은 `reset` event로
복구한다. restart 뒤에도 DB audit cursor에서 재개한다.

poller는 subscriber가 0이면 즉시 취소되고 campaign poller 최대 32, interval 500ms,
idle/backoff 최대 2초, query당 200 rows/256KiB, global background permit 4를 지킨다.
emitter별 queue는 32 event이며 overflow는 마지막 cursor를 담은 `reset` 뒤 연결을 종료한다.
heartbeat/write failure, timeout, disconnect, overflow, shutdown은 하나의 idempotent cleanup
경로에서 ref-count/permit/queue를 반환한다. blocked write는 interruption과 5초 deadline으로
취소한다. SSE cap 도달 시 `503 SSE_CAPACITY_REJECTED`와 polling fallback URL을 제공한다.

max connection, poll permit, slow consumer, timeout, disconnect, last-subscriber cancellation,
shutdown, future/stale/cross-tenant cursor를 검증한다. virtual-thread executor는 application
lifecycle이 소유하고 graceful shutdown에서 bounded deadline 안에 종료한다. UI는 모든
untrusted value를 `textContent`로만 렌더링하고 `innerHTML`을 사용하지 않는다.

UI의 campaign command, review decision, SSE 상태와 오류는 keyboard만으로 탐색·실행할 수 있어야
한다. modal/confirmation 종료 뒤 focus를 원래 trigger로 복원하고, 상태 변화와 reset/error는
`aria-live` region으로 전달한다. input/action에는 accessible name을 제공하며 성공/경고/실패는
색상만이 아니라 text/icon/state label을 함께 사용한다. live browser smoke test는 tab order,
focus restoration, accessible label, live-region message를 검증한다.

## Runtime configuration

| Property | Default | Validation/meaning |
|---|---:|---|
| `spring.threads.virtual.enabled` | `true` | Java 25 JDK provider만 허용 |
| `server.tomcat.threads.max` | `8000` | platform-thread fallback 기록 |
| `server.tomcat.max-connections` | `8000` | accept 상한 |
| `server.tomcat.accept-count` | `1000` | bounded accept queue |
| `spring.datasource.hikari.maximum-pool-size` | `16` | 고정 상한 |
| `spring.datasource.hikari.minimum-idle` | `4` | 1..16 |
| `spring.datasource.hikari.connection-timeout` | `60000` | ms |
| `spring.transaction.default-timeout` | `60s` | business transaction 상한 |
| `workshop.voucher.db.foreground-permits` | `12` | background와 합 <= 16 |
| `workshop.voucher.db.background-permits` | `4` | foreground와 합 <= 16 |
| `workshop.voucher.db.permit-timeout` | `250ms` | connection 전에 획득 |
| `workshop.voucher.db.lock-timeout` | `5s` | transaction-local lock fail-fast |
| `workshop.voucher.worker.batch-size` | `50` | 1..200 |
| `workshop.voucher.worker.run-deadline` | `10s` | transaction timeout보다 작음 |
| `workshop.voucher.sse.max-campaigns` | `32` | global poller cap |
| `workshop.voucher.sse.queue-size` | `32` | slow consumer cap |
| `workshop.voucher.redis.command-timeout` | `500ms` | timeout은 degraded signal |
| `workshop.voucher.keys.current-version` | required | active key ring에 정확히 하나 존재 |

production profile은 unknown property, invalid range, permit 합 초과, missing/weak/default key,
test profile/public bind 조합을 startup에서 거부한다. connection/keep-alive/SSE timeout은 60초
경계로 두되 lock과 permit은 더 짧게 fail-fast한다.

virtual thread 수에 맞춰 JDBC connection을 늘리지 않는다. 16개 connection은 PostgreSQL
session, memory, lock contention을 제한하는 bulkhead이며 timeout 증가는 처리량 확장이 아니라
정상 경합이 connection/transaction 경계에서 완료될 여유를 주는 설정이다.

## Health, lifecycle, rollback

- liveness는 JVM/process event loop가 살아 있는지만 판정한다.
- readiness는 migration과 PostgreSQL authority가 usable해야 `UP`이다.
- Redis, Bloom, leader 장애는 readiness를 내리지 않고 redacted `DEGRADED` component detail과
  metric을 제공한다.
- management interface는 loopback 별도 포트에서 health/metrics만 allowlist한다. `env`,
  `configprops`, `heapdump`, `threaddump`는 노출하지 않는다.

graceful shutdown 순서는 readiness 차단 -> 신규 command/SSE 거부 -> scheduler/leader trigger
중지 -> in-flight command/transaction drain -> SSE/poller cleanup -> Redis connection/leader lease
close -> virtual executor -> DataSource close다. 각 단계는 bounded deadline과 결과 log/metric을
가지며 전체 30초를 넘으면 redacted forced-shutdown reason을 남긴다.

운영 검증은 Redis `HEALTHY/DEGRADED/RECOVERING`, PostgreSQL pool exhaustion/readiness down,
cold/warm restart, publication/inbox replay, migration failure, key rotation/rollback을 포함한다.
audit 90일, terminal idempotency는 max voucher TTL + 7일, applied inbox/publication은 30일을
기본 retention으로 두고 참조 key와 audit보다 먼저 삭제하지 않는다. purge count/oldest age를
관측하며 backup/restore smoke는 audit cursor, inbox, publication, idempotency replay를 확인한다.

## Logging, metrics, security

모든 production operational class는 `bluetape4k-logging`의 `KLogging`과 lazy message를
사용한다. request boundary, idempotency decision, admission fallback, transition, review,
worker/reconciliation lifecycle을 structured key=value event로 남긴다.

log에서 허용되는 식별자는 request id, aggregate UUID, key/digest의 짧은 비가역 prefix,
bounded reason code뿐이다. metric label에는 aggregate UUID와 digest prefix도 금지한다. 다음
값은 log, error, metric label에 금지한다.

- raw voucher code와 HMAC key
- raw idempotency key
- raw tenant/user/device/IP signal
- operator key와 request body
- SQL parameter와 stack trace에 포함된 credential

Micrometer metric은 command, outcome, reason, backend 종류처럼 low-cardinality dimension만
사용한다. `voucher.command.duration`, `voucher.db.bulkhead.rejected`,
`voucher.redis.degraded`, `voucher.review.open`, `voucher.backlog.oldest.age`,
`voucher.worker.last.success`, `voucher.worker.attempts`, `voucher.sse.active`,
`voucher.sse.rejected`, Hikari active/pending과 leader state를 관측한다. README runbook은
degraded 5분, oldest backlog 10분, Hikari pending 지속, worker last success 2주기 초과를
workshop 경고 기준으로 설명한다.

static response에는 CSP, `X-Content-Type-Options`, `Referrer-Policy`, no-store를 적용한다.
Jackson default typing을 사용하지 않고 closed DTO만 역직렬화한다. SQL 값은 Exposed bind
parameter만 사용한다.

## Deterministic fixtures

다음 fixture는 clock, UUID supplier, HMAC test key, risk provider 결과를 주입해 sleep이나
외부 서비스에 의존하지 않는다.

- 같은 idempotency key의 retry storm
- idempotency acquire 직후 crash, DB commit 후 response 전 crash, lease expiry/takeover,
  stale finalize
- capacity 1에 대한 다중 concurrent allocation
- 같은 voucher의 concurrent redemption
- pause와 allocation, pause와 redemption 경합
- revoke와 redemption 경합
- device/IP/user signal disagreement
- Bloom false positive와 Redis unavailable/recovery
- Redis timeout/flapping, Bucket4j token non-return, local permit leak/cancellation
- allocation 뒤 policy version 변경
- delayed, duplicate, out-of-order event
- worker duplicate tick과 leader backend failure
- poison/starved worker item, run deadline, shutdown 중 transaction
- context restart 후 publication/inbox replay
- generation/verification key rotation, old-key replay, missing-key fail closed
- slow SSE consumer, retention gap reset, cross-tenant cursor, shutdown 중 blocked write

시간 경계는 fixed `Clock`을 사용하고 DST gap/overlap처럼 local time이 필요한 입력은 명시적
zone/offset 없이는 허용하지 않는다. 기본 API 시간은 UTC instant다.

## 검증 전략

1. domain policy unit test로 상태 전이와 semantic constraint를 RED/GREEN한다.
2. `PostgreSQLServer` 기반 repository test로 unique/CAS/lock/revision을 검증한다.
3. `MultithreadingTester`와 실제 PostgreSQL로 capacity/redeem/pause/revoke race를 검증한다.
4. `RedisServer` 기반 rate limit/Bloom/admission recovery test를 실행한다.
5. Spring context restart와 publication/inbox replay를 검증한다.
6. `RANDOM_PORT`와 `WebTestClient.bindToServer()` JDK connector로 실제 Tomcat,
   serialization, error header, operator guard, SSE lifecycle을 검증한다.
7. logging capture test로 민감정보 비노출을 검증한다.
8. module test, detekt, module registration, workflow/actionlint, README/diagram validation을
   순차 실행한다.

성능/stress profile은 campaign capacity 100, 동일 user limit 1, 500 allocation 요청과 500
redemption 요청을 concurrency 64/128에서 실행한다. Redis 정상/timeout과 capacity hotspot을
각각 측정해 p95/p99, throughput, expected 409/429/503 비율, Hikari active/pending,
PostgreSQL lock wait, Redis round trips, allocation bytes/op과 GC pause를 JSON/JFR artifact로
남긴다. 구조적 gate는 connection active <= 16, local permit 합 <= 16, lock wait <= 5초,
resource leak 0, correctness invariant 유지다. wall-clock latency는 CI hard gate로 사용하지
않고 동일 환경 두 번의 결과와 회귀를 README에 기록한다. 다중 campaign 32개/slow consumer
profile도 poll rows/bytes cap과 idle cancellation을 확인한다.

Testcontainers/real DB/Redis 검증은 다른 worktree나 review lane과 병렬 실행하지 않는다.
retry에서만 성공한 integration test는 timing/lifecycle 원인을 조사한 뒤에만 통과 증거로
사용한다.

## Acceptance criteria와 완료 조건

- retry storm에서 allocation과 redemption effect가 각각 최대 한 번이다.
- capacity N에서 terminal allocated/redeemed/reviewed 수가 PostgreSQL invariant를 넘지 않는다.
- pause/revoke race는 한 transaction ordering과 audit reason으로 설명 가능하다.
- Redis loss/Bloom false positive는 terminal rejection이나 중복 effect를 만들지 않는다.
- policy version 변경 전후 claim의 적용 규칙이 deterministic test로 고정된다.
- browser가 admission과 authority, review, reconciliation을 구분해 보여 준다.
- raw code/key/signal이 storage/log/error/metric label에 노출되지 않는다.
- Java 25 virtual-thread provider와 executor lifecycle이 검증된다.
- Hikari max 16, Tomcat max 8000, 60초 timeout 계약이 configuration test로 고정된다.
- clean/existing DB migration, failed migration startup, health degradation/recovery,
  graceful shutdown, key rotation/rollback, packaged artifact smoke가 통과한다.
- bilingual README, Architecture/Sequence Diagram, English KDoc, module/workflow registration이
  완성된다.
- six-lens spec/plan/code review가 각 단계에서 P0=0, P1=0으로 수렴한다.
- README는 prerequisites, startup/config, seed/reset, curl, browser walkthrough,
  idempotent retry, review/reconciliation, Redis/PostgreSQL outage, SSE reconnect, error catalog,
  unsupported behavior, troubleshooting과 scenario cookbook을 bilingual로 제공한다.

## 대안과 후속 작업

### 기각: Redis-authoritative allocation

Redis token/bloom/lock을 allocation 권위로 사용하면 eviction, failover, partition에서 durable
capacity와 redemption 결과를 설명하기 어렵다. PostgreSQL authority를 유지한다.

### 분리: pre-generated voucher pool

pool generation, replenishment, secure inventory, batch expiry는 별도 lifecycle이므로 #537에서
다룬다. #534는 allocation-time code에 집중한다.

### 분리: event sourcing

event store, projection rebuild, snapshot, temporal query는 예제의 핵심을 흐리므로 #538에서
다룬다. #534 코드에 speculative TODO나 event-sourcing abstraction을 남기지 않는다.

### 추후 검토: 공용 idempotency/concurrency module

#1055 또는 여러 reference application에서 동일 경계가 반복됐다는 배포 근거가 생긴 후에만
공용 모듈을 검토한다. 그 전에는 application-owned fixture가 계약 검증 근거다.

## 리스크와 완화

| 리스크 | 완화 |
|---|---|
| campaign/claim lock inversion | 모든 command에 campaign -> claim -> review 순서를 강제하고 race test를 둔다. |
| virtual-thread 폭주가 DB를 압도 | Hikari 16과 foreground/background bulkhead, 60초 timeout으로 제한한다. |
| Bloom false positive가 사용자 거절로 이어짐 | review candidate로만 사용하고 PostgreSQL 재검사를 강제한다. |
| Redis outage 시 DB thundering herd | node-local permit와 `503 Retry-After`로 fail-open 진입량을 제한한다. |
| policy 변경이 기존 claim 의미를 바꿈 | allocation policy snapshot과 current safety rule을 분리한다. |
| HMAC key rotation 후 replay 불가 | claim에 key version을 보존하고 active read key set을 지원한다. |
| SSE poller/resource leak | shared poller, connection cap, timeout/disconnect/shutdown test를 둔다. |
| public deployment로 오해 | loopback default, operator guard, README 비범위/배포 경고를 명시한다. |

## Six-lens 설계 리뷰 기록

2026-07-19 초안을 동일 artifact 기준으로 독립 검토했다.

| Lens | Initial counts | Final counts | Integrated resolution |
|---|---|---|---|
| Performance | P0=0, P1=4, P2=4 | P0=0, P1=0, P2=2, P3=0 | token/semaphore 분리, always-on DB permit, conditional counter/lock timeout, index/query plan, stress/JFR/SSE cap 추가 |
| Stability | P0=0, P1=8, P2=2 | P0=0, P1=0, P2=2, P3=0 | idempotency crash window, review capacity, key retention, worker poison/backoff, permit invariant, SSE cleanup/fixture 추가 |
| Security | P0=0, P1=5, P2=5 | P0=0, P1=0, P2=0, P3=0 | generation/verifier 분리, tenant scope, ownership-scoped replay, operator/stream trust boundary, input/XSS/key 안전성 추가 |
| Operator/Ops | P0=0, P1=8, P2=4 | P0=0, P1=0, P2=2, P3=0 | health/management allowlist, shutdown order, migration/rollback, key rotation, degradation/runbook/retention 추가 |
| Developer/API | P0=0, P1=8, P2=6 | P0=0, P1=0, P2=0, P3=0 | side-effect 없는 GET, idempotent code acknowledgement, route별 precondition, tenant/error/fingerprint 계약 추가 |
| User/caller | P0=0, P1=9, P2=3 | P0=0, P1=0, P2=0, P3=0 | server-owned fixture, synchronous bounded run, scenario cookbook, SSE protocol, 접근성/operator UX, README DoD 추가 |

main-session integration은 중복 finding을 12개 범주로 정규화했고 모든 P0/P1 수정안을 이
명세에 반영했다. 최종 gate는 모든 lens에서 P0=0, P1=0으로 수렴했다. 남은 P2 여섯 건은
후속 이슈로 미루지 않고 #534 구현·검증 계획의 비차단 세부 evidence로 유지한다.

- Performance: PostgreSQL/Redis round-trip 관측과 동일 환경 2회 성능 비교를 남기되, workshop
  환경의 wall-clock 수치를 CI hard threshold로 오해하지 않는다.
- Stability: idempotency lease/clock 조건과 full SSE queue의 reset 우선 전달을 fixture로 고정한다.
- Operator/Ops: Redis recovery/worker scheduling 값을 configuration test와 runbook에 pin하고,
  shutdown에서 leader lease를 Redis client보다 먼저 반환하는 lifecycle test를 둔다.

Security, Developer/API, User/caller는 최종 open P2/P3도 없다. 위 P2는 모두 현재 issue의 plan과
acceptance evidence에 포함되며 별도 deferred 항목은 없다.

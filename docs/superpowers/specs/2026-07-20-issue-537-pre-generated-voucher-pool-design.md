# Issue #537 Pre-generated Voucher Pool 설계

## 목표

Java 25와 Spring Boot MVC 위에서 미리 생성하거나 가져온 voucher code pool을 운영하는
browser-backed reference application을 만든다. PostgreSQL은 batch, pool entry, reservation,
allocation, redemption, revocation, idempotency와 audit의 유일한 권위다. Redis, Bucket4j,
Bloom filter와 leader election은 admission 또는 실행 중복 감소를 보조할 뿐 terminal 결과를
결정하지 않는다.

이 예제는 #534의 allocation 시점 opaque code 생성 모델을 대체하지 않는다. 별도 모듈에서
다음 고급 운영 계약을 증명한다.

- 같은 pool entry를 서로 다른 사용자가 동시에 예약하거나 할당할 수 없다.
- 같은 멱등 allocation command가 pool entry를 두 개 이상 소비하지 않는다.
- batch import/generation은 bounded chunk 단위로 재실행하고 실패 지점부터 재개할 수 있다.
- reservation, allocation, one-time reveal, redemption, release, expiry, revocation을 구분한다.
- 한 번이라도 공개된 code는 어떤 terminal 경로에서도 다시 pool로 돌아가지 않는다.
- Redis 장애와 Bloom false positive는 PostgreSQL terminal 판정을 바꾸지 않는다.
- operator는 원문 code나 사용자 식별자를 보지 않고 pool과 reconciliation 상태를 진단한다.

## 범위와 비범위

### 포함

- 별도 Gradle 모듈 `commerce/pre-generated-voucher-pool`
- Gradle project `:commerce-pre-generated-voucher-pool`
- package `io.bluetape4k.workshop.commerce.voucherpool`
- Java 25, Spring Boot MVC/Tomcat, virtual-thread request execution
- PostgreSQL + HikariCP + Exposed JDBC repository
- deterministic batch generation과 bounded JSON batch import
- batch provenance, policy version, activation window, tenant/campaign ownership
- pool entry `AVAILABLE`, `RESERVED`, `ALLOCATED`, `REDEEMED`, `RELEASED`, `EXPIRED`,
  `REVOKED` lifecycle
- explicit reservation과 allocation, 별도 one-time code reveal command
- application-owned HTTP idempotency와 PostgreSQL contention fixture
- keyed digest lookup, pre-reveal envelope encryption, reveal 후 ciphertext 삭제
- Redis/Lettuce/Bucket4j admission과 Bloom risk hint
- deterministic fraud/identity/WAF/notification fixture
- leader-triggered reservation expiry, revocation, reconciliation worker
- operator dashboard, customer flow, snapshot-first SSE와 polling fallback
- redacted logging, low-cardinality metrics, readiness/liveness, failure runbook

### 제외

- #534 모듈의 schema, API 또는 lifecycle 변경
- 재사용 가능한 coupon/voucher-pool library 또는 generic repository 추출
- projects #1055의 미공개 generic HTTP idempotency adapter 의존
- exposed #391의 test-local fixture를 production dependency로 사용
- event-sourced aggregate와 projection rebuild: #538
- 실제 fraud, identity, WAF/CAPTCHA, notification provider
- public internet 배포용 완전한 authentication/authorization
- broker를 전제로 한 범용 outbox framework
- redeemed voucher의 회계 reversal 또는 금전 보상
- 무제한 CSV upload, streaming multipart parser, 외부 object storage

## 현재 근거와 호환성 기준

- `origin/develop@fd7395f9`에는 #534 기준 모듈과 green baseline test가 있다.
- #534는 PostgreSQL authority, application-owned idempotency, advisory Redis, live
  `WebTestClient`, Java 25 virtual thread, operator guard와 reconciliation 계약을 이미 증명한다.
- workshop은 `bluetape4k-dependencies:1.3.1`만 version authority로 사용하며 현재 runtime의
  Bluetape library는 `1.11.0`으로 해석된다.
- projects #1055는 contract-and-fixture-first 연구이며 generic production adapter를 제공하지 않는다.
- exposed #391은 PostgreSQL persistence semantics의 test evidence이고 public repository API가 아니다.
- 따라서 이 모듈은 1.12.0 미배포 capability에 의존하지 않고 #534와 같은 application-owned
  black-box contract를 실행한다.

## 검토한 접근과 결정

### 모듈 경계

#### 채택: 별도 application module

`commerce/pre-generated-voucher-pool`을 독립 Spring Boot application으로 추가한다. #534와
HTTP error vocabulary, operator guard, redaction, test fixture의 의미를 맞추지만 production source를
직접 참조하거나 공유하지 않는다.

장점은 #534의 단순한 allocation-time generation 학습 경계를 보존하고, pool-specific schema와
실패를 독립적으로 설명할 수 있다는 점이다. 단점은 application-owned idempotency와 web guard에
일부 의도적인 반복이 생긴다는 점이다. 이 반복은 두 번째 application evidence이며, 안정된 공통
경계가 증명되기 전에 library를 추출하지 않는다는 issue 제약을 따른다.

#### 거절: #534 모듈 직접 확장

하나의 UI와 application을 재사용할 수 있지만 schema, lifecycle, code delivery 의미가 섞인다.
기준 예제의 opaque allocation과 advanced pool 운영을 동시에 이해해야 하므로 거절한다.

#### 거절: 공통 voucher/idempotency library 선추출

코드 중복은 줄지만 #537의 명시적 non-goal과 projects #1055의 contract-first 경계를 위반한다.
공통 library는 최소 두 application의 최종 source와 review evidence가 수렴한 뒤 별도 issue에서 판단한다.

### 할당 경합

#### 채택: explicit reservation + `FOR UPDATE SKIP LOCKED`

reservation command는 eligible batch를 공유 잠금으로 확인하고, deterministic ordering으로
`AVAILABLE` entry 하나를 `FOR UPDATE SKIP LOCKED`로 선택해 `RESERVED`로 바꾼다. tenant,
campaign과 user digest별 limit row를 잠가 같은 사용자의 동시 요청을 직렬화한다. 서로 다른
사용자는 같은 batch 안에서도 서로 다른 entry를 병렬로 예약할 수 있다.

allocation command는 reservation owner, expiry, batch state와 policy snapshot을 다시 확인한 뒤
`RESERVED -> ALLOCATED`로 전이한다. allocation 자체는 code를 반환하지 않는다. 별도 reveal
command만 code를 한 번 반환한다.

#### 거절: campaign/batch row exclusive lock

구현은 단순하지만 모든 allocator가 한 row에서 직렬화되어 pool의 핵심 contention 학습을 가린다.

#### 거절: optimistic CAS retry loop만 사용

정확성은 만들 수 있지만 고경합에서 loser가 반복적으로 같은 후보를 읽어 DB round trip과 lock
churn을 늘린다. `SKIP LOCKED`가 이 workload를 더 직접적으로 표현한다.

### Batch ingest

#### 채택: staging batch + bounded chunk checkpoint

import/generation은 batch를 `STAGING`으로 만들고 최대 500 entry의 chunk를 처리한다. 각 entry는
`(tenant_id, batch_id, source_ordinal)`과 keyed code digest가 unique하다. chunk transaction은
성공한 마지막 ordinal, accepted/rejected count, bounded failure code를 batch checkpoint에 기록한다.
같은 import idempotency key와 payload digest를 재실행하면 완료 chunk를 건너뛰고 첫 미완료
ordinal부터 재개한다.

entry validation failure는 원문 code 대신 source ordinal, bounded reason, payload digest만 남긴다.
부분 성공 batch는 allocation 대상이 아니다. 모든 chunk가 성공하고 count/digest reconciliation이
통과해야 `STAGING -> ACTIVE`가 된다.

#### 거절: 전체 batch 단일 transaction

원자성은 단순하지만 batch 크기에 비례해 transaction, lock, memory와 재시도 비용이 커지고 부분
실패 진단이 약하다.

#### 거절: 외부 broker/object storage pipeline

대규모 ingest에는 적합하지만 provider와 credential이 필요하고 이 workshop의 bounded reference
scope를 벗어난다.

### Code 보관과 공개

#### 채택: 공개 전 암호화, 공개 후 digest-only

- canonical ASCII code에 domain-separated `HMAC-SHA-256` lookup digest를 계산한다.
- import code는 JDK `AES/GCM/NoPadding`으로 암호화하고 batch/key version, nonce, ciphertext만 저장한다.
- generated code는 deterministic fixture 입력으로 만들되 entry마다 암호화해 import와 같은 storage
  lifecycle을 사용한다.
- raw code, generation seed, plaintext payload는 DB, idempotency response, audit, log와 metric label에
  저장하지 않는다.
- reveal transaction은 allocation ownership과 unrevealed 상태를 lock하고 decrypt한 뒤
  `revealed_at`을 기록하며 ciphertext, nonce와 encryption key reference를 같은 transaction에서
  제거한다. lookup digest와 verification key version만 남긴다.
- raw code는 transaction 밖에서 response object에만 존재하며 operational log/toString에서 제외한다.

현재 catalog와 #534 source에서 reusable Bluetape envelope-encryption helper를 찾지 못했다. 새
dependency를 추가하지 않고 JDK cryptography를 사용한다. key validation, nonce uniqueness,
authenticated tag failure, version rotation과 plaintext zero-retention을 직접 테스트한다.

#### 거절: plaintext code 저장

operator query, backup, log 또는 accidental serialization을 통해 전체 pool이 유출될 수 있다.

#### 거절: reveal 뒤에도 ciphertext 유지

response-loss recovery는 쉬워지지만 “one-time reveal 뒤 keyed digest만 저장” 계약을 위반한다.

## Architecture

PostgreSQL 중심 modular monolith를 사용한다.

| 경계 | 책임 | 장애 시 동작 |
|---|---|---|
| PostgreSQL | batch, entry, reservation, allocation, redemption, idempotency, audit, inbox | terminal outcome의 유일한 권위다. |
| Redis/Bucket4j | tenant/campaign/user admission | 장애 시 bounded node-local permit으로 축소 운전한다. |
| Redis Bloom | keyed code/user signal의 이전 관찰 hint | positive는 deterministic review hint이며 terminal reject가 아니다. |
| Node-local permit | Redis fail-open 시 DB 보호 | foreground, worker, SSE lane을 분리하고 포화 시 `503`을 반환한다. |
| Leader trigger | expiry/revocation/reconciliation 중복 감소 | leader가 없어도 operator command로 같은 bounded path를 실행한다. |
| Deterministic fake | fraud/identity/WAF/notification 결과 | 실제 provider 없이 failure와 replay 순서를 재현한다. |
| Static browser | customer/operator workflow와 SSE | SSE 실패 시 bounded polling으로 전환한다. |

Spring component는 `web`, `application`, `persistence`, `security`, `admission`, `reconciliation`,
`fixture`, `config`, `query` package로 분리한다. Controller는 request validation과 HTTP mapping만,
application service는 transaction use case와 state transition만, repository는 Exposed/JDBC와 raw
locking SQL만 소유한다.

## Domain model

### Voucher batch

`voucher_batches`는 다음 필드를 가진다.

- `tenant_id`, public UUID `batch_id`, `campaign_id`
- `state`: `STAGING`, `ACTIVE`, `PAUSED`, `FAILED`, `REVOKING`, `REVOKED`
- `source_kind`: `IMPORTED`, `GENERATED`
- `provenance_digest`, `request_fingerprint`
- `policy_version`, `activates_at`, `expires_at`
- `next_source_ordinal`, `expected_count`, `accepted_count`, `rejected_count`
- `last_failure_code`, `revision`, auditable timestamps

상태 전이는 다음과 같다.

- `STAGING -> ACTIVE`: 모든 chunk와 digest/count reconciliation이 통과한 경우만 가능하다.
- `STAGING -> FAILED`: schema, duplicate, cryptographic 또는 chunk failure가 기록된 상태다.
- `FAILED -> STAGING`: 같은 fingerprint의 resume command만 가능하다.
- `ACTIVE -> PAUSED -> ACTIVE`: activation window 안에서만 가능하다.
- `ACTIVE|PAUSED|FAILED -> REVOKING -> REVOKED`: bounded worker가 entry를 chunk로 종결한다.
- `REVOKED`는 terminal이며 새 entry나 reservation을 허용하지 않는다.

### Pool entry

`voucher_pool_entries`는 다음 필드를 가진다.

- `tenant_id`, `campaign_id`, `batch_id`, public UUID `entry_id`
- `source_ordinal`, `state`
- `code_digest`, `verification_key_version`
- pre-reveal `ciphertext`, `nonce`, `encryption_key_version`
- `reservation_id`, `allocation_id`, `user_digest`
- `reserved_at`, `reservation_expires_at`, `allocated_at`, `revealed_at`, `redeemed_at`
- `allocation_policy_version`, `terminal_reason`, `revision`

전이와 재사용 규칙은 다음과 같다.

| From | 명령 | To | 재사용 |
|---|---|---|---|
| `AVAILABLE` | reserve | `RESERVED` | reservation이 만료되기 전에는 불가 |
| `RESERVED` | reservation expiry, never allocated/revealed | `AVAILABLE` | 가능, expiry audit는 별도 기록 |
| `RESERVED` | allocate | `ALLOCATED` | 이후 영구 불가 |
| `ALLOCATED` | reveal | `ALLOCATED` + `revealed_at` | ciphertext 삭제, 영구 불가 |
| `ALLOCATED` | redeem | `REDEEMED` | terminal |
| `ALLOCATED` | release | `RELEASED` | terminal |
| `ALLOCATED` | TTL expiry | `EXPIRED` | terminal |
| `RESERVED|ALLOCATED` | batch/operator revoke | `REVOKED` | terminal |
| `REDEEMED` | revoke race loser | `REDEEMED` | redemption outcome 유지, audit만 추가 |

`ALLOCATED`에 도달한 entry는 reveal 여부와 관계없이 다시 `AVAILABLE`이 되지 않는다. 이 규칙은
응답 유실이나 JVM crash로 실제 공개 여부를 확신할 수 없는 경우에도 code 재사용을 막는다.

### Reservation과 user limit

`voucher_pool_reservations`는 `reservation_id`, tenant/campaign/user digest, entry id,
idempotency owner, `ACTIVE|ALLOCATED|EXPIRED|RELEASED|REVOKED`, expiry, policy version과
revision을 보존한다. entry가 `AVAILABLE`로 돌아가더라도 이전 reservation history는 남는다.

`voucher_pool_user_limits`는 `(tenant_id, campaign_id, user_digest)` unique row로 active
reservation/allocation count와 revision을 가진다. 같은 user의 command만 이 row에서 직렬화된다.
reconciliation은 entry/reservation count에서 projection drift를 검출하고 자동 수정 전후 값을 audit한다.

### Idempotency

`voucher_pool_http_idempotency`는 #534와 같은 scope, fingerprint, owner lease, terminal
descriptor, replay/conflict/takeover/cleanup 계약을 사용한다.

- reserve, allocate, redeem, release, operator batch command는 same-key/same-payload terminal
  response를 replay한다.
- same-key/different-payload는 `409 IDEMPOTENCY_FINGERPRINT_CONFLICT`다.
- active owner는 `409 COMMAND_IN_PROGRESS`와 bounded `Retry-After`를 반환한다.
- stale owner takeover와 retryable failure release는 PostgreSQL CAS로 결정한다.
- idempotency key와 raw request payload는 저장하지 않고 scoped digest/fingerprint만 저장한다.

one-time reveal은 의도적으로 일반 response replay contract에서 제외한다. 첫 성공은 code를 반환하고
같은 키 재요청은 side effect 없이 `200`, `Idempotency-Replayed: true`,
`codeAvailable=false`, `outcome=ALREADY_REVEALED`를 반환한다. 응답을 잃은 client는 이전 code를
복구할 수 없다. Operator가 allocation을 revoke한 뒤 새 entry를 예약해야 하며 이전 code는 절대
재사용하지 않는다. README와 UI가 이 복구 절차를 명시한다.

### Audit와 reconciliation inbox

`voucher_pool_audits`는 append-only이고 aggregate revision, policy version, actor type,
bounded reason, request/correlation digest만 저장한다. `voucher_pool_reconciliation_inbox`는 delayed
fixture와 worker command의 event id, payload digest, status, attempt, next attempt, claim lease와
terminal outcome을 저장한다. raw code, tenant, user/device/IP, idempotency key는 저장하지 않는다.

## Transaction과 race 계약

### Reservation

한 transaction에서 다음 순서를 지킨다.

1. idempotency owner를 acquire한다.
2. tenant/campaign/user limit row를 lock한다.
3. ACTIVE batch와 activation window를 확인한다.
4. `AVAILABLE` entry 하나를 deterministic ordering + `FOR UPDATE SKIP LOCKED`로 선택한다.
5. entry와 reservation을 기록하고 user active count를 증가시킨다.
6. audit와 safe idempotency descriptor를 finalize한다.

entry가 없으면 pool exhaustion을 terminal `409 POOL_EXHAUSTED`로 저장한다. lock/permit timeout은
retryable `503`이고 terminal descriptor로 고정하지 않는다.

### Allocation과 reveal

allocation은 reservation row와 entry를 lock하고 owner, expiry, batch state, policy version을 다시
확인한다. batch pause/revoke가 먼저 commit하면 allocation은 거절된다. allocation이 먼저 commit하면
후속 revoke가 `ALLOCATED -> REVOKED`를 기록한다.

reveal은 entry를 lock하고 `ALLOCATED`, correct owner, `revealed_at IS NULL`, ciphertext 존재를
검증한다. decrypt/tag 검증과 ciphertext 제거를 같은 transaction에서 수행한다. commit 뒤 response
serialization이 실패해도 entry는 exposed로 취급한다.

### Redemption과 revocation

redemption은 canonical code의 keyed digest를 계산해 `(tenant_id, allocation_id, code_digest)`로
entry를 찾고 constant-time 비교 후 lock한다. entry, batch/campaign safety state, expiry와 policy
snapshot을 검증하고 `ALLOCATED -> REDEEMED`를 단 한 번 기록한다.

batch revocation worker는 batch를 `REVOKING`으로 바꾸고 entry id 순서로 최대 100개씩 lock한다.
`AVAILABLE|RESERVED|ALLOCATED`는 `REVOKED`, `REDEEMED`는 그대로 유지한다. redemption과 revoke가
경합하면 먼저 얻은 entry lock의 결과가 audit revision으로 설명 가능한 terminal outcome이 된다.

## HTTP와 browser 계약

### Customer API

- `POST /api/v1/campaigns/{campaignId}/reservations`
- `GET /api/v1/reservations/{reservationId}`
- `POST /api/v1/reservations/{reservationId}/allocate`
- `POST /api/v1/allocations/{allocationId}/code-reveals`
- `GET /api/v1/allocations/{allocationId}` — raw code를 반환하지 않는다.
- `POST /api/v1/allocations/{allocationId}/redeem`
- `POST /api/v1/allocations/{allocationId}/release`

모든 mutation은 tenant/principal header, bounded `Idempotency-Key`, JSON size/content validation을
요구한다. `userRef`가 body에 있다면 authenticated workshop principal과 같아야 한다.

### Operator API

- `POST /operator/api/v1/batches/import`
- `POST /operator/api/v1/batches/generate`
- `POST /operator/api/v1/batches/{batchId}/resume`
- `POST /operator/api/v1/batches/{batchId}/activate|pause|revoke`
- `GET /operator/api/v1/batches/{batchId}`
- `GET /operator/api/v1/pool-depth`
- `GET /operator/api/v1/reservations/stuck`
- `POST /operator/api/v1/reconciliation/run`
- test profile의 guarded deterministic failure/fixture routes

Operator mutation은 #534와 같은 same-origin guard, explicit operator secret, tenant scope,
idempotency key와 expected revision을 요구한다. Cookie에 secret을 저장하지 않는다.

### UI

한 static browser application에서 customer와 operator view를 분리한다.

- customer flow: admission → reservation → allocation → one-time reveal → redemption
- operator batch view: provenance, chunk progress, active window, accepted/rejected count
- pool depth: available/reserved/allocated/redeemed/released/expired/revoked count
- timeline: state, policy version, bounded reason, revision, timestamp
- stuck reservation과 worker lease
- batch revocation progress와 reconciliation before/after count
- Redis healthy/degraded와 PostgreSQL authoritative outcome을 구분하는 banner

list와 SSE payload는 public UUID, state, bounded reason과 coarse count만 포함한다. raw code,
user/device/IP, idempotency key, secret, request body와 digest 원문은 표시하지 않는다.

## Configuration과 lifecycle

- Java/Kotlin toolchain과 target은 25다.
- Spring MVC virtual thread를 사용하되 Hikari max 16을 기본으로 유지한다.
- foreground/worker/SSE permit은 #534의 12/1/3 분리를 기준으로 시작하고 stress evidence로 확인한다.
- common HTTP, management readiness, JDBC connection/transaction timeout은 60초다.
- batch chunk max 500, revoke chunk max 100, reconciliation max 50을 기본값으로 둔다.
- reservation TTL, worker deadline, key ring, operator guard, Redis URI와 enable flag는 immutable
  configuration properties로 노출하고 startup validation을 둔다.
- non-test profile은 짧거나 알려진 test key, missing operator secret, invalid timeout/pool/chunk
  조합에서 fail-fast한다.
- shutdown은 새 admission을 막고 worker claim을 중단하며 독립 resource를 모두 닫는다.
- readiness는 PostgreSQL migration/key material을 필수로, Redis와 leader를 degraded advisory로 표시한다.

## Ecosystem capability selection

| 책임 | 재사용 capability | 선택 근거 / 사용하지 않는 경계 |
|---|---|---|
| Version authority | `bluetape4k-dependencies` platform | 개별 Bluetape BOM이나 module version을 고정하지 않는다. |
| JDBC repository | `bluetape4k-exposed-jdbc` | table/record mapping과 transaction pattern을 재사용한다. |
| Repository tests | `bluetape4k-exposed-jdbc-tests` | PostgreSQL-specific contention evidence를 실제 DB에서 실행한다. |
| Containers | `PostgreSQLServer`, `RedisServer` | raw `GenericContainer`를 만들지 않는다. |
| Virtual threads | `bluetape4k-virtualthread-api`, `-jdk25` | lifecycle과 executor ownership을 명시한다. |
| Redis | `bluetape4k-lettuce` | Redis가 필요한 경우 Lettuce만 사용한다. |
| Rate limit | `bluetape4k-bucket4j` + Bucket4j Lettuce | admission 전용이며 terminal authority가 아니다. |
| Leader trigger | `bluetape4k-leader-core`, Redis Lettuce adapter | 중복 trigger만 줄이고 correctness는 PostgreSQL CAS가 유지한다. |
| Logging/metrics | `bluetape4k-logging`, `bluetape4k-micrometer` | redaction과 low-cardinality label을 검증한다. |
| IDs | `bluetape4k-idgenerators` | public UUID v7과 stable fixture ID를 사용한다. |
| HTTP idempotency | application-owned table/fixture | #1055에 generic production adapter가 없다. |
| Envelope encryption | JDK AES-GCM | 현재 catalog/source에 맞는 published Bluetape helper가 없어 raw fallback을 테스트로 제한한다. |
| External providers | deterministic fakes | credential과 deployment evidence가 없다. |

## Failure modes와 복구

1. **동시 allocator가 같은 entry를 선택한다.** Unique reservation/allocation key와 row lock가 두 번째
   writer를 막는다. contention test는 distinct winner entry와 정확한 pool count를 검증한다.
2. **reservation response 유실 후 retry한다.** 같은 key/fingerprint는 같은 reservation descriptor를
   replay하고 새 entry를 소비하지 않는다.
3. **reveal response가 commit 뒤 유실된다.** code는 복구하지 않는다. replay는
   `ALREADY_REVEALED`를 반환하고 operator revoke + 새 reservation runbook을 따른다.
4. **batch chunk 중간에 process가 종료된다.** 마지막 committed ordinal부터 같은 fingerprint로
   resume한다. duplicate digest/source ordinal은 deterministic diagnostic으로 남는다.
5. **Redis가 timeout 또는 unavailable이다.** node-local permit으로 DB 부하를 제한하고 동일한
   PostgreSQL transaction을 실행한다. Redis failure가 terminal reject를 만들지 않는다.
6. **Bloom false positive가 발생한다.** review/admission hint만 바뀌며 PostgreSQL eligibility와 entry
   ownership은 그대로 검증한다.
7. **batch revoke와 redemption이 경합한다.** entry row lock commit 순서가 단일 결과를 정하고 audit가
   policy version과 loser outcome을 보존한다.
8. **reservation expiry worker가 중복 실행된다.** claim lease와 revision CAS가 한 worker만 전이하게
   한다. leader 부재 시 operator가 같은 bounded path를 실행한다.
9. **ciphertext/tag/key version이 손상 또는 누락된다.** allocation/reveal을 fail closed하고 readiness와
   operator diagnostic을 degraded로 표시한다. raw payload를 오류에 포함하지 않는다.
10. **projection count가 drift한다.** source entry/reservation rows에서 recompute하고 reconciliation이
    before/after count와 reason을 audit한다. 자동 수정은 tenant/batch scope와 expected revision을 요구한다.
11. **SSE client가 느리거나 cursor가 retention 밖이다.** bounded queue를 넘으면 reset event를 보내고
    snapshot reload 또는 polling으로 전환한다.
12. **Testcontainers shared schema가 충돌한다.** application integration test는 Base58 전용 schema를
    사용하고 container-backed Gradle command를 순차 실행한다.

## Test strategy

TDD의 RED → GREEN → REFACTOR를 behavior별로 기록한다.

### Unit/contract

- batch request validation, canonical code, digest와 AES-GCM round trip/tag failure
- state transition과 allocation/reveal/redeem/revoke 정책
- idempotency fingerprint, replay/conflict/in-progress/takeover
- redaction, bounded errors, low-cardinality metric tags
- deterministic fake와 operation-specific one-shot signal

### PostgreSQL integration

- concurrent reserve에서 entry당 winner 1명과 user-limit invariant
- retry storm에서 reservation/allocation 소비량 1
- `SKIP LOCKED` progress와 pool exhaustion
- reservation expiry/reuse와 allocated/revealed entry non-reuse
- partial import crash/resume, duplicate ordinal/digest, activation gate
- redemption/revoke, pause/allocate, expiry/reveal race
- worker duplicate claim, restart, reconciliation drift repair
- migration clean/existing DB, failed checksum, backup/restore

PostgreSQL concurrency evidence는 H2로 대체하지 않는다. 적합한 경우 `MultithreadingTester`와 실제
virtual-thread executor를 사용하고, raw harness가 필요하면 ecosystem tester가 transaction barrier와
HTTP concurrency를 표현하지 못하는 이유를 plan에 기록한다.

### Live HTTP/Spring

- JDK connector를 사용하는 live `WebTestClient`
- reserve → allocate → reveal → redeem과 safe GET redaction
- same/same replay, same/different conflict, response-loss recovery vocabulary
- operator origin/secret/tenant/expected revision guard
- request size, Unicode/control character, unknown key version, malformed code
- Redis unavailable boot/degradation과 PostgreSQL continuity
- readiness/liveness, management 60초 timeout, graceful shutdown
- SSE snapshot/reset/reconnect와 bounded slow consumer

### Stress/performance

- concurrency 64/128에서 Redis healthy/unavailable profile을 각각 두 번 실행한다.
- latency는 report-only로 기록하고 correctness, pool count, winner uniqueness, permit 상한,
  connection leak 0과 bounded deadline을 hard evidence로 사용한다.
- allocation query와 operator pool-depth query는 representative cardinality에서 bounded index scan을
  확인한다.

## Documentation, diagram과 repository 등록

- module `README.md`, `README.ko.md`를 동등하게 작성한다.
- architecture와 contention/reconciliation sequence를 SVG source + rendered PNG로 제공한다.
- root/commerce README locale set과 repo `AGENTS.md` module map을 갱신한다.
- `settings.gradle.kts` 자동 등록 결과를 `./gradlew projects`로 확인한다.
- `.github/workflows/Examples.yml`의 sequential container lane, artifact paths와 주석을 갱신한다.
- nightly full group, `scripts/smoke-validate.sh` data-access/full 또는 commerce validation,
  stale-check와 diagram QA를 갱신한다.
- Kover XML/Codecov visibility는 유지하되 hard threshold를 추가하지 않는다.
- module은 consumer example이며 publication/BOM artifact를 만들지 않는다.

## Acceptance criteria

- 서로 다른 concurrent allocator가 같은 entry를 예약하거나 할당하지 않는다.
- 같은 idempotent allocation command는 하나의 reservation/entry만 소비한다.
- redemption/revocation race가 policy version을 포함한 하나의 PostgreSQL outcome으로 수렴한다.
- 만료 reservation은 capacity를 반환하고 allocated 또는 exposed code는 다시 사용하지 않는다.
- batch import/generation은 replay-safe하고 partial failure를 진단·재개한다.
- raw code, device/IP/user, idempotency key와 secret이 log, metric label, audit, operator list에 없다.
- browser가 admission, reservation, allocation, reveal, redemption, reconciliation을 구분한다.
- Redis/Bloom/leader 장애가 terminal PostgreSQL 결과를 변경하지 않는다.
- Java 25 virtual thread, Hikari/permit/deadline과 resource lifecycle이 검증된다.
- bilingual README, diagrams, KDoc, module/workflow/nightly/test registration이 source와 일치한다.
- six-lens spec/plan/code review가 P0=0, P1=0으로 수렴한다.

## Definition of Done

- approved spec와 implementation plan이 committed 상태다.
- 모든 새 production behavior가 관찰된 RED와 GREEN evidence를 가진다.
- targeted, full module, migration, stress, detekt, Kover XML, `git diff --check`가 fresh PASS다.
- repository hazard와 Kotlin checklist의 모든 triggered row가 PASS 또는 근거 있는 N/A다.
- final six-lens review와 PR review가 P0=0, P1=0이다.
- lesson과 review evidence가 tracked/committed 상태다.
- exact feature head가 remote/PR head와 일치하고 required CI가 성공한다.
- PR은 merge-ready로 보고하되 fresh merge 승인을 기다린다.

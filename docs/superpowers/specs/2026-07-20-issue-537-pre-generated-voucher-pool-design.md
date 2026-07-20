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
- 같은 멱등 reservation command가 pool entry를 두 개 이상 소비하지 않으며 allocation retry는
  이미 선택된 entry만 확정한다.
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
- purpose-separated keyed digest, pre-reveal per-entry envelope encryption, reveal 후 ciphertext 삭제
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

import/generation은 batch를 `STAGING`으로 만들고 command마다 최대 500 entry의 chunk를 처리한다.
batch 전체 상한은 10,000 entry, JSON request 상한은 4 MiB다. 첫 import command는 provenance,
`expectedCount`, 전체 source manifest digest, ordinal 0부터의 첫 chunk를 받고 `201` batch snapshot을
반환한다. `POST /operator/api/v1/batches/{batchId}/import-chunks`는 정확한
`nextSourceOrdinal`, 같은 manifest digest와 다음 chunk를 받는다. 호출마다 새 idempotency key를
사용하되 command fingerprint에는 batch, ordinal range와 chunk digest가 포함된다. 같은 chunk의
same-key replay는 같은 checkpoint를 반환하고, 이미 commit된 ordinal을 다른 key로 보내도 digest가
같으면 replay success, 다르면 `409 CHUNK_FINGERPRINT_CONFLICT`다.

generation은 non-test에서 `SecureRandom`으로 entry마다 최소 128-bit entropy를 만들고
`POST /operator/api/v1/batches/{batchId}/generate-chunks`의 bounded chunk/checkpoint protocol을
사용한다. request는 exact next ordinal과 count `<=500`만 받으며 seed/raw code를 받지 않는다.
rollback한 chunk는 같은 key takeover에서 새 random code를 만들어도 외부 effect가 없고, committed
chunk는 stored checkpoint/descriptor를 replay한다. final chunk의 count/digest reconciliation 뒤에만
ACTIVE 전이가 가능하다. deterministic generator는 loopback test/demo profile에서만
등록하며 non-test에서 알려진 seed 또는 deterministic generator가 보이면 startup을 실패시킨다.
seed는 API, DB, log와 audit에 저장하지 않는다.

각 entry는 `(tenant_id, batch_id, source_ordinal)`과 stable code-dedup digest가 unique하다. JSON
parsing, canonical validation과 encryption은 bounded buffer에서 transaction 밖에서 끝내고, chunk
transaction은 prepared ciphertext persistence와 checkpoint만 수행한다. transaction은 성공한 마지막
ordinal, accepted/rejected count, bounded failure code를 기록한다.

entry validation failure는 원문 code 대신 source ordinal, bounded reason, payload digest만 남긴다.
validation, duplicate, cryptographic failure는 `FAILED_TERMINAL`이고 수정된 payload와 새 batch/key를
요구한다. process crash, connection loss와 retryable resource failure는 batch를 `STAGING` 또는
`FAILED_RETRYABLE`에 두며 stale owner takeover 뒤 첫 미완료 ordinal부터 재개한다. 부분 성공 batch는
allocation 대상이 아니다. `rejectedCount`가 0이고 모든 chunk와 count/digest reconciliation이
통과해야 `STAGING -> ACTIVE`가 된다.

#### 거절: 전체 batch 단일 transaction

원자성은 단순하지만 batch 크기에 비례해 transaction, lock, memory와 재시도 비용이 커지고 부분
실패 진단이 약하다.

#### 거절: 외부 broker/object storage pipeline

대규모 ingest에는 적합하지만 provider와 credential이 필요하고 이 workshop의 bounded reference
scope를 벗어난다.

### Code 보관과 공개

#### 채택: 공개 전 per-entry envelope encryption, 공개 후 digest-only

- code dedup, verification, user identity, idempotency, Redis/Bloom, audit/correlation에 서로 다른
  configured key ring 또는 domain-separated subkey를 사용한다. canonical input은 length-prefix와
  tenant, campaign, purpose, operation scope를 포함한다.
- tenant/campaign lifecycle 전체의 plaintext 중복은 rotation과 무관한 stable code-dedup digest로
  막고, verification digest는 version을 저장한다. rotation 중 retained read version 전체를 검사하며
  unknown version은 not-found가 아니라 fail-closed다.
- import code는 random per-entry DEK로 JDK `AES/GCM/NoPadding` 암호화하고, DEK는 versioned KEK로
  다시 AES-GCM wrapping한다. 96-bit nonce 두 개, code ciphertext, wrapped DEK와 KEK version만 저장한다.
- code AAD는 immutable tenant/campaign/batch/entry/source ordinal을 canonical encoding하고, DEK-wrap
  AAD는 entry와 KEK version을 묶는다. `(kek_version, wrap_nonce)`와 code nonce는 unique constraint로
  보호하고 retry는 새 nonce/DEK를 만든다. decrypt 뒤 stable digest를 다시 검증해 row swap을 막는다.
- generated code는 non-test `SecureRandom` 또는 test-only deterministic generator가 만들고 entry마다
  암호화해 import와 같은 storage lifecycle을 사용한다.
- raw code, generation seed, plaintext payload는 DB, idempotency response, audit, log와 metric label에
  저장하지 않는다.
- reveal transaction은 allocation ownership과 unrevealed 상태를 lock하고 decrypt한 뒤
  `revealed_at`을 기록하며 code ciphertext, nonce, wrapped DEK와 wrap nonce를 같은 transaction에서
  제거한다. lookup digest와 verification key version만 남긴다.
- raw code는 transaction 밖에서 response object에만 존재하며 operational log/toString에서 제외한다.

현재 catalog와 #534 source에서 reusable Bluetape envelope-encryption helper를 찾지 못했다. 새
dependency를 추가하지 않고 JDK cryptography를 사용한다. current/read KEK와 verification key set,
reference drain 뒤 retirement, missing referenced key의 fail-closed startup/readiness, nonce uniqueness,
authenticated tag failure와 rotation을 직접 테스트한다. “one-time”은 application replay 금지와
per-entry key material 삭제를 뜻하며 WAL, backup, JVM 또는 browser의 물리적 zero-retention을
절대적으로 보장한다는 뜻이 아니다.

#### 거절: plaintext code 저장

operator query, backup, log 또는 accidental serialization을 통해 전체 pool이 유출될 수 있다.

#### 거절: reveal 뒤에도 ciphertext 유지

response-loss recovery는 쉬워지지만 “one-time reveal 뒤 keyed digest만 저장” 계약을 위반한다.

## Architecture

PostgreSQL 중심 modular monolith를 사용한다.

| 경계 | 책임 | 장애 시 동작 |
|---|---|---|
| PostgreSQL | batch, entry, reservation, allocation, redemption, idempotency, audit, inbox | terminal outcome와 시간 판정의 유일한 권위다. |
| Redis/Bucket4j | tenant/campaign/user admission | 장애 시 bounded node-local permit으로 축소 운전한다. |
| Redis Bloom | keyed code/user signal의 이전 관찰 hint | positive는 deterministic review hint이며 terminal reject가 아니다. |
| Always-on permit | 모든 JDBC 진입 전 DB 보호 | foreground, worker, SSE query lane을 분리하고 포화 시 `503`을 반환한다. |
| Leader trigger | expiry/revocation/reconciliation 중복 감소 | leader가 없어도 operator command로 같은 bounded path를 실행한다. |
| Deterministic fake | fraud/identity/WAF/notification 결과 | 실제 provider 없이 failure와 replay 순서를 재현한다. |
| Static browser | customer/operator workflow와 SSE | SSE 실패 시 bounded polling으로 전환한다. |

Spring component는 `web`, `application`, `persistence`, `security`, `admission`, `reconciliation`,
`fixture`, `config`, `query` package로 분리한다. Controller는 request validation과 HTTP mapping만,
application service는 transaction use case와 state transition만, repository는 Exposed/JDBC와 raw
locking SQL만 소유한다.

## Domain model

### Campaign

`voucher_pool_campaigns`는 tenant/campaign public ID, `ACTIVE|PAUSED|REVOKED` state, policy version,
revision과 audit timestamps를 가진 단일 policy aggregate다. batch lifecycle과 entry eligibility는 이
campaign snapshot 아래에 있으며 campaign pause/revoke는 모든 batch보다 먼저 lock한다. Operator는
campaign pause/resume/revoke를 expected campaign revision으로 실행한다. campaign `REVOKED`는
terminal이고 campaign state와 batch state 중 더 제한적인 결과가 foreground command를 지배한다.

### Voucher batch

`voucher_batches`는 다음 필드를 가진다.

- `tenant_id`, public UUID `batch_id`, `campaign_id`
- `state`: `STAGING`, `FAILED_RETRYABLE`, `FAILED_TERMINAL`, `ACTIVE`, `PAUSED`,
  `EXPIRING`, `EXPIRED`, `REVOKING`, `REVOKED`
- `source_kind`: `IMPORTED`, `GENERATED`
- `provenance_digest`, `request_fingerprint`
- `policy_version`, `activates_at`, `expires_at`
- `next_source_ordinal`, `expected_count`, `accepted_count`, `rejected_count`
- `last_failure_code`, `revision`, auditable timestamps

상태 전이는 다음과 같다.

- `STAGING -> ACTIVE`: 모든 chunk와 digest/count reconciliation이 통과한 경우만 가능하다.
- `STAGING -> FAILED_RETRYABLE -> STAGING`: crash/resource failure 뒤 stale owner takeover와 같은
  manifest의 resume만 가능하다.
- `STAGING -> FAILED_TERMINAL`: validation, duplicate, cryptographic failure이며 새 batch만 허용한다.
- `ACTIVE -> PAUSED -> ACTIVE`: activation window 안에서만 가능하다.
- `ACTIVE|PAUSED -> EXPIRING -> EXPIRED`: PostgreSQL time 기준 activation window 종료를 bounded
  worker가 terminalize한다.
- `ACTIVE|PAUSED|FAILED_RETRYABLE|FAILED_TERMINAL -> REVOKING -> REVOKED`: bounded worker가
  entry를 chunk로 종결한다.
- `EXPIRED|REVOKED`는 terminal이며 새 entry나 reservation을 허용하지 않는다.

### Pool entry

`voucher_pool_entries`는 다음 필드를 가진다.

- `tenant_id`, `campaign_id`, `batch_id`, public UUID `entry_id`
- `source_ordinal`, `state`
- `stable_dedup_digest`, `verification_digest`, `verification_key_version`
- pre-reveal `code_ciphertext`, `code_nonce`, `wrapped_dek`, `wrap_nonce`, `kek_version`
- `reservation_id`, `allocation_id`, `user_digest`
- `reserved_at`, `reservation_expires_at`, `allocated_at`, `allocation_expires_at`, `revealed_at`,
  `redeemed_at`
- `allocation_policy_version`, `terminal_reason`, `revision`
- `entitlement_root_id`, `replacement_count`, `quarantined_at`

전이와 재사용 규칙은 다음과 같다.

| From | 명령 | To | 재사용 |
|---|---|---|---|
| `AVAILABLE` | reserve | `RESERVED` | reservation이 만료되기 전에는 불가 |
| `AVAILABLE` | batch revoke/expiry | `REVOKED|EXPIRED` | terminal |
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

`voucher_pool_user_limits`는 `(tenant_id, campaign_id, user_digest)` unique row로 active reservation,
active allocation과 lifetime consumed count, revision을 가진다. `INSERT ... ON CONFLICT DO NOTHING`
후 같은 row를 lock해 최초 생성 race도 직렬화한다. reserve는 reservation +1, allocate는 reservation
-1/allocation +1/lifetime +1, reservation expiry는 reservation -1, release/allocated expiry/revoke는
allocation -1, redeem은 allocation -1이며 lifetime은 감소하지 않는다. 모든 delta는 entry 전이와
같은 transaction이고 negative count를 check constraint로 막는다. reconciliation은 source rows에서
projection drift를 검출하고 자동 수정 전후 값을 audit한다.

| Transition | Active reservation | Active allocation | Lifetime consumed |
|---|---:|---:|---:|
| reserve | +1 | 0 | 0 |
| reservation expiry/revoke/batch expiry | -1 | 0 | 0 |
| allocate ordinary | -1 | +1 | +1 |
| allocate replacement entitlement | -1 | +1 | 0 |
| reveal | 0 | 0 | 0 |
| redeem/release/allocation expiry/revoke | 0 | -1 | 0 |

최초 ordinary allocation은 새 `entitlement_root_id`를 만들고 lifetime을 한 번 소비한다. reveal-loss
replacement는 같은 root와 entitlement를 새 reservation/allocation으로 이전하며 campaign/user/root
unique record로 총 1회만 허용한다. 원 allocation revoke는 active allocation -1, replacement reservation은
active reservation +1이고 후속 replacement allocation은 lifetime을 증가시키지 않는다. 두 번째
reveal-loss는 추가 pool 소비 없이 terminal operator escalation이다.

### Idempotency

`voucher_pool_http_idempotency`는 #534와 같은 scope, fingerprint, owner lease, terminal
descriptor, replay/conflict/takeover/cleanup 계약을 사용한다.

- reserve, allocate, redeem, release, operator batch command는 same-key/same-payload terminal
  response를 replay한다.
- same-key/different-payload는 `409 IDEMPOTENCY_FINGERPRINT_CONFLICT`다.
- active owner는 `409 COMMAND_IN_PROGRESS`와 bounded `Retry-After`를 반환한다.
- stale owner takeover와 retryable failure release는 PostgreSQL CAS로 결정한다.
- idempotency key와 raw request payload는 저장하지 않고 scoped digest/fingerprint만 저장한다.

full terminal descriptor는 24시간 보존하지만 minimal `voucher_pool_command_tombstones`는 tenant,
operation, scoped key digest, fingerprint와 effect public ID 또는 terminal code만 tenant 삭제 전까지
보존한다. descriptor
purge 뒤 same-key/same-fingerprint는 `410 REPLAY_WINDOW_EXPIRED`와 기존 effect ID를 반환하고 새 effect를
실행하지 않으며 different fingerprint는 계속 conflict다. API가 원 response replay를 보장하는 window는
24시간임을 header/README에 공개한다.

one-time reveal은 의도적으로 일반 response replay contract에서 제외한다. 첫 성공은 code를 반환하고
같은 키 재요청은 side effect 없이 `200`, `Duplicate-Request: true`,
`codeAvailable=false`, `outcome=ALREADY_REVEALED`를 반환한다. 응답을 잃은 client는 이전 code를
복구할 수 없다. Customer는 명시적 확인 뒤 allocation replacement command를 실행한다. 이 command는
전역 lock order로 campaign, batch, user-limit, 원 reservation/entry를 lock하고 replacement capacity를 먼저
확보한 뒤 원 entry를 `REVOKED`로 만들고 새 reservation을 한 transaction에서 생성한다. capacity가
없으면 원 상태를 바꾸지 않는다.
원 allocation당 replacement는 한 번만 허용하며 이전 code는 절대 재사용하지 않는다.

### Dedup ledger와 quarantine

`voucher_pool_code_dedup`은 `(tenant_id, stable_dedup_digest)` unique tombstone, first campaign/batch/entry,
key version과 first-seen time만 저장한다. entry가 purge되어도 tombstone은 tenant의 명시적 irreversible
deletion과 backup-retention 종료 전에는 삭제하지 않는다. import/generation은 entry insert보다 먼저
ledger uniqueness를 획득하므로 campaign과 key rotation을 넘어 공개/terminal code를 재사용하지 않는다.

Cryptographic corruption은 entry state를 덮지 않고 `voucher_pool_quarantines`에 tenant/entry,
source state/revision, allowlisted reason, detected time과 resolution을 원자적으로 기록한다. 모든
eligibility/reveal/redeem query는 active quarantine을 제외한다. key 복구와 digest/tag 재검증 성공 시
expected revision으로 clear할 수 있고, 불가하면 unrevealed entry는 `REVOKED`, 이미 redeemed/released인
entry는 terminal state 유지 + resolved audit다. Alert는 quarantine resolution commit 뒤 clear된다.

### Audit와 reconciliation inbox

`voucher_pool_audits`는 append-only이고 aggregate revision, policy version, actor type,
bounded reason, request/correlation digest만 저장한다. `voucher_pool_reconciliation_inbox`는 delayed
fixture와 worker command의 event id, payload digest, status, attempt, next attempt, claim lease와
terminal outcome을 저장한다. raw code, tenant, user/device/IP, idempotency key는 저장하지 않는다.

모든 lease, TTL, activation window와 expiry 경계는 PostgreSQL transaction time을 사용한다. JVM
clock은 표시와 test injection에만 쓰며 equality는 만료로 판정한다.

## Physical schema와 query contract

- 모든 aggregate PK/FK는 tenant를 포함한 composite key이며 cross-tenant FK가 성립하지 않는다.
- public resource는 `(tenant_id, campaign_id)`, `(tenant_id, batch_id)`,
  `(tenant_id, reservation_id)`, `(tenant_id, allocation_id)`가 각각 unique다. reservation과 allocation은
  tenant-scoped FK로 정확히 한 entry를 참조한다.
- dedup ledger는 `(tenant_id, stable_dedup_digest)`, entry source는
  `(tenant_id, batch_id, source_ordinal)`이 unique다.
- entry는 state별 required/null column check를 둔다. `AVAILABLE`은 reservation/allocation/user fields가
  null이고, recycle transaction은 이를 명시적으로 clear한다. `RESERVED`와 이후 상태는 대응 public
  id와 timestamps를 요구한다.
- campaign당 복수 ACTIVE batch를 허용하고 allocation 후보는
  `(activates_at, batch_id, source_ordinal, entry_id)` 순서다.
- partial index는 eligible entry `(tenant_id, campaign_id, state, batch_id, source_ordinal, entry_id)`,
  reservation expiry `(state, reservation_expires_at, entry_id)`, allocation expiry
  `(state, allocation_expires_at, entry_id)`, revocation `(batch_id, state, entry_id)`를 지원한다.
- redemption은 tenant/allocation ID로 row와 stored verification key version을 찾고 lock한 뒤 해당
  retained key로 digest를 계산해 constant-time 비교한다. raw-code-only global scan은 없다.
- exact pool depth는 `(tenant_id, batch_id, state)` counter projection을 entry 전이와 같은 transaction에서
  갱신하고 reconciliation이 source rows와 비교한다.
- representative 10,000-entry batch에서 핵심 query의 `EXPLAIN (ANALYZE, BUFFERS)`를 저장하고
  candidate/worker lookup은 index scan, returned rows는 chunk 상한, heap fetch와 buffer는 plan에서
  고정한 report limit 안이어야 한다.

## Transaction과 race 계약

Idempotency lookup/admission과 owner lease 획득은 짧은 별도 transaction이다. business transaction은
committed owner/lease를 다시 확인한 뒤 effect와 safe descriptor를 finalize하고, retryable failure는
별도 짧은 transaction에서 owner를 release한다. 경쟁 caller는 committed owner를 보고
`COMMAND_IN_PROGRESS`를 받으며 unique-insert wait를 business transaction까지 끌고 가지 않는다.

모든 foreground/operator/worker transaction의 전역 lock order는 `campaign -> batch -> user-limit ->
reservation -> entry -> audit/inbox`다. 각 command는 필요한 row만 잠그되 이 상대 순서를 지킨다.
필요한 ID는 lock 없이 먼저 resolve할 수 있지만 역순 lock은 금지한다.
batch pause/revoke/expiry와 allocate/redeem은 batch lock 또는 expected state/revision conditional update로
commit 순서를 고정한다. deadlock, permit wait와 lock timeout은 terminal descriptor를 저장하지 않는
retryable `503`과 bounded `Retry-After`로 매핑한다.

### Reservation

한 transaction에서 다음 순서를 지킨다.

1. committed idempotency owner/lease를 재검증한다.
2. ACTIVE campaign을 lock하고 policy/revision을 확인한다.
3. ACTIVE batch를 `(activates_at, batch_id)` 순서로 lock하고 window를 확인한다.
4. tenant/campaign/user limit row를 upsert한 뒤 lock한다.
5. `AVAILABLE` entry 하나를 deterministic ordering + `FOR UPDATE SKIP LOCKED`로 선택한다.
6. entry와 reservation을 기록하고 user active count를 증가시킨다.
7. audit와 safe idempotency descriptor를 finalize한다.

`SKIP LOCKED` 후보가 없으면 동일 eligibility predicate의 non-locking existence query를 실행한다.
eligible row가 보이면 transient contention `503 POOL_BUSY`, 실제 row가 없을 때만 terminal
`409 POOL_EXHAUSTED`를 저장한다. lock/permit timeout도 retryable `503`이고 terminal descriptor로
고정하지 않는다.

### Allocation과 reveal

allocation은 batch, user-limit, reservation, entry 순서로 lock하고 owner, expiry, batch state, policy
version을 다시 확인한다. `allocation_expires_at`은 PostgreSQL time에서
`min(batch.expires_at, now + policy allocationTtl)`로 snapshot한다. batch pause/revoke가 먼저 commit하면
allocation은 거절된다. allocation이 먼저 commit하면 후속 revoke가 `ALLOCATED -> REVOKED`를 기록한다.

reveal은 같은 전역 순서로 batch/reservation/entry를 lock하고 `ALLOCATED`, correct owner,
`revealed_at IS NULL`, ciphertext 존재를 검증한다. decrypt/tag 검증과 ciphertext 제거를 같은
transaction에서 수행한다. commit 뒤 response serialization이 실패해도 entry는 exposed로 취급한다.

### Redemption과 revocation

redemption은 tenant/allocation ID로 batch/entry identity와 stored verification key version을 먼저
resolve하고 전역 순서로 lock한다. canonical code digest를 retained version으로 계산해 constant-time
비교하고 batch/campaign safety state, allocation expiry와 policy snapshot을 검증한 뒤
`ALLOCATED -> REDEEMED`를 단 한 번 기록한다.

batch revocation/expiry worker는 batch를 `REVOKING|EXPIRING`으로 바꾸고 durable cursor 뒤 entry를
`FOR UPDATE SKIP LOCKED`로 최대 100개씩 짧은 transaction에서 처리한다. cursor 끝에서 wrap-around해
skipped row를 재검사하며 bounded run deadline 뒤 backoff한다. zero-remaining authoritative query와
counter reconciliation이 통과해야 batch를 terminal로 바꾼다. `AVAILABLE|RESERVED|ALLOCATED`는
각 worker의 `REVOKED|EXPIRED`, `REDEEMED`는 그대로 유지한다. redemption과 worker는 같은 campaign-first
lock order와 revision으로 단일 terminal outcome을 만든다.

Batch expiry는 `AVAILABLE|RESERVED|ALLOCATED -> EXPIRED`, `REDEEMED|RELEASED|REVOKED` 유지로
고정한다. reservation/allocation command가 먼저 commit하면 expiry worker가 그 committed state를
terminalize하고, batch가 먼저 `EXPIRING`이면 새 foreground transition을 거절한다. Dashboard는
eligible depth와 expired-but-not-terminalized depth를 별도로 표시한다.

## Worker recovery contract

expiry, revocation, reconciliation, purge와 delayed fixture worker는 `claim_owner`, `claim_until`,
`claim_revision`, `attempt`, `next_attempt_at`, cursor와 last checkpoint를 PostgreSQL에 저장한다. Claim,
chunk commit과 finalize는 owner/revision CAS이고 lease가 지난 stale owner 결과는 거절한다. leader와
operator command는 같은 claim path를 사용한다. bounded exponential backoff와 max attempt 뒤
`POISONED`가 되며 operator는 원문 payload 없이 reason과 next action을 본다. 매 run은 deadline과
single-run guard를 가지며 cancellation은 현재 chunk commit 뒤 claim을 release한다. 기본 claim은
15초, chunk deadline은 10초, run deadline은 30초, max attempt는 5, backoff는 1초에서 30초까지다.
각 successful checkpoint는 owner/revision CAS로 `claim_until=databaseNow+15s`를 갱신한다. renewal이
실패하면 다음 chunk를 시작하지 않고 현재 chunk도 owner/revision이 유효할 때만 commit한다. healthy
30초 run에는 takeover가 없고 stale finalize는 항상 거절된다.

## HTTP와 browser 계약

### Customer API

- `POST /api/v1/campaigns/{campaignId}/reservations`
- `GET /api/v1/reservations/{reservationId}`
- `POST /api/v1/reservations/{reservationId}/allocate`
- `POST /api/v1/allocations/{allocationId}/code-reveals`
- `GET /api/v1/allocations/{allocationId}` — raw code를 반환하지 않는다.
- `POST /api/v1/allocations/{allocationId}/replacements` — reveal-loss 전용 1회 recovery
- `POST /api/v1/allocations/{allocationId}/redeem`
- `POST /api/v1/allocations/{allocationId}/release`
- `GET /api/v1/snapshots`
- `GET /api/v1/events?cursor=`

모든 customer GET/mutation/SSE는 server-established tenant/principal과 resource owner predicate를
사용한다. `userRef`는 body에 받지 않고 principal에서 파생하며 scope mismatch는 uniform `404`다.
demo header identity는 authentication이 아니므로 loopback bind에서만 허용한다. 실제 authentication
adapter 없이 public bind하면 startup을 실패시킨다. Mutation은 bounded `Idempotency-Key`, expected
revision과 JSON size/content validation을 요구한다. Resource create는 expected revision 대신
`If-None-Match: *`와 idempotency/fingerprint를 사용한다.

### Operator API

- `POST /operator/api/v1/batches/import`
- `POST /operator/api/v1/batches/generate`
- `POST /operator/api/v1/batches/{batchId}/import-chunks`
- `POST /operator/api/v1/batches/{batchId}/generate-chunks`
- `POST /operator/api/v1/batches/{batchId}/resume`
- `POST /operator/api/v1/batches/{batchId}/activate`
- `POST /operator/api/v1/batches/{batchId}/pause`
- `POST /operator/api/v1/batches/{batchId}/revoke-preview`
- `POST /operator/api/v1/batches/{batchId}/revoke`
- `POST /operator/api/v1/campaigns/{campaignId}/pause`
- `POST /operator/api/v1/campaigns/{campaignId}/resume`
- `POST /operator/api/v1/campaigns/{campaignId}/revoke`
- `GET /operator/api/v1/batches/{batchId}`
- `GET /operator/api/v1/pool-depth?campaignId=&batchId=`
- `GET /operator/api/v1/reservations/stuck?campaignId=&cursor=&limit=`
- `GET /operator/api/v1/diagnostics/{requestId}`
- `POST /operator/api/v1/reconciliation/run`
- `GET /operator/api/v1/snapshots?campaignId=&batchId=`
- `GET /operator/api/v1/events?campaignId=&batchId=&cursor=`
- test profile의 guarded deterministic failure/fixture routes

모든 `/operator/**` GET/POST/SSE는 strict Host/Origin allowlist, CORS deny-by-default, constant-time
operator secret, credential rate limit와 tenant scope를 요구하며 mismatch는 uniform `404`다. 기존
aggregate mutation은 추가로 idempotency key와 expected aggregate revision을 요구한다. Create는
`If-None-Match: *`, reconciliation은 idempotency + single-run guard를 사용한다. Cookie에 secret을
저장하지 않는다. Revoke는
affected state/count preview, batch identity 재입력, fresh revision, 상태별 action disable, progress와
partial-recovery 안내가 선행된다.

### HTTP success와 error vocabulary

create는 `201 + Location`, accepted worker command는 `202`, safe replay/조회/전이는 `200`을 기본으로
한다. 모든 resource response는 public ID, state, revision, observed PostgreSQL time와 safe request ID를
포함하고 raw code는 reveal의 첫 `200`에만 있다. error body는 `code`, allowlisted `reason`, `requestId`,
optional `retryAfterSeconds`만 가진다.

| Code | HTTP | Retry/same key · descriptor | Caller action |
|---|---:|---|---|
| `COMMAND_IN_PROGRESS` | 409 | retry / same · release | `Retry-After` 뒤 재시도 |
| `IDEMPOTENCY_FINGERPRINT_CONFLICT` | 409 | no / new · tombstone | payload/key 수정 |
| `REPLAY_WINDOW_EXPIRED` | 410 | no effect / same · tombstone | effect ID 조회 |
| `POOL_BUSY`, `BACKEND_TIMEOUT`, `BATCH_FAILED_RETRYABLE` | 503 | retry / same · release | bounded backoff |
| `POOL_EXHAUSTED`, `USER_LIMIT_REACHED` | 409 | terminal / new · store | UI terminal 또는 operator 확인 |
| `STALE_REVISION` | 409 | no / new · release | snapshot refresh |
| `CAMPAIGN_PAUSED`, `BATCH_PAUSED`, `BATCH_EXPIRING` | 409 | retry / same · release | 상태 refresh/backoff |
| `CAMPAIGN_REVOKED`, `BATCH_REVOKED`, `BATCH_EXPIRED`, `BATCH_FAILED_TERMINAL` | 409 | terminal / new · store | 새 campaign/batch 또는 operator 확인 |
| `RESERVATION_EXPIRED`, `ALLOCATION_EXPIRED` | 409 | terminal / new · store | 새 reservation/recovery 판단 |
| `WRONG_OWNER`, `SCOPE_NOT_FOUND` | 404 | no · release | resource를 노출하지 않음 |
| `RATE_LIMITED` | 429 | retry / same · release | `Retry-After` |
| `KEY_MATERIAL_UNAVAILABLE`, `CIPHERTEXT_INVALID` | 503 | retry / same · release | operator escalation; fail closed |
| `ALREADY_REVEALED` | 200 | duplicate / n/a · store safe descriptor | replacement flow 안내 |

### Route contract matrix

| Route group | Request/precondition | Success | Stored result / ownership |
|---|---|---|---|
| reserve create | campaign ID, `If-None-Match: *`, idempotency | `201 Location`, reservation snapshot | terminal descriptor/tombstone; principal owner |
| reservation GET | resource ID | `200` snapshot | no descriptor; principal owner |
| allocate | expected reservation revision, idempotency | `200` allocation snapshot | terminal descriptor; principal owner |
| reveal | expected allocation revision, idempotency | first `200` code, duplicate safe `200` | safe descriptor only; principal owner |
| replacement | expected allocation revision, idempotency, explicit confirm | `201 Location`, reservation snapshot | root-limited terminal descriptor; principal owner |
| redeem/release | expected allocation revision, idempotency | `200` terminal snapshot | terminal descriptor; principal owner |
| customer snapshot/SSE | cursor optional | `200` snapshot or event stream | no descriptor; principal owner |
| batch import/generate create | DTO, `If-None-Match: *`, idempotency | `201 Location`, batch checkpoint | terminal descriptor/tombstone; operator tenant |
| import/generate chunk | expected batch revision, exact ordinal/count, idempotency | `200` checkpoint | terminal descriptor; operator tenant |
| batch resume | expected batch revision, idempotency | `202` worker snapshot | accepted descriptor; operator tenant |
| campaign/batch pause/resume/activate | expected aggregate revision, idempotency | `200` snapshot | terminal descriptor; operator tenant |
| revoke preview | expected aggregate revision | `200` impact snapshot | no descriptor; operator tenant |
| revoke | expected aggregate revision, preview token, idempotency | `202` progress snapshot | accepted descriptor; operator tenant |
| reconciliation run | idempotency, single-run guard, scope | `202` run snapshot | accepted descriptor; operator tenant |
| operator query/SSE | scope, cursor/limit | `200` snapshot or stream | no descriptor; operator tenant |

DTO는 raw code를 import chunk 외에는 포함하지 않는다. 모든 mutation response는 revision, PostgreSQL
observed time, request ID와 route-specific next action을 포함하며 error subset은 aggregate state와 위
vocabulary에서 결정한다.

Import/generation response는 batch ID/state/revision, next ordinal, expected/accepted/rejected count,
checkpoint digest와 `nextAction`을 포함한다. failure list는 ordinal과 bounded reason만 cursor/limit 100으로
조회한다. activation 가능 여부와 resume/import-chunk URI는 snapshot과 SSE에 동일하게 노출한다.
safe `requestId`는 response header/body와 tenant-scoped diagnostic lookup을 연결한다.

### UI

한 static browser application에서 customer와 operator view를 분리한다.

- customer flow: admission → reservation → allocation → one-time reveal → redemption
- operator batch view: provenance, chunk progress, active window, accepted/rejected count
- pool depth: eligible available과 expired-but-not-terminalized, reserved/allocated/redeemed/released/
  expired/revoked count
- timeline: state, policy version, bounded reason, revision, timestamp
- stuck reservation과 worker lease
- batch revocation progress와 reconciliation before/after count
- Redis healthy/degraded와 PostgreSQL authoritative outcome을 구분하는 banner

reveal은 explicit user gesture와 confirmation 뒤 실행한다. response는 `Cache-Control: no-store`,
`Pragma: no-cache`, `Referrer-Policy: no-referrer`, strict CSP를 사용하며 third-party script/service worker,
URL/history/localStorage/sessionStorage와 application log에 code를 두지 않는다. code는 bounded ephemeral
DOM에만 표시하고 copy acknowledgement, navigation/refresh 경고와 clear control을 제공한다. 서버는
mutable buffer와 best-effort zeroization을 사용하고 reveal body/error logging과 production heap dump를
금지한다. semantic control, keyboard-only flow, focus 이동, `aria-live`, 색 외 상태 표현을 검증한다.

SSE는 customer/operator별 동일 authorization을 적용하는 header-capable same-origin endpoint다.
global 32, campaign 8 subscriber, client queue 64 event/256 KiB, shared poller query 100 row/512 KiB,
write 5초를 상한으로 한다. overflow/expired cursor는 reset 후 close하고 polling route도 같은 scope를
사용한다. JDBC permit은 connection lifetime이 아니라 query 동안만 보유한다.

list와 SSE payload는 public UUID, state, bounded reason과 coarse count만 포함한다. raw code,
user/device/IP, idempotency key, secret, request body와 digest 원문은 표시하지 않는다.

## Configuration과 lifecycle

- Java/Kotlin toolchain과 target은 25다.
- Spring MVC virtual thread를 사용하되 Hikari max 16을 기본으로 유지한다.
- foreground/worker/SSE query permit은 항상 JDBC connection 획득 전에 12/1/3으로 분리하며 합계는
  16을 넘지 않는다. nested acquire를 금지하고 foreground 250 ms, worker/SSE 1초 wait 뒤 `503`이다.
  공정한 semaphore를 쓰고 transaction 종료/cancellation에서 즉시 release하며 leak 0을 검증한다.
- common HTTP와 management readiness client timeout은 60초다. Hikari connection wait는 2초,
  foreground transaction/lock deadline은 5초, worker chunk는 10초다.
- batch chunk max 500, revoke chunk max 100, reconciliation max 50을 기본값으로 둔다.
- reservation TTL, allocation TTL, worker deadline, key ring, operator guard, Redis URI와 enable flag는 immutable
  configuration properties로 노출하고 startup validation을 둔다.
- packaged YAML/default/command-line에는 cryptographic key material을 금지한다. non-test key는
  permission-restricted mounted secret 또는 secret-provider environment indirection에서만 공급하며
  source provenance를 startup에 검증한다. Actuator/config diagnostics는 key property를 sanitize하고
  key bytes는 log/toString/exception에 없다. Inline/default/test material, 짧은 key, missing operator
  secret과 invalid timeout/pool/chunk 조합은 fail-fast다.
- shutdown은 readiness DOWN → 새 command/SSE 거부 → scheduler/leader trigger 중단 → worker claim 중단
  → in-flight transaction bounded drain → SSE/poller → leader lease/Redis/Bloom → owned executor →
  DataSource 순서다. transaction drain은 worker chunk deadline보다 긴 12초다. 미완료 시 cancel →
  rollback 확인 → claim release를 추가 5초 기다린 뒤에만 resource를 닫는다. 나머지 단계는 5초,
  전체 45초 뒤 forced reason을 metric/log에 남기며 close는 idempotent다.
- liveness는 process-only다. readiness는 migration, PostgreSQL과 global/live-reference key usability를
  필수로 한다. 단일 quarantined entry 손상과 Redis/leader 장애는 redacted `DEGRADED`, recovery 중은
  `RECOVERING`이다. reason은 allowlist이고 last-success/error time을 노출한다. management port는
  loopback bind와 health/metrics allowlist를 사용한다.

### Rate limit과 abuse boundary

reserve/allocate/reveal/redeem/operator-auth는 서로 다른 keyed namespace를 사용하며 기본 상한은
principal/source별 분당 20/20/5/10/5다. 특히 reveal/redeem/operator-auth는 Redis 장애에도 같은
node-local hard cap, short permit wait와 uniform
not-found/unauthorized timing을 유지한다. 공격 실패 횟수는 voucher를 terminal lockout하지 않으며
정상 owner의 PostgreSQL state transition만 terminal outcome을 만든다.

## Migration, key backup과 배포

application-owned migration runner는 `src/main/resources/db/migration/V001__voucher_pool.sql` 같은
versioned SQL과 migration-history checksum을 authority로 사용한다. PostgreSQL advisory lock으로 동시
startup을 직렬화하고 checksum drift는 startup fail-closed다. clean, current warm, previous-schema
startup을 검증한다. 변경은 expand → compatible dual-read/write → contract 순서이며 이 예제의 rollback은
직전 schema와 binary까지만 보장한다.

DB backup은 referenced KEK/verification/dedup key-version inventory와 같은 recovery unit으로 기록한다.
restore는 key manifest를 먼저 검증하고 DB를 복구한 뒤 live ciphertext coverage, counter, idempotency
replay, audit/cursor, stale worker takeover와 one-time reveal smoke를 실행한다. rotate는 current 추가 →
read set 유지 → reference drain 확인 → backup/rollback rehearsal → retire 순서다. KEK/verification key는
이 절차로 회전하고, stable dedup key는 tenant-lifetime tombstone이 존재하는 동안 retire하지 않는다.

## Observability, retention과 runbook

metric tag는 command/outcome/reason/backend/state의 bounded enum만 허용한다. tenant, batch, allocation,
request, user, code/digest, URL과 exception message는 label 금지다. worker backlog/oldest age, claim stall,
Hikari active/pending, permit wait, SSE subscribers/reset, pool depth, degraded component, purge와 restore
결과를 측정하고 structured lifecycle event도 같은 redaction을 따른다.

Hikari pending 지속, worker checkpoint 정체, Redis degradation, pool exhaustion, SSE reset burst,
quarantined ciphertext, purge lag와 restore failure에 threshold/duration alert와 recovery signal을 둔다.
runbook은 alert마다 safe request ID/tenant-scoped diagnostic, authoritative query, bounded operator action과
recovery verification을 연결한다.

기본 warning은 Hikari pending `>0` 10초, worker no-progress 30초, Redis degraded 30초, eligible pool
depth 10% 미만 60초, SSE reset 10회/분, quarantine 1건 이상, purge lag 24시간, restore smoke failure
즉시다. recovery signal은 같은 window에서 정상 상태를 확인한 뒤 발생한다.

기본 retention은 full idempotency descriptor 24시간, terminal inbox/claim 7일, terminal entry와
reservation history 30일, audit 90일이다. command tombstone과 stable dedup ledger는 tenant의 명시적
irreversible deletion + backup retention 종료까지 유지한다. quarantine과 legal-hold fixture는 purge를
중단한다. key는 live row, command/dedup tombstone, backup inventory와 audit retention이 참조하는 동안
retire하지 않는다.
bounded purge worker는 dependency order와 cursor를 쓰며 oldest age/count를 보고하고 backup/restore 및
concurrent replay와 경합 테스트를 가진다.

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
   `ALREADY_REVEALED`를 반환하고 owner-confirmed allocation replacement flow를 따른다.
4. **batch chunk 중간에 process가 종료된다.** 마지막 committed ordinal부터 같은 fingerprint로
   resume한다. duplicate digest/source ordinal은 deterministic diagnostic으로 남는다.
5. **Redis가 timeout 또는 unavailable이다.** admission은 node-local hard cap으로 degrade하고
   always-on JDBC permit으로 DB 부하를 제한한 채 동일한 PostgreSQL transaction을 실행한다. Redis
   failure가 terminal reject를 만들지 않는다.
6. **Bloom false positive가 발생한다.** review/admission hint만 바뀌며 PostgreSQL eligibility와 entry
   ownership은 그대로 검증한다.
7. **batch revoke와 redemption이 경합한다.** 같은 campaign-first lock order와 revision commit 순서가
   단일 결과를 정하고 audit가 policy version과 loser outcome을 보존한다.
8. **reservation expiry worker가 중복 실행된다.** claim lease와 revision CAS가 한 worker만 전이하게
   한다. leader 부재 시 operator가 같은 bounded path를 실행한다.
9. **ciphertext/tag/key version이 손상 또는 누락된다.** global/live-reference key 누락은 startup 또는
   readiness DOWN, 격리 가능한 단일 row 손상은 quarantined `DEGRADED`다. 해당 allocation/reveal은
   fail closed하고 raw payload를 오류에 포함하지 않는다.
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
- generated chunk rollback/regeneration, committed replay와 10,000-entry finalization
- redemption/revoke, pause/allocate, expiry/reveal race
- worker duplicate claim, restart, reconciliation drift repair
- migration clean/existing DB, failed checksum, backup/restore
- global lock order, forced deadlock/timeout mapping, stale worker takeover와 batch expiry terminalization
- physical FK/check/partial index, counter delta와 negative/drift guard
- entry purge 뒤 duplicate-code reject, descriptor purge 뒤 same-key no-effect tombstone
- lease renewal/takeover boundary, quarantine detect/clear/terminalize와 shutdown cancel/rollback

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
- reveal security headers/storage-free browser behavior, replacement recovery와 accessibility smoke
- route matrix의 precondition/status/error/descriptor와 lifetime-limit=1 replacement retry/loss

### Stress/performance

- 10,000-entry batch, 64/128 virtual clients, same-user hotspot 50%, worker와 SSE 동시 부하에서 Redis
  healthy/unavailable profile을 각각 두 번 실행한다.
- latency는 report-only지만 active connection `<=16`, permit 합/동시 holder `<=16`, foreground permit
  wait `<=250 ms`, worker/SSE wait `<=1 s`, transaction deadline 준수, worker checkpoint progress,
  pending drain, connection/permit leak 0, winner/count invariant는 hard gate다. JSON metric snapshot과
  JFR 또는 thread dump artifact를 두 실행 모두 보존한다.
- allocation query와 operator pool-depth query는 representative cardinality에서 bounded index scan을
  확인한다.

## Documentation, diagram과 repository 등록

- module `README.md`, `README.ko.md`를 동등하게 작성한다.
- architecture와 contention/reconciliation sequence를 SVG source + rendered PNG로 제공한다.
- root/commerce README locale set과 repo `AGENTS.md` module map을 갱신한다.
- `settings.gradle.kts` 자동 등록 결과를 `./gradlew projects`로 확인한다.
- `.github/workflows/Examples.yml`의 sequential container lane에
  `:commerce-pre-generated-voucher-pool:test`와 test-result/Kover artifact glob을 등록한다.
- nightly commerce/full group, `scripts/smoke-validate.sh` data-access/full commerce expected-module check,
  stale-check, README locale parity, voucher runbook validator와 diagram QA를 갱신한다.
- workflow YAML syntax와 validator 입력을 local/CI에서 확인한다.
- Kover XML/Codecov visibility는 유지하되 hard threshold를 추가하지 않는다.
- module은 consumer example이며 publication/BOM artifact를 만들지 않는다.

## Acceptance criteria

- 서로 다른 concurrent allocator가 같은 entry를 예약하거나 할당하지 않는다.
- 같은 idempotent reservation command는 하나의 entry만 소비하고 allocation retry는 새 entry를
  소비하지 않는다.
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

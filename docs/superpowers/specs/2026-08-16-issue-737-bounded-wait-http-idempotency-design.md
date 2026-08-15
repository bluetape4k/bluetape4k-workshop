# Issue #737 Job Console bounded-wait HTTP idempotency 설계

- 날짜: 2026-08-16
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/737
- 작업 유형: Type A Full Feature
- 대상 브랜치: `feat/issue-737-bounded-wait-http-idempotency`
- 기준 브랜치: `develop`
- 기준 SHA: `8fc6cd375d20c3b448f7224d84d9cea3d5ae8080`
- 선택안: PostgreSQL 전역 상태 머신을 사용하는 권고안 A

## 1. 결정 요약

Job Console의 `POST /v1/jobs`에 여러 애플리케이션 인스턴스가 공유하는 bounded-wait HTTP idempotency를 적용한다. PostgreSQL의 기존 `job_requests`를 권위 상태로 확장하고, 같은 PostgreSQL schema에 waiter 등록을 보조하는 `job_request_waiters`를 추가한다. 최초 요청, 대기자, replay, fingerprint conflict, overflow, owner 중단, lease 복구가 모두 데이터베이스 트랜잭션과 compare-and-set 조건으로 수렴해야 한다.

Spring MVC와 Ktor는 같은 core coordinator를 사용한다. 두 adapter는 upstream `bluetape4k-junit5:1.12.1`의 `assertBoundedWaitHttpIdempotencyConformance`를 실제 HTTP 경계에서 실행한다. conformance 통과는 관측 가능한 HTTP 동작만 증명하며 durable persistence, restart recovery, 외부 부수 효과의 exactly-once를 주장하지 않는다. 이러한 workshop 고유 보장은 별도의 PostgreSQL 통합 테스트로 증명한다.

이 설계는 이슈의 "새 idempotency 저장소를 추가하지 않는다"는 제외 범위를 지킨다. 별도 제품이나 데이터 저장소를 도입하지 않고 기존 PostgreSQL `job_requests` 계약을 발전시키며, `job_request_waiters`는 그 상태 머신의 정규화된 보조 테이블이다. 사용자가 선택한 production `/v1/jobs` 적용 범위에 따라 이슈의 원래 test-only 범위보다 넓어진 동작은 이 문서에 명시적으로 고정한다.

## 2. 목표와 비목표

### 목표

1. 같은 scope, key, fingerprint의 동시 요청은 business execution 하나만 만들고 나머지는 제한된 수만 기다린다.
2. 최초 응답과 replay는 status, body, 허용된 header가 동일하고 `Idempotency-Replayed`만 전달 시점에 달라진다.
3. waiter 한도, timeout, cancellation, owner 중단과 프로세스 장애가 여러 인스턴스에서도 일관되게 정리된다.
4. job, outbox, history와 terminal HTTP snapshot을 한 PostgreSQL transaction에서 commit한다.
5. Spring MVC와 Ktor가 동일한 wire contract와 upstream conformance를 통과한다.
6. V001 데이터를 잃지 않는 additive migration과 명시적인 binary rollback 절차를 제공한다.

### 비목표

- caller가 수행하는 외부 시스템 부수 효과의 exactly-once 보장
- Redis, Kafka, 분산 lock 제품 또는 새 persistence 제품 도입
- 모든 endpoint를 위한 범용 idempotency framework 제작
- 처리량, 최대 동시성 또는 production capacity 수치 주장
- conformance fixture만으로 restart recovery나 durable persistence를 증명했다는 주장
- `POST /v1/jobs` 이외의 기존 Job Console command에 bounded-wait를 확대 적용

## 3. 선택한 구조와 대안

### 선택: PostgreSQL 전역 상태 머신

```text
Spring POST /v1/jobs                    Ktor POST /v1/jobs
          |                                      |
          +-------- Job submission boundary -----+
                              |
              BoundedWaitIdempotencyCoordinator
                  | reserve / wait / recover
                  | terminal snapshot transaction
                  v
        PostgreSQL job_requests + job_request_waiters
                  |
                  +-- jobs + job_outbox + job_history
```

coordinator는 framework-neutral core에 둔다. HTTP adapter는 인증/인가, 요청 크기와 key 문법 검증, wire 변환, cancellation 연결만 담당한다. 권위 분류와 waiter 수는 PostgreSQL에서 계산한다. polling은 짧은 transaction만 사용하며 owner의 business action이나 waiter 대기 동안 connection과 row lock을 보유하지 않는다.

### 거절한 대안 1: PostgreSQL correctness + 인스턴스 로컬 waiter 제한

replay와 conflict는 맞더라도 여러 인스턴스의 총 waiter 수를 제한할 수 없다. 한 인스턴스의 종료가 로컬 waiter bookkeeping을 잃으므로 전역 bounded-wait 계약을 만족하지 않는다.

### 거절한 대안 2: advisory lock을 잡은 bounded polling

요청 수명 동안 lock이나 connection을 보유하기 쉽고, 정확한 waiter count, cancellation cleanup, snapshot과 owner takeover를 명시적으로 모델링하기 어렵다. 기존 submit의 짧은 advisory lock은 새 상태 머신으로 대체한다.

### 거절한 대안 3: `LISTEN/NOTIFY` 기반 깨우기

notification 유실과 connection lifecycle 복구가 correctness 경로에 들어간다. V1은 bounded polling을 사용해 PostgreSQL row만으로 수렴한다. 추후 notification을 추가하더라도 최적화일 뿐 권위가 될 수 없다.

## 4. Core 경계와 책임

### BoundedWaitIdempotencyCoordinator

coordinator는 다음 입력을 받는다.

- 서버가 확정한 `tenantId`와 `submitterHash`
- 정규화·hash 전의 유효한 idempotency key
- canonical request fingerprint
- instance-scoped 정책 값
- owner일 때 실행할 transaction-aware action

owner action은 coordinator가 연 PostgreSQL transaction과 현재 owner token을 받는다. `/v1/jobs` action은 그 transaction에서 job, outbox, history를 기록하고 202 응답 snapshot을 만든다. coordinator는 같은 transaction에서 snapshot을 검증하고 `job_requests`를 `TERMINAL`로 바꾼다. snapshot 검증이 실패하면 전체 transaction을 rollback한다.

coordinator 결과는 HTTP adapter가 해석할 sealed outcome으로 제한한다.

| Outcome | 의미 |
|---|---|
| `OwnerCompleted` | 현재 요청이 business action과 terminal snapshot을 commit함 |
| `Replayed` | 보존 기간 안의 terminal snapshot을 반환함 |
| `Conflict` | 같은 scope/key의 다른 fingerprint |
| `InFlightTimeout` | 허용된 waiter였으나 deadline까지 terminal이 아님 |
| `WaiterOverflow` | 전역 active waiter 한도를 초과함 |
| `Abandoned` | owner의 transient failure를 현재 세대 요청에 전달함 |

production time은 PostgreSQL `CURRENT_TIMESTAMP`를 권위로 사용한다. 테스트는 같은 coordinator에 transaction-scoped virtual time source를 주입한다. 모든 비교 시각은 한 transaction에서 한 번 읽으며 서로 다른 application clock을 섞지 않는다.

### JobRepository 변화

현재 `JobRepository.submit`이 한 번에 수행하던 key lock, job 생성, idempotency row 삽입을 분리한다. job 생성 primitive는 coordinator가 제공한 기존 transaction을 사용하도록 내부 함수를 추출한다. 공개 service가 임의의 connection lifecycle을 소유하지 않으며, repository 밖에서 SQL을 복제하지 않는다.

`JobConsoleService.submit`은 `JobSnapshot`만 반환하지 않고 replay 여부와 안전한 HTTP snapshot을 보존할 수 있는 submission outcome을 반환한다. Spring/Ktor는 동일한 mapper를 사용해 status, body, content type과 replay header를 만든다.

## 5. PostgreSQL 상태 모델

### `job_requests` V002 확장

V001의 primary key `(tenant_id, submitter_hash, key_hash)`, `request_fingerprint`, `job_id`, `created_at`은 유지한다. V002는 additive column과 constraint만 추가한다.

| Column | 역할 |
|---|---|
| `state` | `IN_FLIGHT`, `TERMINAL`, `ABANDONED` |
| `owner_token` | 현재 owner 세대를 구분하는 UUID |
| `owner_lease_expires_at` | 장애 owner를 takeover할 수 있는 경계 |
| `response_status` | replay할 HTTP status |
| `response_body` | 검증을 통과한 bounded body |
| `response_content_type` | canonical content type |
| `response_headers` | allowlist를 통과한 canonical header JSON |
| `terminal_at` | terminal snapshot commit 시각 |
| `retained_until` | 같은 key를 terminal replay하는 정확한 경계 |
| `updated_at` | 상태 변경 시각 |

기존 행은 `TERMINAL`로 backfill한다. 기존 행에는 원래 HTTP bytes가 없으므로 replay 시 현재 job row에서 `JobSnapshot`을 한 번 만들고 안전성 검증 후 snapshot column을 채운다. 이 legacy lazy snapshot은 역사적으로 존재하지 않았던 byte-for-byte 보장을 소급 주장하지 않는다. 채운 뒤의 replay부터는 저장된 snapshot을 그대로 사용한다.

### `job_request_waiters`

| Column | 역할 |
|---|---|
| scope key columns | 대상 `job_requests` row 식별 |
| `waiter_token` | 요청별 UUID, composite primary key 일부 |
| `expires_at` | active waiter 판정과 유실 정리 경계 |
| `created_at` | 관측성과 진단용 등록 시각 |

foreign key는 `job_requests` composite primary key를 참조하고 삭제 시 함께 정리한다. active waiter 수는 `expires_at > now`인 row만 센다. admission transaction은 먼저 만료 waiter를 삭제하고 row lock 아래에서 count와 insert를 수행한다. waiter는 성공, timeout, cancellation, overflow 이외의 failure에도 `finally`에서 자기 token을 삭제한다.

### 상태 불변식

1. scope/key당 `IN_FLIGHT` owner token은 하나뿐이다.
2. `TERMINAL`은 안전성 검증을 통과한 snapshot과 `retained_until`을 가진다.
3. stale owner token은 job 생성과 terminal 전환을 commit할 수 없다.
4. active waiter 수는 instance-local 값이 아니라 PostgreSQL row 수다.
5. `ABANDONED` snapshot은 현재 세대에만 전달하며 retention replay 대상이 아니다.
6. job, 최초 outbox, 최초 history, terminal snapshot은 모두 commit되거나 모두 rollback한다.

## 6. 요청 알고리즘

### 사전 검증

HTTP adapter는 idempotency lookup 전에 인증/인가와 scope 확정을 끝낸다. 요청 body 제한과 JSON parsing도 lookup 전에 끝낸다. fingerprint는 parsed `SubmitJobRequest`의 `jobType`, `workUnits`, `failureMode`를 canonical 순서로 직렬화해 SHA-256으로 만든다. JSON field 순서와 무의미한 whitespace는 fingerprint를 바꾸지 않는다.

key는 정확히 하나여야 하며 printable ASCII이고 UTF-8 기준 최대 255 bytes다. scope와 raw key를 조합한 값의 SHA-256만 저장한다. raw key, fingerprint 원문, request/response body와 header를 log에 남기지 않는다.

### 예약 transaction

1. scope/key row가 없으면 UUIDv7 `job_id`와 owner token을 미리 만들고 `IN_FLIGHT` row를 insert한다.
2. 같은 primary key insert 경쟁은 기존 row를 `FOR UPDATE`로 읽어 하나의 분류 결과로 수렴한다.
3. fingerprint가 다르고 retention 또는 현재 generation이 유효하면 `Conflict`다.
4. `TERMINAL`이고 `now < retained_until`이면 snapshot을 `Replayed`로 반환한다.
5. `TERMINAL`이고 `now >= retained_until`이면 정확히 한 요청만 새 `job_id`, fingerprint, owner token의 `IN_FLIGHT`로 compare-and-set 전환한다.
6. `IN_FLIGHT`의 lease가 유효하고 fingerprint가 같으면 만료 waiter를 정리한 뒤 한도 안에서 waiter를 등록한다. 한도에 도달했으면 row를 추가하지 않고 `WaiterOverflow`를 반환한다.
7. `IN_FLIGHT` lease가 만료되면 owner token compare-and-set으로 정확히 한 요청을 새 owner로 승격한다. 기존 waiter가 polling 중이면 자기 waiter token을 제거하면서 takeover를 시도할 수 있다.
8. `ABANDONED`는 현재 waiter가 모두 drain될 때까지 transient snapshot을 반환한다. active waiter가 0이면 정확히 한 다음 요청이 새 generation owner가 된다.

### Owner 완료

owner business action은 reservation transaction과 분리해 실행하되 terminal commit transaction에서 현재 token과 lease를 다시 검증한다. `/v1/jobs`는 job, outbox, history와 202 snapshot을 같은 transaction에 기록한다. 응답 전송 전에 commit이 끝났다면 client disconnect 이후 재시도는 terminal snapshot을 replay한다.

owner의 결정적 4xx/5xx 결과는 안전한 snapshot이면 `TERMINAL`로 보존할 수 있다. transient dependency failure나 request cancellation은 `ABANDONED`로 전환하고 현재 owner와 waiter에게 503을 전달한다. stale owner가 늦게 완료해도 token 조건이 맞지 않아 commit하지 못한다.

### Waiter 대기

waiter는 connection을 점유하지 않고 bounded interval로 row를 읽는다. 각 poll은 짧은 transaction이다. terminal이면 snapshot을 replay하고, abandoned이면 transient 응답을 받고, lease expiry면 takeover를 경쟁하며, deadline에 도달하면 409를 반환한다. 정확히 deadline인 `now == deadline`은 timeout이다. 모든 종료 경로에서 waiter token을 삭제한다.

## 7. HTTP 계약

### Production `POST /v1/jobs`

| 상황 | Status | Problem code / body | Header |
|---|---:|---|---|
| 최초 owner 성공 | 202 | 저장된 `JobSnapshot` | `Idempotency-Replayed: false` |
| terminal replay | 저장된 status | 저장된 body | 저장된 허용 header + `Idempotency-Replayed: true` |
| fingerprint conflict | 409 | `idempotency_key_reused` | 없음 |
| waiter timeout | 409 | `idempotency_in_flight` | `Retry-After` |
| waiter overflow | 429 | `idempotency_waiters_exceeded` | `Retry-After` |
| key 형식/개수 오류 | 400 | `invalid_idempotency_request` | 없음 |
| request body 초과 | 413 | `idempotency_request_too_large` | 없음 |
| 안전하지 않은 snapshot | 500 | `idempotency_snapshot_rejected` | 없음 |
| transient owner failure | 503 | dependency-safe problem | replay하지 않음 |

`Idempotency-Replayed`는 snapshot에 저장하지 않고 전달 시점에 주입한다. 최초 owner와 같은 terminal 결과를 기다린 waiter는 이미 수행된 결과를 받으므로 `true`다.

초기 workshop profile 정책은 upstream fixture의 대표 값과 일치시킨다.

| 정책 | 값 |
|---|---:|
| wait timeout | 2초 |
| active waiter 한도 | key당 2 |
| terminal retention | 1시간 |
| timeout `Retry-After` | 1초 |
| overflow `Retry-After` | 2초 |
| request/replay body 최대 | 64 KiB |
| key 최대 | 255 bytes |
| replay header 이름 최대 | 8 |
| header별 value 최대 | 4 |
| header value 최대 | 8 KiB |
| replay header 총합 최대 | 16 KiB |

모든 값은 하나의 immutable, instance-scoped 정책 객체로 검증한다. conformance runner의 최대 지원값은 key당 waiter 32이며 그보다 큰 운영값은 이 이슈에서 사용하지 않는다.

### Test-only conformance endpoint

upstream fixture는 201, 422, 503 같은 synthetic outcome을 제어해야 한다. Spring/Ktor test application은 같은 production coordinator를 호출하는 test-only endpoint를 제공한다. endpoint는 test profile의 test source에만 존재하고 일반 demo/application classpath와 문서화된 public API에는 노출하지 않는다. adapter의 gate, virtual clock, side-effect counter와 reset 기능도 test fixture가 소유한다.

## 8. Replay snapshot 안전성

snapshot은 domain DTO를 다시 계산하는 identity가 아니라 실제 HTTP response의 안전한 표현이다. 저장 항목은 status, body, canonical content type, allowlist를 통과한 header뿐이다.

항상 금지하는 header에는 authentication/authorization, cookie/set-cookie, proxy authentication, connection/keep-alive/transfer-encoding/upgrade 같은 hop-by-hop header, `x-api-key`와 credential/secret/token/API key 패턴이 포함된다. 허용된 이름이어도 개수와 bytes 제한을 모두 통과해야 한다. unsafe snapshot은 저장하지 않고 owner business transaction 전체를 rollback한다.

JSON snapshot은 동일 mapper 설정과 UTF-8 bytes로 한 번 직렬화한다. replay 때 domain object를 다시 조회하거나 재직렬화하지 않는다. `Content-Type`은 별도 canonical column으로 보존하고 추가 header allowlist는 빈 집합에서 시작한다.

## 9. 취소·장애·복구

| Failure | 처리 |
|---|---|
| waiter HTTP disconnect | coroutine cancellation 또는 thread interruption을 보존하고 waiter row 삭제 |
| owner disconnect, commit 전 | owner action을 취소하고 `ABANDONED`; current generation은 503 |
| owner disconnect, commit 후 | terminal snapshot 유지; 다음 요청은 replay |
| owner process crash | lease 만료 후 waiter 또는 다음 요청이 CAS takeover |
| stale owner 완료 | owner token 불일치로 business/terminal commit 거절 |
| PostgreSQL unavailable | 503, readiness DOWN; local fallback 금지 |
| snapshot 검증 실패 | job/outbox/history/snapshot transaction rollback, 500 |
| retention 정확한 경계 | `now >= retained_until`에서 한 요청만 새 generation owner |
| waiter cleanup 실패 | expiry 기반 후속 정리와 metric; 응답은 원래 결과 유지 |

Spring의 blocking JDBC 대기는 caller-owned virtual-thread executor에서 interruptible하게 실행한다. Ktor는 coroutine cancellation을 broad exception mapping 전에 다시 던지고 blocking JDBC 호출을 `Dispatchers.IO`에서 실행한다. application shutdown은 신규 admission을 막고 active owner/waiter를 bounded drain한 뒤 취소한다.

## 10. 보안과 관측성

인가되지 않은 요청은 존재하는 idempotency row, conflict, waiter 수를 구분할 수 없어야 한다. 인증/인가와 scope validation을 먼저 수행하고 그 뒤에만 hash와 lookup을 수행한다.

metric과 structured log의 outcome은 `owner`, `replay`, `conflict`, `timeout`, `overflow`, `abandon`, `recovery`로 제한한다. 다음 항목을 관측한다.

- active waiter gauge와 expired waiter cleanup count
- waiter duration과 outcome count
- owner lease expiry와 takeover count
- snapshot rejection count
- PostgreSQL readiness와 coordinator failure count

metric label과 log field에 tenant, submitter, raw/hashed key, fingerprint, job ID, body, response snapshot을 넣지 않는다. 예제는 throughput, capacity 또는 exactly-once를 주장하지 않는다.

## 11. Migration과 rollback

`V001__job_console.sql`은 수정하지 않는다. `V002__bounded_wait_http_idempotency.sql`을 추가하고 Spring, Ktor, core database fixture, high-contention fixture의 ordered migration 목록에 V001 다음으로 등록한다. `JobMigrationRunner`의 checksum 추적과 advisory lock을 그대로 사용한다.

V002는 다음 조건을 만족해야 한다.

1. 기존 V001 row를 삭제하거나 primary key를 바꾸지 않는다.
2. 새 column은 backfill/default 또는 nullable transition을 사용해 기존 row를 허용한다.
3. legacy row는 `TERMINAL`로 읽고 lazy snapshot 경로로 전환한다.
4. V002를 적용한 schema에서 구 binary가 기존 방식의 terminal row를 insert할 수 있도록 기존 required column contract를 유지한다.
5. migration 재실행과 checksum mismatch failure를 테스트한다.

schema rollback은 하지 않는다. binary rollback 전에는 traffic을 차단하고 `IN_FLIGHT` row를 drain하거나 `ABANDONED`로 전환한 뒤 active waiter가 0인지 확인한다. 구 binary는 `IN_FLIGHT`의 preallocated `job_id`에 아직 `jobs` row가 없는 상태를 이해하지 못하므로 이 drain gate 없이 rollback할 수 없다. additive V002 table과 column은 그대로 남긴다.

## 12. 테스트 전략

### Core 단위 테스트

- owner/replay/conflict 상태 분류
- waiter admission, overflow, timeout의 정확한 경계
- cancellation `finally` cleanup과 expired waiter 청소
- `ABANDONED` drain 후 새 generation
- lease expiry takeover와 stale owner 거절
- retention 직전/정확히 경계/직후 경쟁
- fingerprint canonicalization과 key/body 제한
- snapshot body/header allowlist·denylist·총 bytes 제한
- transient failure 비보존과 deterministic terminal failure replay

### PostgreSQL 통합 테스트

- 서로 다른 coordinator 인스턴스와 datasource가 공유하는 전역 waiter 한도
- 동시에 여러 key를 처리할 때 key 간 격리
- job, outbox, history, terminal snapshot의 atomic commit/rollback
- owner crash simulation, CAS takeover, stale owner commit 거절
- waiter cancellation 후 database row quiescence
- V001 fixture 데이터의 V002 migration과 legacy lazy snapshot
- V002 checksum, 재시작, additive schema와 rollback drain precondition
- 기존 UUIDv7, lifecycle, heartbeat, queue, high-contention contract 회귀 없음

### Spring/Ktor live HTTP 통합 테스트

각 adapter는 공유 core test fixture를 사용하되 실제 framework HTTP server를 통과한다.

1. test-only endpoint로 `assertBoundedWaitHttpIdempotencyConformance` 전체 실행
2. production `/v1/jobs`의 202 first/replay와 stable status/body/header 검증
3. duplicate job/outbox/history가 없음을 PostgreSQL에서 확인
4. client cancellation, owner response delivery hold, application shutdown 후 quiescence 확인
5. Spring virtual thread와 Ktor coroutine lifecycle 종료 확인

upstream fixture가 검증하는 개별 HTTP assertion을 Spring/Ktor에 복제하지 않는다. workshop 고유 PostgreSQL durability, migration, restart, production endpoint mapping만 별도 assertion으로 유지한다. Testcontainers와 실제 DB 테스트는 `TestMutexService` 아래 순차 실행하며 다른 module 또는 agent와 병렬 실행하지 않는다.

### 검증 명령

```bash
./gradlew :operations-job-console-core:test
./gradlew :operations-job-console-core:integrationTest
./gradlew :operations-job-console-spring:test
./gradlew :operations-job-console-spring:integrationTest
./gradlew :operations-job-console-ktor:test
./gradlew :operations-job-console-ktor:integrationTest
./gradlew detekt
./gradlew :operations-job-console-core:build \
  :operations-job-console-spring:build \
  :operations-job-console-ktor:build
```

## 13. 문서와 diagram

다음 문서를 source-equivalent하게 갱신한다.

- `operations/job-console-core/README.md`, `README.ko.md`
- `operations/job-console-spring/README.md`, `README.ko.md`
- `operations/job-console-ktor/README.md`, `README.ko.md`

문서는 first/replay/conflict/timeout/overflow/abandon/recovery, 설정값, PostgreSQL 권위, binary rollback drain gate, exactly-once 비보장을 설명한다. test-only endpoint는 public 사용법으로 안내하지 않는다.

bounded-wait idempotency 전용 sequence/state diagram을 영문과 한글로 각각 제공한다. SVG를 canonical source로 하고 scale 2 PNG를 함께 생성한다. diagram 변경 단계에서 `$bluetape-diagram`을 적용하고 XML, text hazard, connector/geometry/endpoint/mixed-corner, sequence style와 full-size PNG 육안 검사를 통과한다.

새 module이나 dependency는 추가하지 않는다. 현재 smoke/full/nightly workflow와 stale-check가 기존 `test`/`integrationTest` task를 포함하는지 감사한다. task 이름이나 module 목록이 바뀌지 않으면 workflow를 불필요하게 수정하지 않는다.

## 14. 예상 변경 지점

| 영역 | 예상 변경 |
|---|---|
| core API/domain | problem code, submission outcome, replay snapshot과 policy 모델 |
| core persistence | V002 migration, request/waiter repository, transaction-aware job creation |
| core application | bounded-wait coordinator와 service submission boundary |
| core test fixtures | upstream adapter 공통 기반, PostgreSQL 다중 instance fixture |
| Spring | V002 등록, response mapping, cancellation 연결, conformance host/test |
| Ktor | V002 등록, response mapping, cancellation 연결, conformance host/test |
| documentation | 3개 module의 영문/한글 README와 bilingual diagram |
| CI/validation | 기존 matrix 포함 여부 감사, 필요한 경우에만 변경 |

정확한 파일 목록과 단계별 테스트 순서는 이 설계가 승인된 뒤 `$writing-plans`에서 확정한다.

## 15. 위험과 완화

| 위험 | 완화 |
|---|---|
| long transaction/connection 고갈 | reservation/poll/finalize를 짧은 transaction으로 분리 |
| multi-instance waiter cap 초과 | row lock 아래 expired cleanup + count + insert |
| stale owner 중복 commit | 모든 finalize SQL에 owner token과 state 조건 적용 |
| response snapshot에 credential 저장 | denylist, allowlist, bytes 제한, transaction rollback |
| cancellation 누수 | framework cancellation 보존 + waiter `finally` 삭제 + expiry cleanup |
| legacy row의 원본 response 부재 | 한 번의 명시적 lazy snapshot, 소급 byte identity 비주장 |
| 구 binary rollback 실패 | traffic stop + IN_FLIGHT drain/abandon + waiter 0 gate |
| conformance 과신 | HTTP proof와 workshop durability proof를 테스트/문서에서 분리 |
| 문서 언어·diagram 불일치 | 영문/한글 source-equivalent 문서와 bilingual asset 검증 |

## 16. 완료 조건 (DoD)

- [ ] Spring/Ktor test host가 upstream bounded-wait conformance 전체를 순차적으로 통과한다.
- [ ] production `/v1/jobs`에서 first/replay/conflict/timeout/overflow/abandon 계약이 동일하다.
- [ ] 여러 coordinator 인스턴스에서 global waiter cap과 CAS takeover가 증명된다.
- [ ] job, outbox, history, terminal snapshot의 atomicity와 duplicate 부재가 증명된다.
- [ ] owner/waiter cancellation, deadline, lease expiry, retention 경계 후 quiescence가 증명된다.
- [ ] V001 데이터 무손실, legacy lazy snapshot, V002 checksum과 rollback drain gate가 검증된다.
- [ ] raw key, body, credential header가 저장·log·metric label에 노출되지 않는다.
- [ ] 기존 lifecycle, heartbeat, UUIDv7, high-contention contract가 회귀하지 않는다.
- [ ] core/Spring/Ktor unit·integration test, `detekt`, module build가 통과한다.
- [ ] 영문/한글 README와 bilingual diagram이 source-equivalent하고 diagram QA를 통과한다.
- [ ] 새 dependency, 개별 Bluetape BOM 또는 explicit Bluetape version을 추가하지 않는다.
- [ ] 한국어 PR 본문 끝에 `## DoD Status`를 두고 live metadata와 정확한 head/CI를 재확인한다.
- [ ] merge-ready 증거에서 멈추고 병합은 별도의 최신 승인 후에만 수행한다.

## 17. 설계 승인 기록

사용자는 다음 네 구간을 순차 승인했다.

1. PostgreSQL 전역 상태 머신과 production `/v1/jobs` 적용
2. `IN_FLIGHT`/`TERMINAL`/`ABANDONED`, waiter registration, polling과 lease 복구
3. replay snapshot, HTTP contract, 보안과 관측성
4. shared conformance adapter, additive V002 migration, bilingual documentation과 DoD

이 문서에 대한 독립 검토와 사용자 파일 승인이 끝나기 전에는 구현 코드를 변경하지 않는다.

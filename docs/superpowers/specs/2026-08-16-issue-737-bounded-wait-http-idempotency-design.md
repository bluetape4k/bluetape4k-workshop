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
            JobSubmissionIdempotencyCoordinator
                  | reserve / wait / recover
                  | terminal snapshot transaction
                  v
        PostgreSQL job_requests + job_request_waiters
                  |
                  +-- jobs + job_outbox + job_history
```

coordinator는 framework-neutral core의 Job Submission 전용 internal package에 둔다. generic type parameter나 다른 endpoint가 소비하는 범용 idempotency SPI로 공개하지 않는다. HTTP adapter는 scope 확정, 요청 크기와 key 문법 검증, wire 변환, cancellation 연결만 담당한다. 권위 분류와 waiter 수는 PostgreSQL에서 계산한다. polling은 짧은 transaction만 사용하며 owner 준비 작업이나 waiter 대기 동안 connection과 row lock을 보유하지 않는다.

### 거절한 대안 1: PostgreSQL correctness + 인스턴스 로컬 waiter 제한

replay와 conflict는 맞더라도 여러 인스턴스의 총 waiter 수를 제한할 수 없다. 한 인스턴스의 종료가 로컬 waiter bookkeeping을 잃으므로 전역 bounded-wait 계약을 만족하지 않는다.

### 거절한 대안 2: advisory lock을 잡은 bounded polling

요청 수명 동안 lock이나 connection을 보유하기 쉽고, 정확한 waiter count, cancellation cleanup, snapshot과 owner takeover를 명시적으로 모델링하기 어렵다. 기존 submit의 짧은 advisory lock은 새 상태 머신으로 대체한다.

### 거절한 대안 3: `LISTEN/NOTIFY` 기반 깨우기

notification 유실과 connection lifecycle 복구가 correctness 경로에 들어간다. V1은 bounded polling을 사용해 PostgreSQL row만으로 수렴한다. 추후 notification을 추가하더라도 최적화일 뿐 권위가 될 수 없다.

## 4. Core 경계와 책임

### JobSubmissionIdempotencyCoordinator

coordinator는 다음 입력을 받는다.

- 서버가 확정한 `tenantId`와 `submitterHash`
- 정규화·hash 전의 유효한 idempotency key
- canonical request fingerprint
- instance-scoped 정책 값
- owner일 때 실행할 transaction-aware action

owner 실행은 다음 세 단계로 고정한다.

1. **Reserve transaction:** request row를 분류하거나 `IN_FLIGHT` owner를 예약하고 즉시 commit한다.
2. **Prepare phase:** database connection과 row lock 없이 bounded owner 작업을 준비한다. test-only gate도 이 단계에만 존재한다.
3. **Finalize transaction:** 현재 owner token과 generation을 다시 검증한 뒤 job, outbox, history, 202 response snapshot을 한 transaction에서 기록하고 `TERMINAL`로 전환한다.

JDBC transaction 안에서는 suspend, sleep, network I/O 또는 외부 callback을 허용하지 않는다. snapshot 검증이나 owner CAS가 실패하면 finalize transaction 전체를 rollback한다. prepare가 실패하거나 취소되면 별도의 짧은 transaction으로 `ABANDONED`를 기록한다.

구현 경계는 다음 Job Submission 전용 모양을 따른다. 정확한 이름은 계획 단계에서 repository 관례에 맞춰 확정하되 책임을 합치거나 범용화하지 않는다.

```kotlin
internal interface JobSubmissionOwnerAction {
    fun prepare(ownership: JobSubmissionOwnership): PreparedJobSubmission

    fun commit(
        connection: Connection,
        ownership: JobSubmissionOwnership,
        prepared: PreparedJobSubmission,
    ): ReplayableJobSubmission
}

internal class JobSubmissionIdempotencyCoordinator {
    fun execute(
        command: JobSubmissionCommand,
        action: JobSubmissionOwnerAction,
    ): JobSubmissionOutcome
}
```

API는 blocking/interruptible contract다. Spring은 caller-owned virtual thread에서 실행하고 Ktor는 `runInterruptible(Dispatchers.IO)`로 연결한다. interruption과 coroutine cancellation은 broad exception mapping 전에 보존한다. owner/waiter token은 client가 제공할 수 없고 CSPRNG 기반 UUID로 생성하며 log에 남기지 않는다.

coordinator 결과는 HTTP adapter가 해석할 sealed outcome으로 제한한다.

| Outcome | 의미 |
|---|---|
| `OwnerCompleted` | 현재 요청이 job 기록과 terminal snapshot을 finalize함 |
| `Replayed` | 보존 기간 안의 terminal snapshot을 반환함 |
| `Conflict` | 같은 scope/key의 다른 fingerprint |
| `InFlightTimeout` | 허용된 waiter였으나 deadline까지 terminal이 아님 |
| `WaiterOverflow` | 전역 active waiter 한도를 초과함 |
| `Abandoned` | owner의 transient failure를 현재 세대 요청에 전달함 |

production time은 PostgreSQL `CURRENT_TIMESTAMP`를 권위로 사용한다. 테스트는 같은 coordinator에 transaction-scoped virtual time source와 interruptible wait strategy를 주입한다. 모든 비교 시각은 한 transaction에서 한 번 읽으며 서로 다른 application clock을 섞지 않는다.

### JobRepository 변화

현재 `JobRepository.submit`이 한 번에 수행하던 key lock, job 생성, idempotency row 삽입을 분리한다. job 생성 primitive는 coordinator가 제공한 finalize transaction을 사용하도록 내부 함수를 추출한다. service가 임의의 connection lifecycle을 소유하지 않으며, repository 밖에서 SQL을 복제하지 않는다. 모든 SQL은 prepared statement와 bound parameter만 사용한다.

`JobConsoleService.submit`은 `JobSnapshot`만 반환하지 않고 replay 여부와 안전한 HTTP snapshot을 보존할 수 있는 submission outcome을 반환한다. Spring/Ktor는 동일한 mapper를 사용해 status, body, content type과 replay header를 만든다. 이 예제 module의 service/repository class는 published library binary API가 아니므로 기존 signature의 binary compatibility를 주장하지 않는다. repository 전체 usage search로 모든 기존 caller와 fixture를 같은 change에서 이전하고 compile/test로 누락을 막는다. `Idempotency-Key`가 없는 제출은 이전과 마찬가지로 허용하지 않으며 새 400 problem contract로 일관되게 매핑한다.

## 5. PostgreSQL 상태 모델

### `job_requests` V002 확장

V001의 primary key `(tenant_id, submitter_hash, key_hash)`, `request_fingerprint`, `job_id`, `created_at`은 유지한다. V002는 additive column과 constraint만 추가한다.

| Column | 역할 |
|---|---|
| `state` | `IN_FLIGHT`, `TERMINAL`, `ABANDONED` |
| `generation` | retention expiry, abandon recovery마다 증가하는 세대 번호 |
| `owner_token` | 현재 owner 세대를 구분하는 UUID |
| `owner_lease_expires_at` | 장애 owner를 takeover할 수 있는 경계 |
| `response_status` | replay할 HTTP status |
| `response_body` | 검증을 통과한 bounded body |
| `response_content_type` | canonical content type |
| `response_headers` | allowlist를 통과한 canonical header JSON |
| `terminal_at` | terminal snapshot commit 시각 |
| `retained_until` | 같은 key를 terminal replay하는 정확한 경계 |
| `abandoned_until` | abandoned generation을 race 없이 정리하는 GC 경계 |
| `updated_at` | 상태 변경 시각 |

V002의 `state`는 `NOT NULL DEFAULT 'TERMINAL'`, `generation`은 `NOT NULL DEFAULT 1`, `updated_at`은 `NOT NULL DEFAULT CURRENT_TIMESTAMP`로 추가한다. 기존 행과 V002 적용 후 구 binary가 insert한 행은 `retained_until = created_at + 1 hour`로 해석한다. owner token/lease, response snapshot과 terminal 시각은 nullable transition column이며 state별 `CHECK`로 유효 조합을 제한한다.

기존 행에는 원래 HTTP bytes가 없으므로 replay 시 현재 job row에서 `JobSnapshot`을 한 번 만들고 안전성 검증 후 `(scope, generation, response_status IS NULL)` CAS로 snapshot column을 채운다. 경쟁 loser는 winner가 저장한 bytes를 다시 읽는다. 이 legacy lazy snapshot은 역사적으로 존재하지 않았던 byte-for-byte 보장을 소급 주장하지 않는다. job row가 없거나 snapshot이 안전하지 않으면 500 `idempotency_snapshot_rejected`를 반환하고 자동 재실행하거나 row를 삭제하지 않는다. 운영자가 orphan을 복구한 뒤 caller는 같은 key로 다시 시도한다.

### `job_request_waiters`

| Column | 역할 |
|---|---|
| scope key columns | 대상 `job_requests` row 식별 |
| `generation` | 등록 당시 owner generation |
| `waiter_token` | 요청별 UUID, composite primary key 일부 |
| `expires_at` | active waiter 판정과 유실 정리 경계 |
| `created_at` | 관측성과 진단용 등록 시각 |

foreign key는 `job_requests` composite primary key를 참조하고 삭제 시 함께 정리한다. waiter primary key는 scope, generation, waiter token을 포함한다. active waiter 수는 현재 generation이고 `expires_at > now`인 row만 센다. admission transaction은 먼저 해당 scope의 만료 waiter를 삭제하고 request row lock 아래에서 count와 insert를 수행한다. waiter는 성공, timeout, cancellation, overflow 이외의 failure에도 `finally`에서 자기 token을 삭제한다.

V002는 hot path와 cleanup을 위해 다음 index를 둔다.

- `(tenant_id, submitter_hash, key_hash, generation, expires_at)` waiter admission/poll index
- `(expires_at)` expired waiter batch cleanup index
- `(state, retained_until)` terminal retention cleanup index
- `(state, abandoned_until)` abandoned generation cleanup index

모든 `ABANDONED` CAS는 `abandoned_until = now + 1 minute`를 함께 기록한다. 이 값은 current waiter에게 transient response를 전달하는 안전 여유이며 새 generation election을 지연시키지 않는다. 새 generation은 active waiter가 0이면 즉시 CAS할 수 있다. 주기적 janitor는 한 transaction에서 최대 100개씩 만료 waiter를 삭제하고, waiter가 없으며 `retained_until <= now`인 `TERMINAL` 또는 `abandoned_until <= now`인 `ABANDONED` request를 최대 100개씩 삭제한다. 삭제는 request row lock과 generation 조건을 사용해 새 owner/admission과 경쟁해도 current generation을 지우지 않는다. 기본 주기는 1분이며 shutdown과 동시에 중지한다. request 처리 중 opportunistic cleanup은 유지하되 유일한 cleanup 경로로 의존하지 않는다.

### 상태 불변식

1. scope/key당 `IN_FLIGHT` owner token은 하나뿐이다.
2. 신규 `TERMINAL`은 안전성 검증을 통과한 snapshot과 `retained_until`을 가진다. legacy terminal만 최초 lazy CAS 전까지 snapshot이 nullable이다.
3. stale owner token은 job 생성과 terminal 전환을 commit할 수 없다.
4. active waiter 수는 instance-local 값이 아니라 PostgreSQL row 수다.
5. `ABANDONED` snapshot은 현재 세대에만 전달하며 retention replay 대상이 아니다.
6. job, 최초 outbox, 최초 history, terminal snapshot은 모두 commit되거나 모두 rollback한다.
7. generation이 바뀔 때 이전 generation의 active waiter는 0이어야 한다.
8. `ABANDONED`는 항상 `abandoned_until`을 가지며 current generation waiter가 있으면 삭제할 수 없다.

## 6. 요청 알고리즘

### 사전 검증

HTTP adapter는 request body를 소비하기 전에 profile-gated scope resolver를 실행하고 idempotency lookup 전에 scope 확정을 끝낸다. 현재 Job Console의 `demo` profile은 production authentication이 아니라 trusted-header fixture이므로 production authn 보장을 새로 주장하지 않는다. 다만 resolver가 반환한 immutable `tenantId`/`submitterHash`만 사용하고 body나 path에서 받은 scope/hash를 신뢰하지 않는다. 모든 lookup, foreign key, count와 CAS는 완전한 `(tenant_id, submitter_hash, key_hash)` tuple을 사용한다. profile이 비활성화되거나 scope가 유효하지 않으면 body parsing, hash, lookup과 waiter 등록 전에 거절한다.

request body는 streaming ingress limit으로 64 KiB를 넘기기 전에 중단하고 strict UTF-8 `application/json`만 허용한다. duplicate field, unknown field, trailing token, malformed escape와 `null` required field를 거절한다. parser depth와 scalar 길이는 body limit 안에서 bounded configuration으로 고정한다. 상충하는 framing은 framework/server가 거절하도록 live negative test로 확인한다.

fingerprint는 parsed `SubmitJobRequest`의 다음 canonical tuple을 length-prefix해 SHA-256으로 만든다.

```text
job-console-submit-v1 | jobType.wireValue | decimal(workUnits) | failureMode.wireValue
```

omitted `failureMode`는 `none`으로 정규화하며 explicit `"none"`과 동일하다. enum은 정의된 lowercase wire value만 허용하고 `workUnits`는 JSON integer 1..10,000만 허용한다. object field 순서와 whitespace는 무시하지만 array 순서가 있는 향후 field는 보존한다. key는 trim, case-folding 또는 Unicode normalization을 하지 않는다.

key hash는 문자열 연결이 아니라 domain tag와 각 tuple component의 byte length를 포함해 `SHA-256("job-console-key-v1", tenantId, submitterHash, rawKey)`로 만든다. 이 hash는 secret 보호용 HMAC가 아니라 scope-isolated identifier다. database 접근 제어를 신뢰 경계로 두며 raw key, fingerprint 원문, request/response body와 header를 log, metric, SQL trace에 남기지 않는다.

### 예약 transaction

1. scope/key row가 없으면 UUIDv7 `job_id`, CSPRNG owner token과 generation 1을 미리 만들고 `IN_FLIGHT` row를 insert한다.
2. 같은 primary key insert 경쟁은 기존 row를 `FOR UPDATE`로 읽어 하나의 분류 결과로 수렴한다.
3. fingerprint가 다르고 retention 또는 현재 generation이 유효하면 `Conflict`다.
4. `TERMINAL`이고 `now < retained_until`이면 snapshot을 `Replayed`로 반환한다.
5. `TERMINAL`이고 `now >= retained_until`이면 정확히 한 요청만 generation을 증가시키고 새 `job_id`, fingerprint, owner token의 `IN_FLIGHT`로 compare-and-set 전환한다. 이전 job row는 Job Console 보존 정책에 따라 유지한다.
6. `IN_FLIGHT`의 lease가 유효하고 fingerprint가 같으면 만료 waiter를 정리한 뒤 한도 안에서 waiter를 등록한다. 한도에 도달했으면 row를 추가하지 않고 `WaiterOverflow`를 반환한다.
7. `IN_FLIGHT` lease가 만료되면 owner token compare-and-set으로 정확히 한 요청을 같은 generation의 새 owner로 승격한다. 기존 waiter가 polling 중이면 자기 waiter token을 제거하면서 takeover를 시도할 수 있다. loser는 새 owner를 기다리는 waiter로 남는다.
8. `ABANDONED`는 현재 generation waiter가 모두 drain될 때까지 transient snapshot을 반환한다. active waiter가 0이면 정확히 한 다음 요청이 generation을 증가시키고 새 `job_id`와 owner token의 owner가 된다. race loser는 새 generation waiter admission을 다시 수행한다.

### Owner 완료

owner prepare는 reservation transaction과 finalize transaction 사이에서 connection 없이 실행한다. 기본 owner lease는 30초이고 prepare deadline은 10초다. prepare deadline은 waiter의 2초 wait timeout과 별개다. prepare가 lease보다 짧으므로 V1은 heartbeat/renewal을 사용하지 않는다. deadline 초과는 `ABANDONED`로 전환한다. crash recovery SLA 상한은 owner lease 30초 + 최대 poll interval 100ms + database acquisition bound다.

finalize transaction에서 현재 token, generation, state와 lease를 다시 검증한다. `/v1/jobs`는 job, outbox, history와 202 snapshot을 같은 transaction에 기록한다. 응답 전송 전에 commit이 끝났다면 client disconnect 이후 재시도는 terminal snapshot을 replay한다.

production `/v1/jobs`에서 저장하는 terminal snapshot은 성공한 202 submission뿐이다. validation은 owner 예약 전에 끝나므로 4xx는 저장하지 않는다. coordinator가 만든 conflict/timeout/overflow도 저장하지 않는다. test-only conformance endpoint만 synthetic 201과 deterministic 422를 terminal replay해 upstream 계약을 증명한다. transient dependency failure나 request cancellation은 `ABANDONED`로 전환하고 현재 owner와 waiter에게 503을 전달한다. stale owner가 늦게 완료해도 token 조건이 맞지 않아 commit하지 못한다.

owner cancellation cleanup은 Spring의 interruptible `finally`, Ktor의 bounded `NonCancellable` cleanup 구간에서 `ABANDONED` CAS를 한 번 시도한다. PostgreSQL 장애로 cleanup이 실패해도 lease expiry takeover가 최종 복구 경로다.

### Waiter 대기

waiter는 connection을 점유하지 않고 25ms에서 시작해 50ms, 최대 100ms까지 증가하는 interval로 row를 읽는다. waiter token에서 유도한 결정론적 ±20% jitter로 herd를 줄이고 다음 delay는 남은 deadline을 넘지 않는다. test profile은 실제 sleep 대신 virtual wait strategy를 사용한다. 각 poll은 connection acquire 250ms, statement 500ms 상한을 가진 짧은 transaction이며 오류는 local fallback 없이 503으로 매핑한다.

per-key 전역 한도 2와 별도로 한 instance의 active waiter는 32개로 제한한다. instance limit 거절도 429 `idempotency_waiters_exceeded`를 사용하며 database row를 만들지 않는다. terminal/abandoned를 먼저 관측하고, `now >= deadline`이면 takeover보다 timeout을 우선한다. 따라서 정확히 deadline은 409다. 모든 종료 경로에서 waiter token을 삭제한다.

두 adapter의 workshop datasource maximum pool size는 instance당 8로 고정한다. coordinator는 별도 fair semaphore로 동시에 database connection을 획득하는 idempotency reserve/poll/finalize/cleanup 작업을 4개로 제한해 worker, outbox와 read endpoint에 최소 절반의 pool 여유를 남긴다. connection permit을 250ms 안에 얻지 못한 신규 owner/waiter admission은 database row를 만들지 않고 429 `idempotency_waiters_exceeded`로 backpressure하며, 이미 등록된 waiter의 poll은 다음 interval까지 재시도하되 HTTP deadline을 넘지 않는다. connection을 사용하지 않는 owner prepare도 instance당 8개로 제한하고 permit 획득 전에는 owner row를 예약하지 않는다.

hot-path SQL budget은 O(1)로 고정한다. replay/conflict poll은 한 indexed read, waiter cleanup은 한 delete, admission은 request row lock과 cleanup/count/insert를 합쳐 최대 세 statement, finalize는 sequence/job/outbox/history/snapshot을 합쳐 최대 여덟 statement다. outcome별 statement count를 test datasource로 검증하고 key 수나 stale waiter 수에 비례하는 N+1을 허용하지 않는다. wall-clock throughput 합격값은 #522 범위로 남기되 32개 key fan-in에서 connection acquisition timeout이 없고 terminal commit 후 두 poll interval 안에 waiter가 수렴해야 한다.

## 7. HTTP 계약

### Production `POST /v1/jobs`

요청은 HTTP field name 비교 규칙상 case-insensitive인 `Idempotency-Key` field를 정확히 한 번 포함해야 한다. field가 없거나 두 번 이상 나타나거나 comma-joined multiple value이면 400이다. value는 trim/정규화하지 않으며 ASCII `0x21..0x7E`만 허용한다. 따라서 empty, whitespace-only, tab, non-ASCII와 255 bytes 초과는 400이다. `Content-Type`은 `application/json`이며 charset이 있으면 UTF-8만 허용한다.

| 상황 | Status | Problem code / body | Header |
|---|---:|---|---|
| 최초 owner 성공 | 202 | 저장된 `JobSnapshot` | `Content-Type: application/json`, `Idempotency-Replayed: false` |
| terminal replay | 저장된 status | 저장된 body | 저장된 허용 header + `Idempotency-Replayed: true` |
| fingerprint conflict | 409 | `idempotency_key_reused` | `Content-Type: application/problem+json` |
| waiter timeout | 409 | `idempotency_in_flight` | problem content type + `Retry-After: 1` |
| waiter overflow | 429 | `idempotency_waiters_exceeded` | problem content type + `Retry-After: 2` |
| key 형식/개수 오류 | 400 | `invalid_idempotency_request` | problem content type |
| request body 초과 | 413 | `idempotency_request_too_large` | problem content type |
| 안전하지 않은 snapshot | 500 | `idempotency_snapshot_rejected` | problem content type |
| transient owner failure | 503 | `dependency_unavailable` | problem content type, replay하지 않음 |

모든 problem body는 기존 `JobProblem`의 `{status, code, title, requestId, retryAfterSeconds}` JSON schema를 사용한다. `Retry-After`는 양의 decimal delta-seconds이며 `retryAfterSeconds`와 같은 값이다. `Idempotency-Replayed`는 lowercase `true`/`false` 한 값만 사용하고 snapshot에 저장하지 않으며 problem response에는 넣지 않는다. 최초 owner와 같은 terminal 결과를 기다린 waiter는 이미 수행된 결과를 받으므로 `true`다.

대표 요청과 응답은 다음과 같다. 실제 `jobId`, `requestId`, 시각은 fixture가 검증하는 값으로 바뀐다.

```http
POST /v1/jobs HTTP/1.1
Content-Type: application/json
Idempotency-Key: export-2026-08-16-001
X-Demo-Tenant: tenant-a
X-Demo-Submitter: submitter-hash

{"jobType":"document_export","workUnits":100,"failureMode":"none"}
```

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
Idempotency-Replayed: false

{"jobId":"0198af23-7b9c-7000-8000-000000000001","jobType":"document_export","state":"queued","progress":0,"checkpoint":null,"queue":{"position":1,"jobsAhead":0,"estimatedStartRange":null,"estimatedCompletionRange":null,"confidence":"insufficient_data","sampleSize":0,"queueVersion":1,"updatedAt":"2026-08-16T00:00:00Z"},"version":1,"updatedAt":"2026-08-16T00:00:00Z"}
```

같은 key와 canonical payload를 다시 보내면 status/body/content type은 같고 `Idempotency-Replayed: true`가 된다. 다른 payload면 다음 stable schema를 반환한다.

```json
{"status":409,"code":"idempotency_key_reused","title":"Conflict","requestId":"0198af23-7b9c-7000-8000-000000000002","retryAfterSeconds":null}
```

모든 coordinator problem은 다음 exact field matrix를 사용한다. `requestId`만 요청마다 새 UUIDv7 값이다. 모든 행의 content type은 `application/problem+json`이고 `Idempotency-Replayed`는 없다.

| Status | `code` | `title` | `retryAfterSeconds` | 추가 header |
|---:|---|---|---:|---|
| 400 | `invalid_idempotency_request` | `Bad Request` | `null` | 없음 |
| 413 | `idempotency_request_too_large` | `Payload Too Large` | `null` | 없음 |
| 409 | `idempotency_key_reused` | `Conflict` | `null` | 없음 |
| 409 | `idempotency_in_flight` | `Conflict` | `1` | `Retry-After: 1` |
| 429 | `idempotency_waiters_exceeded` | `Too Many Requests` | `2` | `Retry-After: 2` |
| 500 | `idempotency_snapshot_rejected` | `Internal Server Error` | `null` | 없음 |
| 503 | `dependency_unavailable` | `Service Unavailable` | `null` | 없음 |

예를 들어 timeout body는 다음과 같다.

```json
{"status":409,"code":"idempotency_in_flight","title":"Conflict","requestId":"0198af23-7b9c-7000-8000-000000000003","retryAfterSeconds":1}
```

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
| header value 최대 | 4 KiB |
| replay header 총합 최대 | 16 KiB |

모든 값은 하나의 immutable, instance-scoped 정책 객체로 검증한다. 누락이나 범위 밖 값은 startup/readiness failure이며 unbounded fallback을 사용하지 않는다. 각 instance는 secret을 포함하지 않은 policy fingerprint를 readiness detail로 제공하고 feature enable 전 smoke가 모든 instance의 동일성을 확인한다. 불일치하면 enable을 중단한다. conformance runner의 최대 지원값은 key당 waiter 32이며 그보다 큰 운영값은 이 이슈에서 사용하지 않는다. 이 값은 upstream이 추천한 production capacity가 아니라 workshop이 선택한 대표 fixture policy다.

### Caller 재시도 지침

| 관측 결과 | Caller 행동 |
|---|---|
| 202 수신 | `jobId`를 사용하고 같은 command를 다시 제출하지 않음 |
| 응답 유실/연결 종료 | 같은 key와 같은 canonical payload로 재시도 |
| 409 `idempotency_in_flight` | `Retry-After` 뒤 같은 key/payload로 재시도 |
| 429 `idempotency_waiters_exceeded` | `Retry-After` 뒤 같은 key/payload로 재시도; 새 key로 중복 작업을 만들지 않음 |
| 409 `idempotency_key_reused` | key를 잘못 재사용한 caller bug로 처리; 의도적으로 새 command일 때만 새 key 사용 |
| 503 `dependency_unavailable` | 결과가 commit되지 않았으므로 같은 key/payload로 재시도 |
| legacy snapshot 500 | 자동으로 새 key를 만들지 말고 운영 복구 뒤 같은 key로 재시도 |
| 400 `invalid_idempotency_request` | 자동 재시도하지 않음; header/JSON을 고친 뒤 의도한 command key로 다시 제출 |
| 413 `idempotency_request_too_large` | 자동 재시도하지 않음; body를 제한 안으로 고친 뒤 같은 command key로 다시 제출 |
| 500 `idempotency_snapshot_rejected` | 자동 재시도하지 않음; server/operator 수정 뒤 같은 key/payload로 재시도 |
| 기타 5xx 또는 응답 해석 실패 | 새 key를 만들지 말고 backoff 후 같은 key/payload로 재시도; commit됐다면 terminal replay로 수렴 |

bounded wait는 duplicate waiter가 기존 owner 결과를 기다리는 시간만 제한한다. 최초 owner prepare/finalize deadline과 background job 실행 시간에는 적용되지 않는다. retention 만료 뒤 같은 key를 재사용하면 새 generation과 새 job이 만들어지므로 caller는 key를 command identity로 보고 1시간 이후의 재사용에 의존하지 않는다.

### Terminal cache 정책

| 경계 | Terminal로 저장 | Replay header |
|---|---|---|
| production `/v1/jobs` owner 성공 | 202 `JobSnapshot`만 저장 | owner `false`, waiter/후속 replay `true` |
| test-only conformance 결정적 성공 | synthetic 201 저장 | owner `false`, waiter/후속 replay `true` |
| test-only conformance 결정적 실패 | synthetic 422 저장 | owner `false`, waiter/후속 replay `true` |
| ingress 400/413, conflict/timeout 409, overflow 429 | 저장하지 않음 | header 없음 |
| snapshot rejection 500, transient 503, 기타 non-terminal failure | 저장하지 않고 `ABANDONED` 또는 fail-closed 복구 | header 없음 |

따라서 test-only 422 replay는 production이 4xx/5xx를 cache한다는 뜻이 아니다. 같은 production owner가 commit한 202 bytes만 durable replay 대상이다.

### Test-only conformance endpoint

upstream fixture는 201, 422, 503 같은 synthetic outcome을 제어해야 한다. Spring/Ktor test application은 같은 production coordinator를 호출하는 test-only endpoint를 제공한다. endpoint는 test profile의 test source에만 존재하고 일반 demo/application classpath와 문서화된 public API에는 노출하지 않는다. adapter의 gate, virtual clock, side-effect counter와 reset 기능도 test fixture가 소유한다.

## 8. Replay snapshot 안전성

snapshot은 domain DTO를 다시 계산하는 identity가 아니라 실제 HTTP response의 안전한 표현이다. production snapshot body는 redacted `JobSnapshot` JSON, test-only snapshot body는 고정된 synthetic JSON/problem schema만 허용한다. content type allowlist는 `application/json`과 `application/problem+json`의 UTF-8 표현뿐이다. 저장 항목은 status, body, canonical content type, allowlist를 통과한 header뿐이며 database encryption이나 secret 보관소를 대체하지 않는다.

항상 금지하는 header에는 authentication/authorization, cookie/set-cookie, proxy authentication, connection/keep-alive/transfer-encoding/upgrade 같은 hop-by-hop header, `content-length`, `idempotency-replayed`, `retry-after`, `x-api-key`와 credential/secret/token/API key 패턴이 포함된다. header name은 lowercase HTTP token으로 canonicalize하며 case-insensitive duplicate를 거절한다. value에 CR, LF, NUL 또는 다른 제어 문자가 있으면 거절한다. 허용된 이름이어도 개수와 bytes 제한을 모두 통과해야 한다. unsafe snapshot은 저장하지 않고 owner finalize transaction 전체를 rollback한다.

JSON snapshot은 strict, non-polymorphic mapper 설정과 UTF-8 bytes로 한 번 직렬화한다. header JSON은 string-to-string-list의 flat schema만 허용하고 type metadata, unknown shape와 invalid encoding을 거절한다. replay 때 domain object를 다시 조회하거나 재직렬화하지 않는다. `Content-Type`은 별도 canonical column으로 보존하고 추가 replay header allowlist는 빈 집합에서 시작한다. schema에는 body/content type 길이와 response status range를 검증하는 `CHECK`를 둔다.

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

Spring의 blocking JDBC 대기는 caller-owned virtual-thread executor에서 interruptible하게 실행한다. Ktor는 coroutine cancellation을 broad exception mapping 전에 다시 던지고 blocking JDBC 호출을 `runInterruptible(Dispatchers.IO)`에서 실행한다. application shutdown은 readiness를 먼저 닫고 신규 admission을 막은 뒤 최대 5초간 active owner/waiter를 drain한다. 남은 작업은 취소하고 owner는 `ABANDONED` CAS를 시도하며 실패하면 30초 lease expiry 복구에 맡긴다. liveness는 process event loop가 살아 있는지만 나타내고 PostgreSQL, migration, invalid local policy는 readiness만 DOWN으로 만든다.

## 10. 보안과 관측성

인가되지 않은 요청은 존재하는 idempotency row, conflict, waiter 수를 구분할 수 없어야 한다. 인증/인가와 scope validation을 먼저 수행하고 그 뒤에만 hash와 lookup을 수행한다.

metric과 structured log의 outcome은 `owner`, `replay`, `conflict`, `timeout`, `overflow`, `abandon`, `recovery`로 제한한다. 구현할 low-cardinality metric 이름은 다음과 같다.

- `job_console_idempotency_requests_total{outcome}`
- `job_console_idempotency_active_waiters{scope=instance|database}`
- `job_console_idempotency_wait_seconds{outcome}`
- `job_console_idempotency_expired_rows_cleaned_total{kind=waiter|request}`
- `job_console_idempotency_owner_recoveries_total`
- `job_console_idempotency_snapshot_rejections_total`
- `job_console_idempotency_cleanup_backlog{kind=waiter|request}`
- `job_console_idempotency_ready{reason=postgres|migration|policy}`

metric label과 log field에 tenant, submitter, raw/hashed key, fingerprint, job ID, body, response snapshot을 넣지 않는다. request correlation에는 기존 redacted `requestId`만 사용한다. README 운영 표는 readiness reason, overflow/timeout 증가, cleanup backlog, owner recovery를 진단 신호와 복구 절차에 연결한다. workshop에는 alert manager나 dashboard 배포가 없으므로 production threshold와 on-call ownership을 주장하지 않는다. 예제는 throughput, capacity 또는 exactly-once를 주장하지 않는다.

## 11. Migration과 rollback

`V001__job_console.sql`은 수정하지 않는다. `V002__bounded_wait_http_idempotency.sql`을 추가하고 Spring, Ktor, core database fixture, high-contention fixture의 ordered migration 목록에 V001 다음으로 등록한다. `JobMigrationRunner`의 checksum 추적과 advisory lock을 그대로 사용한다. migration은 lock/statement timeout을 설정하고 preflight row count와 schema checksum을 기록한다. workshop table에 대한 단일 bounded backfill이며 lock timeout이나 constraint 검증 실패 시 binary 시작과 readiness를 중단한다. 범용 batch migration framework는 추가하지 않는다.

V002는 다음 조건을 만족해야 한다.

1. 기존 V001 row를 삭제하거나 primary key를 바꾸지 않는다.
2. 새 column은 backfill/default 또는 nullable transition을 사용해 기존 row를 허용한다.
3. legacy row는 `TERMINAL`로 읽고 lazy snapshot 경로로 전환한다.
4. V002를 적용한 schema에서 구 binary가 기존 방식의 terminal row를 insert할 수 있도록 기존 required column contract를 유지한다.
5. migration 재실행과 checksum mismatch failure를 테스트한다.

### Expand/deploy 순서

1. V001 schema와 checksum, row count를 preflight한다.
2. V002를 적용하고 constraint/index/checksum을 검증한다. 이 단계에서는 구 binary가 계속 legacy terminal row를 쓸 수 있다.
3. 새 binary를 `bounded-wait.enabled=false`로 모든 instance에 배포한다. disabled path는 V001-compatible terminal insert를 유지한다.
4. 모든 instance의 binary와 policy fingerprint가 같은지 확인한 뒤 feature를 활성화한다.
5. Spring/Ktor first/replay smoke와 readiness를 확인한다. 실패하면 feature를 다시 비활성화하고 신규 `IN_FLIGHT` 생성을 중지한다.

구 binary와 bounded-wait enabled 새 binary의 동시 traffic은 지원하지 않는다. 구 binary가 preallocated `job_id`만 있는 `IN_FLIGHT` row를 terminal job으로 오해하기 때문이다. 이 compatibility matrix와 feature gate를 integration test와 README rollback note에 고정한다.

### Binary rollback runbook

1. readiness를 닫고 신규 submit admission을 중지한다.
2. 최대 owner lease 30초 + 5초 동안 drain한다.
3. 다음 query 의미를 repository diagnostic으로 제공해 `IN_FLIGHT=0`, active waiter=0을 확인한다. raw scope/key는 출력하지 않는다.
4. timeout 뒤 남은 owner는 `ABANDONED` CAS하고 waiter가 0이 될 때까지 다시 확인한다. PostgreSQL 장애나 잔여 row가 있으면 rollback을 중단한다.
5. bounded-wait feature를 비활성화한 새 binary로 smoke한 뒤에만 구 binary를 배포한다.

schema rollback은 하지 않고 additive V002 table과 column을 그대로 남긴다. 배포·rollback 실행 주체는 이 workshop repository의 PR operator이며, 실제 배포 시스템이나 on-call 조직의 존재는 주장하지 않는다.

## 12. 테스트 전략

### Core 단위 테스트

- owner/replay/conflict 상태 분류
- waiter admission, overflow, timeout의 정확한 경계
- cancellation `finally` cleanup과 expired waiter 청소
- `ABANDONED` drain 후 새 generation
- lease expiry takeover와 stale owner 거절
- retention 직전/정확히 경계/직후 경쟁
- fingerprint canonicalization과 key/body 제한
- cross-tenant/cross-submitter/forged scope와 unauthenticated oversized-body precedence
- duplicate/missing/whitespace/non-ASCII key, duplicate/unknown/deep/malformed JSON
- snapshot body/header allowlist·denylist·총 bytes 제한
- CRLF/control character, case-insensitive duplicate/reserved header와 malicious flat-JSON shape
- transient failure 비보존과 deterministic terminal failure replay
- local policy 누락/범위 오류 startup failure, deployment policy fingerprint 동일성과 CSPRNG token 비재사용

### PostgreSQL 통합 테스트

- 서로 다른 coordinator 인스턴스와 datasource가 공유하는 전역 waiter 한도
- 동시에 여러 key를 처리할 때 key 간 격리
- job, outbox, history, terminal snapshot의 atomic commit/rollback
- owner crash simulation, CAS takeover, stale owner commit 거절
- 10초 prepare deadline, 30초 lease와 exact expiry ordering
- waiter cancellation 후 database row quiescence
- V001 fixture 데이터의 V002 migration과 legacy lazy snapshot
- legacy lazy snapshot concurrent CAS, orphan/unsafe row의 fail-closed 처리
- V002 checksum, 재시작, additive schema와 rollback drain precondition
- expand/disabled deploy/enabled mixed-version matrix와 rollback diagnostic
- waiter/retention index의 `EXPLAIN (ANALYZE, BUFFERS)` index plan과 batch 100 cleanup
- outcome별 statement budget, pool size 8/coordinator DB concurrency 4/owner prepare 8 saturation, 32-key fan-in의 pool acquisition과 두 poll interval 수렴
- `ABANDONED` 1분 GC와 current-generation waiter/new-owner race-safe deletion
- 기존 UUIDv7, lifecycle, heartbeat, queue, high-contention contract 회귀 없음

### Spring/Ktor live HTTP 통합 테스트

각 adapter는 공유 core test fixture를 사용하되 실제 framework HTTP server를 통과한다.

1. test-only endpoint로 `assertBoundedWaitHttpIdempotencyConformance` 전체 실행
2. production `/v1/jobs`의 202 first/replay와 stable status/body/header 검증
3. duplicate job/outbox/history가 없음을 PostgreSQL에서 확인
4. client cancellation, owner response delivery hold, application shutdown 후 quiescence 확인
5. Spring virtual thread와 Ktor coroutine lifecycle 종료 확인
6. production profile에서 test-only endpoint가 등록되지 않음을 404와 bean/route 부재로 확인
7. exact `JobProblem` schema, header multiplicity, `Retry-After` delta-seconds와 request precedence 확인

upstream fixture가 검증하는 개별 HTTP assertion을 Spring/Ktor에 복제하지 않는다. workshop 고유 PostgreSQL durability, migration, restart, production endpoint mapping만 별도 assertion으로 유지한다. Testcontainers와 실제 DB 테스트는 `TestMutexService` 아래 순차 실행하며 다른 module 또는 agent와 병렬 실행하지 않는다.

dependency는 root의 `platform(libs.bluetape4k.dependencies)`만 사용한다. 2026-08-16 dependency insight에서 `bluetape4k-dependencies:1.4.0`이 `bluetape4k-junit5:1.12.1`을 해석하고 target conformance API를 포함함을 확인했다. `libs.versions.toml`에 module version을 pin하거나 개별 BOM을 추가하지 않는다. API가 사라진 catalog 조합에는 local fallback fixture를 복제하지 않고 dependency resolution/compile failure로 중단한다.

기존 full/nightly workflow에서 core/Spring/Ktor `integrationTest`가 실제 실행되는지 구현 diff 전에 감사한다. PR 필수 증거는 세 module의 unit/integration 결과, migration/mixed-version test, `detekt`, module build다. 현재 workflow가 하나라도 누락하면 같은 branch에서 matrix를 갱신하고 summary `needs`까지 검증한다. branch protection과 외부 release 승인은 repository 외부 정책이므로 PR delivery 단계에서 live 확인하며 이 설계가 변경하지 않는다.

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

문서는 first/replay/conflict/timeout/overflow/abandon/recovery의 검증된 request/response 예제, caller 재시도 decision table, 설정값, PostgreSQL 권위, legacy replay 차이, binary rollback drain gate, exactly-once 비보장을 설명한다. 보장은 committed PostgreSQL job/outbox/history와 저장된 HTTP snapshot에 한정하며 background job이나 caller 외부 side effect의 exactly-once가 아님을 각 README의 계약 표 바로 아래에 표시한다. test-only endpoint는 public 사용법으로 안내하지 않는다.

bounded-wait idempotency 전용 sequence/state diagram을 영문과 한글로 각각 제공한다. SVG를 canonical source로 하고 scale 2 PNG를 함께 생성한다. diagram 변경 단계에서 `$bluetape-diagram`을 적용하고 XML, text hazard, connector/geometry/endpoint/mixed-corner, sequence style와 full-size PNG 육안 검사를 통과한다.

새 module이나 dependency는 추가하지 않는다. 현재 smoke/full/nightly workflow와 stale-check가 기존 `test`/`integrationTest` task를 포함하는지 감사한다. task 이름이나 module 목록이 바뀌지 않으면 workflow를 불필요하게 수정하지 않는다. 영문/한글 parity 검증은 outcome table, status/header/body schema, retry table, migration warning과 exactly-once 경고의 행/예제 수까지 비교한다.

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
- [ ] reservation/prepare/finalize가 분리되고 prepare/wait 동안 connection이나 row lock을 보유하지 않는다.
- [ ] job, outbox, history, terminal snapshot의 atomicity와 duplicate 부재가 증명된다.
- [ ] owner/waiter cancellation, deadline, lease expiry, retention 경계 후 quiescence가 증명된다.
- [ ] V001 데이터 무손실, legacy lazy snapshot, V002 checksum과 rollback drain gate가 검증된다.
- [ ] disabled deploy, all-new enable, mixed-version 금지와 binary rollback 순서가 검증된다.
- [ ] waiter/retention index plan, bounded janitor, statement budget과 pool bound가 검증된다.
- [ ] raw key, body, credential header가 저장·log·metric label에 노출되지 않는다.
- [ ] full scope tuple isolation, strict ingress, snapshot content/header negative test가 통과한다.
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

## 18. 독립 명세 검토 수렴

정확한 spec commit `063a2b97c5b8fd1ea11f6713a5cee5e435244c52`를 기준으로 여섯 관점을 검토했다. 안정성 native lane은 원본과 replacement가 모두 bounded timeout에 도달해 workflow 규칙에 따라 main session이 같은 lens를 직접 수행했다. 수정 후 통합 판정은 다음과 같다.

| Lens | 최초 P0/P1 | 통합된 핵심 발견 | 처리 |
|---|---:|---|---|
| Performance | 0/3 | transaction 보유, poll/pool bound, waiter index/GC | §4–§6, §12에 3단계 transaction, poll·pool bound, index·janitor·statement invariant 추가 |
| Stability | 0/3 | owner cancellation cleanup, lease/action 경계, deadline/recovery ordering | §6, §9에 10초 action/30초 lease, exact deadline, bounded cleanup·shutdown 추가 |
| Security | 0/2 | scope tuple 격리, snapshot body/header 안전성 | §6–§8, §12에 resolver 선행, strict ingress, typed snapshot과 negative test 추가 |
| Operator/Ops | 0/6 | lease/GC/readiness, mixed-version rollout, rollback/CI 증거 | §9–§12에 metric/readiness, expand/deploy/rollback runbook과 CI gate 추가 |
| Developer/API | 0/9 | callback/transaction API, compatibility, wire/outcome, migration 세대 | §4–§7, §11에 internal Job Submission API, generation/CAS, exact mapping과 compatibility stance 추가 |
| User/caller | 0/9 | header/body schema, 재시도, canonicalization, legacy/migration 설명 | §6–§8, §13에 exact request/response, retry table, canonical tuple와 bilingual examples 추가 |

중복 발견을 합친 뒤 모든 P1은 문서에 반영했다. 다음 P2/P3는 별도 범위로 숨기지 않고 명시적으로 처리했다.

- key hash는 승인된 SHA-256 선택을 유지하되 domain separation과 length prefix를 사용하고 non-secret identifier임을 명시했다. HMAC/rotation은 새 secret 운영체계를 요구하므로 이 workshop 범위에서 거절한다.
- hard wall-clock throughput/p95 capacity 기준은 #522 범위다. 대신 statement count, indexed plan, pool acquisition과 virtual-time convergence invariant를 이 이슈에서 검증한다.
- dashboard, alert threshold, on-call 조직과 branch protection 변경은 workshop repository가 소유하지 않는다. README 진단 표와 live PR/CI 확인까지만 이 이슈가 책임진다.
- 범용 idempotency abstraction은 비목표를 유지하고 package-internal Job Submission 전용 타입으로 제한한다.
- owner/waiter token은 CSPRNG-generated, server-only 값으로 고정했다.

최신 통합 verdict는 **P0=0, P1=0**이다. 이 수정은 승인된 PostgreSQL 전역 상태 머신, production `/v1/jobs`, replay snapshot과 additive V002 방향을 바꾸지 않고 구현·운영·caller 경계를 구체화한다.

### 최신 재검토 결과

| Lens | 재검토 결과 | 근거 |
|---|---|---|
| Performance | PASS, P0=0/P1=0 | pool 8, coordinator DB concurrency 4, owner prepare 8과 saturation test |
| Stability | PASS, P0=0/P1=0 | main-session replacement review에서 3단계 transaction, cancellation cleanup, 10초/30초 deadline·lease와 generation/GC race 확인 |
| Security | PASS, P0=0/P1=0 | complete scope tuple, ingress precedence, typed body/content type와 header injection 방어 |
| Operator/Ops | PASS, P0=0/P1=0 | 후속 발견 `abandoned_until`을 반영하고 index/janitor/race test 확인 |
| Developer/API | PASS, P0=0/P1=0 | replacement lane이 internal API, compatibility, wire/outcome, V002/BOM 경계를 확인 |
| User/caller | PASS, P0=0/P1=0 | 후속 발견 exact JSON/problem matrix, 전체 retry 지침, production/test cache 분리를 반영하고 재검토 |

## 19. Writer DoD

| Gate | 상태 | 증거 |
|---|---|---|
| SPW-01 | PASS | 한국어 Type A 설계, primary reader는 workshop contributor와 HTTP caller; #737, #520 설계, 현재 core/Spring/Ktor/V001, upstream 1.12.1 source와 BOM dependency insight를 source ledger로 사용 |
| SPW-02 | PASS | 문제·범위·대안·상태 모델·wire contract·실패 모드·migration/rollback·test·documentation·acceptance/DoD 포함 |
| SPW-03 | PASS | KO-01~KO-06 확인; code/API/HTTP/SQL token은 보존하고 과장, 번역투, 익명 권위와 의미 변경 없음 |
| SPW-04 | PASS | 기준 SHA, dependency 1.12.1, V001 column, existing service/controller/migration path, upstream conformance 값과 spec claim을 대조 |
| SPW-05 | PASS | Markdown heading/table/code fence, exact JSON validity, link와 명령, P0/P1 disposition을 최종 read-back |

미해결 writer gap은 없다. 이 writer DoD는 Step 2 spec과 Step 2-R integrated review를 함께 소유한다.

# Issue #520 Job Operations Console 설계

- 날짜: 2026-07-21
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/520
- 작업 유형: Type A Full Feature
- 대상 브랜치: `feature/issue-520-job-operations-console`
- 기준 브랜치: `develop`

## 1. 목표

동일한 browser UI와 versioned HTTP/SSE failure fixture를 공유하는 Job Operations Console을 만든다. Spring Boot와 Ktor는 교환 가능한 HTTP adapter이며, PostgreSQL이 job, tenant queue, lease, checkpoint, idempotency, outbox, terminal history의 권위 상태를 갖는다. Redis는 running job 취소를 빠르게 알리는 보조 신호이며 데이터 권위가 아니다.

완성된 예제는 다음 질문에 실행 가능한 답을 제공해야 한다.

1. tenant 내부 FIFO를 PostgreSQL transaction으로 어떻게 보장하는가?
2. 사용자에게 queue position과 과장되지 않은 ETA 범위를 어떻게 보여 주는가?
3. running job을 checkpoint 경계에서 어떻게 협력적으로 취소하는가?
4. Redis 신호 유실, worker restart, SSE event 누락 후에도 어떻게 수렴하는가?
5. Spring MVC와 Ktor가 어떻게 동일한 wire contract를 유지하면서 각 framework의 lifecycle을 따르는가?

## 2. 범위와 비목표

### 포함

- Java 25 전용 core, Spring Boot, Ktor 모듈
- tenant별 FIFO와 tenant당 기본 동시 실행 수 1
- PostgreSQL durable job, lease, checkpoint, idempotency, outbox, history
- Redis cancel notification과 알림 유실 fixture
- REST snapshot source of truth와 snapshot-only SSE
- fake clock과 barrier 기반 결정론적 fixture
- 동일 browser UI와 Spring/Ktor black-box parity test
- 영문/한글 README, architecture/sequence/job-state SVG 및 PNG

### 제외

- global FIFO, 우선순위 scheduler, generic workflow engine
- production 인증/인가, KMP/mobile client
- Redis를 queue 또는 terminal history의 권위 저장소로 사용
- Redis Streams, Kafka, NATS 등 별도 event transport 제품화
- 실제 외부 workload, cloud deployment, autoscaling, production capacity 주장
- #521 Concert Ticket Flash Sale와 #522 load/resilience profile

## 3. 선택한 구조와 대안

### 선택: 예제 전용 core + framework adapter

```text
Browser UI
   | REST snapshot + SSE notification
   +--------------------+--------------------+
   |                                         |
Spring MVC adapter                         Ktor adapter
SseEmitter + virtual threads              Ktor SSE + coroutines
   |                                         |
   +---------------- job-console-core --------+
           | PostgreSQL authority
           | Redis cancel notification
           ` deterministic fixtures
```

core는 이 예제 안에서만 재사용한다. generic queue library, framework adapter SPI, 조직 공통 contract test kit로 승격하지 않는다. 공통 정합성 로직은 한 번 구현하되 Spring/Ktor의 routing, error mapping, SSE session, startup/shutdown, cancellation wiring은 adapter별로 유지한다.

core의 production code에는 framework adapter가 의존하고, core의 `java-test-fixtures`에는 black-box fixture model과 assertion contract를 둔다. adapter는 core의 test fixture를 재사용하지만 framework-specific test host나 server lifecycle을 core에 역으로 노출하지 않는다. browser UI는 core classpath resource로 한 번만 보관하고 두 adapter가 동일 bytes를 제공한다.

### 대안 1: 계약만 공유하고 두 서버를 완전 독립 구현

프레임워크 비교 독립성은 가장 높지만 PostgreSQL schema, claim, checkpoint, idempotency, outbox가 중복된다. 두 구현이 다르게 실패해 fixture보다 구현 차이 자체를 디버깅하게 될 위험이 크므로 거절한다.

### 대안 2: 단일 모듈에서 실행 profile로 Spring/Ktor 선택

모듈 수는 줄지만 Gradle plugin, classpath, DI, server lifecycle이 얽힌다. 독립 실행성과 학습 가치가 낮아 거절한다.

## 4. 모듈과 Java 25 경계

`operations/` module group을 추가한다.

| Gradle module | 책임 |
|---|---|
| `operations-job-console-core` | domain state, DTO, PostgreSQL repository, worker engine, Redis signal port, fixtures, UI assets |
| `operations-job-console-spring` | Spring Boot 4 MVC, `SseEmitter`, virtual-thread lifecycle, health/readiness |
| `operations-job-console-ktor` | Ktor Netty, routing, SSE plugin, coroutine lifecycle, health/readiness |

root의 Java 21 toolchain은 변경하지 않는다. 세 모듈은 각각 `java.toolchain=25`, `kotlin.jvmToolchain(25)`, `JvmTarget.JVM_25`를 선언한다. Java 21 virtual-thread runtime은 exclude하고 JDK 25 runtime만 사용한다.

## 5. Domain 상태와 데이터 모델

### Job 상태

```text
queued --claim--> running --success--> succeeded
   |                 |--retryable failure--> queued
   |                 |--non-retryable------> failed
   |                 |--retry exhausted----> dead_lettered
   |                 `--cancel-------------> cancel_requested --checkpoint--> cancelled
   `--cancel---------------------------------------------------------------> cancelled
```

terminal 상태는 `succeeded`, `failed`, `dead_lettered`, `cancelled`이다. terminal 상태에서 추가 command는 상태를 바꾸지 않고 기존 결과 또는 stable conflict를 반환한다.

### PostgreSQL tables

| Table | 권위 상태 |
|---|---|
| `job_requests` | idempotency key, canonical request fingerprint, replayable response identity |
| `jobs` | tenant, submitter hash, enqueue sequence, state, queue version, lease, progress, retry budget, cancellation |
| `job_checkpoints` | 마지막 완료 chunk와 재개 cursor |
| `job_attempts` | attempt, worker lease token, 시작/종료/실패 분류 |
| `job_outbox` | stable event identity, type, resource version, publication 상태 |
| `job_history` | 사용자와 운영자가 조회할 redacted 상태 전이 |
| `job_duration_samples` | job type별 최근 성공 duration과 ETA 표본 |

tenant별 enqueue sequence는 transaction 안에서 단조 증가한다. tenant당 `running` 또는 `cancel_requested` job이 하나만 존재하도록 PostgreSQL partial unique constraint를 둔다. claim은 가장 오래된 eligible `queued` row를 잠그고 lease token과 attempt를 원자적으로 기록한다.

오래된 worker는 현재 lease token과 version이 일치할 때만 checkpoint, retry, terminal 결과를 commit할 수 있다.

queue와 claim hot path는 `(tenant_id, state, enqueue_sequence)`와 lease expiry 조건에 맞는 covering/partial index를 사용한다. query plan fixture는 repository 전체 sequential scan이 아니라 tenant-local index plan을 확인한다. tenant queue 목록은 cursor pagination을 사용하고 operator endpoint는 기본 page size와 최대 page size를 강제한다. 정확한 `jobsAhead` count는 tenant backlog에 비례할 수 있으므로 README에 이 제한과 #522 load-profile 후속 검증 경계를 명시한다.

## 6. HTTP와 SSE 계약

| Endpoint | 역할 |
|---|---|
| `POST /v1/jobs` | `Idempotency-Key`를 포함한 job 제출 |
| `GET /v1/jobs/{jobId}` | 상태, progress, queue projection, ETA snapshot |
| `POST /v1/jobs/{jobId}/cancel` | queued 즉시 취소 또는 running cancel request |
| `GET /v1/queues/me` | 현재 submitter의 tenant queue summary |
| `GET /v1/tenants/{tenantId}/queue` | operator용 redacted tenant queue summary |
| `GET /v1/events/jobs/{jobId}` | `job.updated`, `queue.updated`, heartbeat SSE |
| `GET /healthz` | process liveness |
| `GET /readyz` | PostgreSQL 필수, Redis degraded 허용 readiness |

테스트용 trusted headers가 tenant, submitter, operator scope를 주입한다. 이 resolver는 명시적인 `demo` profile에서만 활성화되고 profile이 없거나 허용되지 않은 operator scope이면 fail closed한다. tenant path parameter는 resolver가 확정한 tenant scope와 일치해야 한다. production authentication이라고 주장하지 않으며 README와 startup log에 경계를 명시한다.

동일 idempotency key와 동일 canonical request는 기존 job ID와 terminal/현재 response를 재생한다. 같은 key의 다른 request는 stable `IDEMPOTENCY_KEY_REUSED` conflict를 반환한다.

SSE event는 `eventId`, `eventType`, `jobId`, `queueVersion`, `occurredAt`만 포함한다. payload, raw tenant ID, submitter ID, stack trace는 노출하지 않는다. event replay를 보장하지 않으며 client는 최초 연결과 재연결 뒤 REST snapshot을 읽는다. committed outbox poller는 event를 bounded adapter fan-out buffer에 전달하고 slow/disconnected client를 제거한다. 한 SSE client의 write가 outbox polling 또는 다른 client를 막을 수 없다.

Spring은 `SseEmitter`, Ktor는 server SSE plugin과 heartbeat를 사용한다. 두 adapter는 동일 JSON field, HTTP status, problem code, SSE field를 반환한다.

## 7. Worker, 취소, 복구

1. worker가 claim transaction에서 lease token과 만료 시각을 받는다.
2. 각 chunk 전후에 현재 lease와 durable cancel state를 확인한다.
3. chunk 완료와 checkpoint/progress/outbox 기록은 한 transaction에서 일어난다.
4. running 취소는 먼저 PostgreSQL에 `cancel_requested`를 기록하고 Redis에 notification을 publish한다.
5. Redis notification이 유실되어도 다음 checkpoint에서 PostgreSQL 상태를 읽어 취소한다.
6. lease를 잃은 worker는 terminal 결과와 checkpoint를 commit하지 못한다.
7. 재시작 worker는 만료 lease를 reclaim하고 마지막 완료 checkpoint 다음부터 재개한다.

lease 획득, 갱신, 만료 판단은 application wall clock이 아니라 PostgreSQL server time을 사용한다. fake clock은 workload progress, ETA, retry scheduling fixture에만 사용한다. lease test는 DB가 기록한 만료 시각을 명시적으로 이동시키는 fixture transaction으로 결정론을 유지한다.

Spring adapter는 blocking JDBC/Redis worker를 Java 25 virtual-thread executor에서 실행한다. Ktor adapter는 application-owned coroutine scope를 만들고 blocking core 호출을 `Dispatchers.IO`에서 실행한다. shutdown은 신규 claim을 중지하고 active worker를 bounded wait한 뒤 취소한다. coroutine cancellation은 broad exception handler보다 먼저 다시 던진다.

## 8. Queue projection과 ETA

FIFO는 tenant 안에서 실행 시작 순서를 보장한다. `jobsAhead`는 같은 tenant에서 현재 job보다 앞선 non-terminal job 수다. 다른 tenant의 상세 순서와 identity는 노출하지 않는다.

ETA는 최근 성공 job duration의 p50/p90과 앞선 job의 checkpoint progress를 사용해 시작/완료 범위를 반환한다. job type별 최근 표본은 고정된 최대 개수와 retention window로 제한하고 계산 query는 bounded index range만 읽는다.

| Field | 의미 |
|---|---|
| `position` | 1부터 시작하는 tenant queue 위치 |
| `jobsAhead` | 앞선 non-terminal job 수 |
| `estimatedStartRange` | earliest/latest 시작 시각 |
| `estimatedCompletionRange` | earliest/latest 완료 시각 |
| `confidence` | `high`, `medium`, `low`, `insufficient_data` |
| `sampleSize` | 최근 성공 표본 수 |
| `queueVersion` | snapshot freshness용 단조 증가 버전 |
| `updatedAt` | 계산 시각 |

표본이 부족하면 거짓 정밀도를 만들지 않고 `insufficient_data`를 반환한다. ETA는 SLA가 아님을 UI와 README에 표시한다.

## 9. 오류, 보안, 관측성

stable problem code는 validation, idempotency conflict, not found, scope denial, invalid transition, dependency unavailable, lease lost를 구분한다. 내부 exception과 provider detail은 client response에 포함하지 않는다.

운영 로그는 request ID, route template, redacted job ID, transition, safe failure class를 사용한다. raw tenant ID, submitter ID, payload, Redis key content를 기록하지 않는다. metric label은 route template, job type, state, safe failure class처럼 low-cardinality 값만 허용한다.

PostgreSQL 장애는 readiness failure다. Redis 장애는 degraded 상태이며 제출, 조회, durable cancel 기록은 계속 가능하다. SSE publisher 장애는 REST snapshot을 손상시키지 않는다.

outbox backlog, oldest unpublished age, active lease, oldest tenant wait, retry/dead-letter count, Redis degraded state를 metric과 health detail로 노출한다. metric label에는 tenant/job identity를 넣지 않는다. rollback은 신규 claim 중지, adapter drain, 이전 application binary 재기동 순서이며 PostgreSQL schema는 backward-compatible additive migration만 허용한다.

## 10. Ecosystem capability 선택

| 책임 | 사용 capability | 선택 또는 제외 이유 |
|---|---|---|
| 검증/기본 유틸 | `bluetape4k-core` | 기존 validation과 공통 유틸 재사용 |
| logging | `bluetape4k-logging` | lazy structured operational log |
| identity | `bluetape4k-idgenerators` | stable UUID/event identity |
| JSON | `bluetape4k-jackson3` | 공통 wire/fixture serialization |
| PostgreSQL | `bluetape4k-exposed-jdbc` | framework-neutral JDBC authority |
| DB tests | `bluetape4k-exposed-jdbc-tests`, `PostgreSQLServer` | 실제 PostgreSQL authoritative proof |
| Redis | `bluetape4k-lettuce`, `RedisServer` | cancel notification과 장애 fixture |
| virtual threads | `bluetape4k-virtualthread-api`, JDK 25 runtime | Spring blocking boundary |
| metrics | `bluetape4k-micrometer` | low-cardinality queue/worker metrics |
| Spring adapter | Spring Boot 4 MVC/JDBC/Actuator | 기존 workshop Spring 패턴 |
| Ktor adapter | Ktor BOM/Netty/SSE/StatusPages | 기존 `ktor/rest-coroutines` 패턴 |
| leader | 제외 | DB lease가 worker 권위이며 global leader 불필요 |
| Spring Modulith | 제외 | framework-neutral core를 Spring에 종속시키지 않음 |
| Redis Streams | 제외 | v1 durable history는 PostgreSQL outbox가 담당 |

## 11. 결정론적 failure fixture

| Case | 불변식 |
|---|---|
| 동일 제출 재전송 | 새 job, enqueue sequence, outbox가 생성되지 않음 |
| 같은 tenant 동시 제출 | enqueue sequence 순서로 claim, active job 최대 1 |
| queued cancel/claim 경쟁 | 하나의 권위 결과와 terminal history |
| running cancel | checkpoint 경계에서 `cancel_requested`에서 `cancelled`로 수렴 |
| Redis notification 유실 | durable cancel state로 동일 결과 |
| worker restart/lease 만료 | stale worker commit 거절, 마지막 checkpoint에서 재개 |
| SSE 누락/재연결 | REST `queueVersion` snapshot으로 수렴 |
| outbox 중복 publication | stable event ID로 중복 반영하지 않음 |
| retry budget 소진 | 정확히 한 번 `dead_lettered` terminal history |

fixture는 fake clock, deterministic workload, barrier를 사용한다. wall-clock sleep과 실제 외부 provider는 사용하지 않는다.

performance fixture는 큰 tenant queue에서도 제출/claim/position query가 tenant-local index plan을 유지하는지, slow SSE consumer가 outbox poller와 다른 client를 지연시키지 않는지 검증한다. 처리량 수치와 exact position query의 backlog별 용량 평가는 #522 범위이므로 합격 기준으로 사용하지 않는다.

## 12. 테스트 전략

1. core domain/state/ETA/idempotency 단위 테스트
2. PostgreSQL/Redis Testcontainers repository/worker 통합 테스트
3. Spring live HTTP/SSE와 virtual-thread lifecycle 테스트
4. Ktor live HTTP/SSE와 coroutine cancellation/lifecycle 테스트
5. 동일 black-box fixture를 두 base URL에 실행하는 parity 테스트
6. worker restart, Redis unavailable, SSE reconnect, shutdown recovery 테스트

core의 container-free test는 smoke 후보로 둔다. PostgreSQL/Redis와 live server test는 full/nightly에서 순차 실행한다. Testcontainers evidence는 다른 module 또는 agent와 병렬 실행하지 않는다.

## 13. README와 diagram

`README.md`와 `README.ko.md`는 동일한 실행 시나리오, API, failure fixture, 운영 경계를 설명한다. 공통 English-label SVG/PNG를 함께 사용한다.

browser UI는 내 앞 job 수, 시작/완료 ETA 범위와 confidence, progress/checkpoint, cancellation acknowledgement, last snapshot update를 표시한다. operator view는 redacted tenant backlog, oldest wait, worker lease, retry/dead-letter, PostgreSQL/Redis readiness만 보여 주며 payload와 다른 tenant의 사용자 identity를 표시하지 않는다.

- architecture: core, Spring, Ktor, PostgreSQL, Redis 경계
- sequence: submit, claim, checkpoint, outbox, SSE, snapshot refresh
- job lifecycle state diagram: 상태, command, retry/cancel/failure 분기

diagram은 구현 source를 읽은 뒤 제작한다. SVG를 canonical source로 두고 CairoSVG scale 2 PNG를 authoritative output으로 검증한다. XML, text hazard, connector/geometry/endpoint/mixed-corner, sequence style audit와 full-size PNG 육안 검사를 통과해야 한다.

## 14. Repository 등록과 호환성

- `settings.gradle.kts`에 `operations` module group을 등록한다.
- root/module README map과 repo-local `AGENTS.md` module map을 갱신한다.
- smoke/full/nightly workflow group, stale validation script, summary `needs`, Kover/Codecov artifact 경로를 점검한다.
- `./gradlew projects`로 세 모듈 등록을 검증한다.
- `bluetape4k-dependencies` BOM만 사용하고 개별 Bluetape BOM 또는 명시 버전을 추가하지 않는다.
- 기존 module과 root Java 21 build에는 동작 변경이 없어야 한다.

schema는 신규 예제 전용이므로 기존 데이터 migration은 없다. 예제 내부 schema evolution은 idempotent startup migration과 compatibility fixture로 검증하며 destructive recreate를 기본 경로로 사용하지 않는다.

startup migration은 기존 `promotion-voucher-campaign`의 ordered migration/checksum pattern을 빌려 core에 예제 전용으로 둔다. Flyway 같은 신규 dependency와 generic migration framework는 추가하지 않는다. migration 실패 시 server는 readiness를 열지 않고 worker를 시작하지 않는다.

## 15. 주요 failure mode와 완화

### Tenant FIFO가 retry 또는 restart에서 깨짐

원래 enqueue sequence를 보존하고 claim query, active-job partial unique constraint, concurrency fixture로 막는다. retry는 동일 job의 다음 attempt이며 새 queue identity가 아니다.

### Redis 장애가 취소 결과를 지움

PostgreSQL에 cancel request를 먼저 commit하고 Redis는 notification으로만 사용한다. Redis unavailable/reconnect fixture에서 durable convergence를 검증한다.

### Stale worker가 새 worker 결과를 덮어씀

모든 checkpoint와 terminal write가 current lease token과 version을 조건으로 수행된다. lease-loss fixture에서 stale write가 0 row update로 거절됨을 검증한다.

### SSE 누락을 terminal event 손실로 오해

SSE를 notification channel로 문서화하고 연결/reconnect 시 REST snapshot을 읽는다. replay를 보장하는 `Last-Event-ID` 의미를 만들지 않는다.

### ETA가 거짓 정밀도를 제공

p50/p90 범위, confidence, sample size, freshness를 함께 제공하고 표본 부족은 `insufficient_data`로 표현한다.

### Framework lifecycle 종료 중 resource leak

Spring executor와 Ktor application scope의 ownership을 adapter가 명시적으로 가진다. shutdown/cancellation fixture에서 신규 claim 중지, bounded drain, resource close를 검증한다.

## 16. Acceptance criteria와 DoD

- Spring/Ktor가 동일 HTTP/SSE 및 failure fixture를 통과한다.
- PostgreSQL이 queue, cancel, checkpoint, terminal history의 유일한 권위다.
- tenant FIFO와 tenant당 active job 최대 1이 동시성 테스트로 증명된다.
- duplicate submit, cancellation race, Redis loss, worker restart, outbox duplicate, SSE reconnect가 결정적으로 수렴한다.
- 세 신규 모듈만 Java 25이며 root Java 21 설정은 유지된다.
- README locale parity와 architecture/sequence/state SVG/PNG가 검증된다.
- module registration과 smoke/full/nightly/stale/Kover surface가 갱신된다.
- targeted tests, full module tests, detekt, repository validation, `git diff --check`가 통과한다.
- 독립 관점 review와 main integration에서 P0=0, P1=0이다.
- exact-head PR과 CI/review evidence를 확인한 뒤 merge-ready에서 멈춘다.

## 17. 설계 근거

### Local anchors

- `commerce/promotion-voucher-campaign`: Java 25 module override, PostgreSQL-authoritative workflow, Redis degraded behavior, migration/checksum, live HTTP/SSE tests
- `ktor/rest-coroutines`: Ktor SSE plugin, heartbeat, client SSE tests
- `ktor/exposed-rest`: Ktor와 Exposed JDBC/Testcontainers 결합
- `exposed/mvc-jdbc`: Spring MVC와 Exposed JDBC/PostgreSQL test pattern
- `redis/redisson-examples`: `RedisServer.Launcher` singleton fixture pattern

### External primary sources

- [Spring Framework 7 MVC asynchronous request and `SseEmitter`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
- [Ktor 3.5 server SSE plugin and heartbeat](https://ktor.io/docs/server-server-sent-events.html)
- [JetBrains Exposed JDBC transactions](https://www.jetbrains.com/help/exposed/transactions.html)
- [Redis Pub/Sub at-most-once delivery](https://redis.io/docs/latest/develop/pubsub/)

## 18. 설계 리뷰 결과

| Priority | Lens | Finding | Resolution |
|---|---|---|---|
| P1 | performance | queue position, ETA sample, operator listing이 repository-wide scan이 될 수 있음 | tenant-local index, cursor pagination, sample bound, query-plan fixture와 exact count 제한 문서화 추가 |
| P1 | stability | application clock 기반 lease는 clock skew와 flaky test 위험 | PostgreSQL server time을 lease 권위로 고정 |
| P1 | security | trusted header가 일반 profile에서 열리면 scope 위조 가능 | explicit demo profile, operator fail-closed, path/scope 일치 추가 |
| P1 | stability/performance | slow SSE client가 outbox poller를 막을 수 있음 | bounded fan-out buffer와 slow client removal 추가 |
| P2 | operator/Ops | migration/rollback과 backlog 진단이 불충분 | ordered migration, fail-closed startup, backlog/lease metrics 추가 |
| P2 | user/caller | UI가 어떤 상태와 ETA 불확실성을 보여 주는지 불명확 | 사용자/operator UI field와 redaction 경계 추가 |

수정 후 재검토 결과는 performance, stability, security, operator/Ops, developer/API, user/caller 모두 P0=0, P1=0이다. P2 항목은 설계에 반영되어 열린 finding이 없다.

# Issue #533 Reservation Control Plane 구현 계획

**목표:** Java 25 Spring Boot MVC application에서 PostgreSQL-authoritative hold,
confirmation, cancellation, expiry와 waitlist promotion을 구현하고, Redis admission,
Lettuce leader sweeper, fake notification, browser UI를 장애/경합 fixture로 검증한다.

**아키텍처:** HTTP command는 Redis admission/suppression을 보조 경계로 통과한 뒤
application-owned PostgreSQL idempotency record를 획득한다. Exposed JDBC repository는
expected revision/state를 조건으로 CAS하고 audit/outbox를 같은 transaction에 기록한다.
scheduled sweeper는 Lettuce leader lease로 우선 실행자를 고르지만 모든 finalization과
promotion은 PostgreSQL CAS/unique constraint로 중복 안전하게 만든다.

**기술 스택:** Kotlin 2.3 language level, Java 25, Spring Boot 4.1 MVC, JetBrains Exposed
1.3.0, PostgreSQL, Redis/Lettuce, HikariCP, Jackson 3, Micrometer, JUnit 5,
`bluetape4k-dependencies:1.3.1`, Bluetape core/Exposed/Lettuce/Testcontainers/virtual-thread
1.11.0, Bluetape leader 0.4.0.

## Software stack approval table

| Responsibility | Reused module/capability | Decision and constraint |
|---|---|---|
| Version authority | `bluetape4k-dependencies:1.3.1` | 유일한 BOM. 개별 Bluetape BOM/명시 버전 금지 |
| Domain tables | `bluetape4k-exposed-core` `AuditableLongIdTable` | audit columns와 DB timestamp 재사용 |
| Repositories | `bluetape4k-exposed-jdbc` `LongAuditableJdbcRepository` | CRUD는 재사용하고 reservation CAS만 application method로 구현 |
| Conditional CAS | `auditedUpdateAll` + `affectedRows == 1` | public generic CAS API가 없으므로 expected revision/state 조건을 좁게 구현 |
| Spring transaction | `bluetape4k-exposed-spring-boot-jdbc` | `springTransactionManager` 경계 재사용 |
| Repository tests | `bluetape4k-exposed-jdbc-tests` | `withTables`, `TestDB.POSTGRESQL`, 공개 test helper 재사용 |
| PostgreSQL | `bluetape4k-testcontainers` `PostgreSQLServer` | 동시성/재시작 correctness authority. H2로 대체 금지 |
| Redis client | `bluetape4k-lettuce` `LettuceClients` | client/connection lifecycle 재사용 |
| Admission | `bluetape4k-lettuce` `LettuceSemaphore` | 1.11.0에는 permit lease가 없으므로 best-effort + finally release. local bulkhead가 안전 경계 |
| In-flight suppression | `bluetape4k-lettuce` `LettuceLock` | token-checked unlock 재사용. ad-hoc SET NX/Lua 금지 |
| Redis tests | `bluetape4k-testcontainers` `RedisServer` | backend failure/expiry/reconnect fixture |
| Sweeper election | `bluetape4k-leader-core`, `bluetape4k-leader-redis-lettuce` 0.4.0 | `@Scheduled` 안에서 `runIfLeaderResult` 사용 |
| Leader wiring | `bluetape4k-leader-core` + `bluetape4k-leader-redis-lettuce` 0.4.0 | Redis connection과 optional leader bean을 application이 소유한다. connection bean 존재만으로 backend auto-configuration이 활성화되는 Spring Boot 모듈은 startup fail-open과 맞지 않아 사용하지 않는다. |
| Virtual threads | `bluetape4k-virtualthread-api`, runtime `bluetape4k-virtualthread-jdk25` | MVC/JDBC/Lettuce/worker blocking 경계. JDK21 provider 제외 |
| Concurrency fixture | `bluetape4k-junit5` `MultithreadingTester` | CAS race와 duplicate sweeper 검증 |
| IDs and JSON | `bluetape4k-idgenerators`, `bluetape4k-jackson3`, `bluetape4k-exposed-jackson3` | UUID v7, canonical fingerprint, closed DTO |
| Logging | `bluetape4k-logging` `KLogging` | 모든 operational class, lazy key=value event, redaction contract test |
| Metrics | `bluetape4k-micrometer`, Actuator | low-cardinality command/outcome/reason only |
| Notification | application-owned outbox + deterministic fake | 실제 provider 없음. unique delivery와 fail-first retry 검증 |
| HTTP test | live `WebTestClient.bindToServer()` | MockMvc 사용 금지, 실제 Tomcat/serialization/error 경계 검증 |

### 명시적으로 사용하지 않는 것

- current leader develop의 `@LeaderScheduled`: BOM 1.3.1의 leader 0.4.0 JAR에 없음
- #391 `JdbcIdempotencyFixture`: module `src/test` 전용이며 배포 API가 아님
- #1055 공용 repository/filter: issue도 generic store API를 비목표로 두며 아직 OPEN
- Spring Data Redis/Redisson: Lettuce direct API로 필요한 책임을 충족
- Spring Modulith publication: #533 notification은 동일 PostgreSQL transaction/unique
  delivery를 보여주는 작은 application-owned outbox가 더 직접적
- Redis capacity counter/global scheduler/generic reservation core

## Logging acceptance contract

사용자의 “모든 코드에 `bluetape4k-logging` 사용” 요구는 다음처럼 검증 가능하게 적용한다.

- controller/filter, application service, policy executor, repository/CAS, idempotency,
  Redis adapter, leader/sweeper, notification, configuration/lifecycle class는 모두
  `KLogging`을 사용한다.
- pure DTO/enum/table schema처럼 실행하지 않는 선언형 타입은 logger 대상에서 제외한다.
- success만이 아니라 rejection, stale CAS, duplicate, Redis degraded, leader skipped,
  retry/final failure도 안정 event name으로 기록한다.
- raw idempotency key, raw owner token, full owner hash, payload, PII는 log/metric에 없다.
- `OperationalLoggingTest`와 forbidden-value scan test로 event 존재와 redaction을 검증한다.

## 파일 구조

새 모듈 `commerce/reservation-control-plane`, package prefix
`io.bluetape4k.workshop.commerce.reservation`을 사용한다.

- `ReservationControlPlaneApplication.kt`: entrypoint와 lifecycle logging
- `config/ReservationConfiguration.kt`: Clock, VT executor, Hikari/Tomcat/Redis wiring
- `domain/ReservationModels.kt`: aggregate/state/command/outcome
- `domain/ReservationPolicies.kt`: hold/confirm/cancel/extend/offer transition
- `persistence/ReservationTables.kt`: resource, hold, waitlist, offer, audit, outbox, idempotency
- `persistence/*JdbcRepository.kt`: Bluetape auditable repository + bounded CAS
- `idempotency/HttpIdempotencyRepository.kt`: acquire/replay/conflict/takeover/finalize
- `redis/ReservationAdmissionGate.kt`: LettuceSemaphore + local fallback
- `redis/InFlightCommandSuppressor.kt`: LettuceLock + PostgreSQL fallback
- `sweeper/ReservationExpirySweeper.kt`: leader-guarded bounded expiry/promotion
- `sweeper/ReservationResourceTransactionService.kt`: proxy-safe one-resource transaction
- `notification/NotificationOutbox.kt`: unique delivery와 deterministic fake adapter
- `application/ReservationCommandService.kt`: command transaction orchestration
- `query/ReservationQueryService.kt`: browser snapshot와 operator projection
- `web/ReservationController.kt`: live REST command/query
- `web/ApiExceptionHandler.kt`: stable code/reason/requestId
- `web/RequestLoggingFilter.kt`: requestId와 redacted boundary log
- `web/SecurityHeadersFilter.kt`: no-store/CSP/referrer/same-origin 계약
- `web/OperatorPrincipalResolver.kt`: configured key digest의 actor/role 매핑과 `/operator/**` 보호
- `src/main/resources/static/*`: reservation browser console
- `src/main/resources/application.yml`: bounded server/pool/timeout/worker 설정
- domain/repository/concurrency/Redis/leader/restart/WebTestClient/logging tests

등록 표면:

- `settings.gradle.kts`, `gradle/libs.versions.toml`
- `README.md`, `README.ko.md`, `AGENTS.md`
- `.github/workflows/ci.yml`, `.github/workflows/Examples.yml`, `.github/workflows/nightly.yml`
- `scripts/smoke-validate.sh`, stale/module validation matrix
- module/group bilingual README, Architecture/Sequence Diagram, lesson/review

## 구현 순서

### 1. 모듈과 dependency 계약

- [ ] module skeleton과 Java 25 toolchain을 등록한다.
- [ ] BOM이 관리하는 versionless `bluetape4k-leader-core`와 `bluetape4k-leader-redis-lettuce`를 조합한다.
- [ ] dependency insight로 Exposed 1.3.0, Bluetape 1.11.0, leader 0.4.0,
  JDK25 virtual-thread provider를 확인한다.
- [ ] Java 21 workflow runtime은 유지하고 module toolchain으로 Java 25를 provision한다.
- [ ] `CommerceSchemaInitializer` 패턴을 재사용하고 local/test PostgreSQL/Redis dynamic property를 분리한다.
- [ ] Redis connection 실패가 Spring context 시작을 막지 않고 node-local fallback으로 수렴함을 smoke test로 고정한다.

### 2. 상태 정책 TDD

- [ ] hold/confirm/cancel/extend/force-release transition test를 먼저 작성한다.
- [ ] hold/offer expiry와 terminal 재전이 거부를 고정한다.
- [ ] policyVersion mismatch와 timezone/DST fixture를 구현한다.
- [ ] transaction마다 주입 `Clock`의 `now`를 한 번만 캡처하고 DB timestamp는 audit 전용으로 제한한다.

### 3. Exposed repository와 PostgreSQL CAS

- [ ] auditable resource/hold/waitlist/offer repository를 구현한다.
- [ ] expected revision/state/owner/expiry 조건의 `auditedUpdateAll` CAS를 구현한다.
- [ ] capacity invariant와 active offer/transition unique constraint를 추가한다.
- [ ] `occupiedCount = HELD + CONFIRMED + ACTIVE_OFFER`와 canonical lock order를 고정한다.
- [ ] expiry/waitlist composite index와 FIFO `FOR UPDATE LIMIT 1` query를 구현한다.
- [ ] `withTables(TestDB.POSTGRESQL)`에서 동시 hold와 stale revision을 검증한다.
- [ ] capacity 1/N의 expire/new-hold/promote/accept 4-way race와 deadlock-free order를 검증한다.

### 4. HTTP idempotency 계약

- [ ] random key validation, server-secret HMAC scope와 owner digest 포함 fingerprint를 구현한다.
- [ ] same/same replay, same/different conflict, in-progress를 검증한다.
- [ ] expired lease takeover와 stale owner finalize 거부를 검증한다.
- [ ] 90초 lease/60초 transaction/5초 lock timeout 관계를 고정한다.
- [ ] finalize owner CAS 실패 시 command state 전체 rollback과 orphan mutation 부재를 검증한다.
- [ ] raw owner token/key가 DB terminal body와 replay response에 없음을 검증한다.

### 5. Redis admission과 suppression

- [ ] always-on foreground 5/background 1 node-local DB bulkhead를 먼저 구현한다.
- [ ] `LettuceSemaphore` 64 permit/100ms acquire와 모든 경로의 finally release를 구현한다.
- [ ] 1.11.0 semaphore의 crash lease 부재를 문서화하고 permit 누수 projection/reset recovery를 검증한다.
- [ ] `LettuceLock` 2초 suppression과 token-checked release를 연결한다.
- [ ] rejection, lock hit, TTL expiry, Redis failure/degraded path를 검증한다.
- [ ] Redis flush/eviction 뒤에도 PostgreSQL 결과가 동일함을 검증한다.
- [ ] outage/fallback/recovery와 client/connection shutdown을 검증한다.

### 6. Leader-guarded expiry와 waitlist promotion

- [ ] `fixedDelay` + local single-flight + `runIfLeaderResult(LeaderSlot(lockName, instanceId))` trigger를 구현한다.
- [ ] `Elected`/`Skipped`/`ActionFailed`와 lock-acquire backend exception을 각각 bounded outcome으로 분류한다.
- [ ] 15초 auto-extend lease, batch 32, tick budget 5초를 구성한다.
- [ ] scheduler/leader bean과 `ReservationResourceTransactionService`를 분리해 transactional self-invocation을 금지한다.
- [ ] hold/offer expiry, capacity accounting, audit, promotion, outbox를 resource 단위 transaction으로 처리한다.
- [ ] waitlist next entry를 stable order로 한 번만 offer한다.
- [ ] duplicate/overlap sweeper, hot resource, batchSize+1, leader failure, transaction cut-point restart를 검증한다.

### 7. Notification outbox와 fake adapter

- [ ] transition transaction에 stable deliveryId unique notification row를 추가한다.
- [ ] claim lease/attempt/nextAttemptAt/stale-finalize와 provider idempotency를 구현한다.
- [ ] deterministic success/fail-first/duplicate/provider-accepted-then-crash adapter를 구현한다.
- [ ] retry/restart 후 fake provider effect와 delivered terminal 상태가 한 번임을 검증한다.
- [ ] 최대 5회 bounded retry, `EXHAUSTED`, idempotent operator redrive를 검증한다.
- [ ] PII/raw token이 payload와 log에 없음을 검증한다.

### 8. Browser UI와 live HTTP

- [ ] live `WebTestClient`로 hold/confirm/cancel/extend/waitlist/accept-offer를 검증한다.
- [ ] browser-generated 256-bit reservation-session owner header와 HMAC constant-time verification을 검증한다.
- [ ] waitlist/offer가 owner digest를 계승하고 다른 owner의 snapshot/cancel/accept를 거부함을 검증한다.
- [ ] command별 method/path/header/body/status/replay와 공통 error DTO 계약을 고정한다.
- [ ] same-key/payload timeout/in-progress retry, `Retry-After`, polling/visibility refresh state machine을 구현한다.
- [ ] calendar/capacity, countdown, position/range, offer expiry, stale reason을 렌더링한다.
- [ ] UTC/capacity 1/hold TTL 30초/offer TTL 20초 fixture와 two-browser demo/reset을 제공한다.
- [ ] fixed server tenant와 `OperatorPrincipalResolver`의 local/test actor/role/key 경계를 검증한다.
- [ ] 모든 `/operator/**`에 filter를 적용하고 missing/wrong key, role 부족, tenant/actor injection, projection 우회를 거부한다.
- [ ] no-store, same-origin, CSP, referrer policy와 token 비영속성을 검증한다.
- [ ] reload/tab-close credential loss 경고와 server snapshot 기반 재동기화를 구현한다.
- [ ] force release의 idempotency/expectedRevision/reason, terminal replay/conflict와 atomic rollback을 검증한다.
- [ ] manual sweep의 maxResources 32/5초/single-flight와 scheduled service 재사용을 검증한다.
- [ ] local/test operator panel에 key 비저장, 확인 단계, 영향 snapshot과 결과 요약을 제공한다.
- [ ] Tomcat 8000 connection/fallback threads와 60초 timeout을 검증한다.
- [ ] Hikari pool 8과 60초 connection/transaction timeout을 검증한다.

### 9. Logging과 observability

- [ ] 모든 operational class에 `KLogging`을 적용한다.
- [ ] command/CAS/admission/suppression/leader/sweeper/notification/lifecycle event를 남긴다.
- [ ] `OperationalLoggingTest`로 필수 event와 raw secret/PII/외부 exception 부재를 검증한다.
- [ ] 정상 rejection은 DEBUG/INFO, authorization과 degraded 전이만 WARN, invariant failure만 ERROR로 검증한다.
- [ ] Redis degraded transition throttle과 admission sampling/rate limit을 검증한다.
- [ ] policyVersion/digest/resource id가 metric tag에 없음을 검증한다.
- [ ] process liveness, PostgreSQL-authoritative readiness, Redis/leader DEGRADED 상태 전이를 live HTTP로 검증한다.
- [ ] 인증된 projection에 observedAt/sweep/backlog/oldest age/stale flag만 redacted 제공한다.

### 10. README, diagrams와 저장소 통합

- [ ] module README에 영문/한글 예제 시나리오를 작성한다.
- [ ] PostgreSQL/Redis/sweep/notification/force-release/restart별 signal-판단-조치-복구확인 runbook을 작성한다.
- [ ] PostgreSQL authority와 Redis/leader 보조 경계를 Architecture Diagram에 표현한다.
- [ ] Architecture Diagram에 browser/HTTP/Redis/PostgreSQL/leader/outbox authority를 표현한다.
- [ ] Sequence Diagram에 retry/expiry/promotion/notification과 stale/error alt 흐름을 표현한다.
- [ ] 정상 hold-confirm, two-browser contention, promotion-accept, ambiguous replay, Redis outage, operator release를 재현한다.
- [ ] module/group/root bilingual index와 AGENTS/module matrix/workflow/stale check를 갱신한다.
- [ ] diagram visual QA, README parity, actionlint, stale-check, smoke/full 검증을 실행한다.

## 테스트 매트릭스

| 계약 | 최소 증거 |
|---|---|
| capacity safety | PostgreSQL에서 동시 N hold가 capacity를 넘지 않음 |
| owner safety | session token은 header/memory에만 있고 DB/response/log에 없으며 다른 owner는 hold/waitlist/offer 조회·mutation 불가 |
| idempotency | replay/conflict/in-progress/takeover/stale-finalize |
| expiry | fake clock 전/후, duplicate sweep, restart 후 finalization 1회 |
| waitlist | atomic occupied accounting, stable FIFO, active offer 1개, expired offer 재promotion 1회 |
| Redis advisory | rejection/TTL/backend failure/flush 뒤 DB 결과 불변 |
| leader advisory | elected/skipped/backend failure와 duplicate trigger 안전 |
| notification | stable deliveryId, claim lease, fail-first/provider-accepted crash, duplicate suppression |
| DST | overlap/gap local time의 결정적 Instant 변환과 display |
| live HTTP | 실제 Tomcat + WebTestClient serialization/status/reason |
| browser recovery | same-key retry, Retry-After, polling/visibility refresh, reload credential-loss UX |
| logging | 필수 level/throttle event 존재, raw key/token/hash/PII/payload/외부 exception 부재 |
| authorization | fixed tenant, client actor rejection, all operator routes의 local/test principal role/key enforcement |
| operations | DB readiness, Redis/leader degraded projection, bounded force-release/manual-sweep, recovery runbook |

## 승인 후 stop condition

구현은 targeted tests, PostgreSQL/Redis integration, live HTTP, module build, smoke/full
workflow group, actionlint, stale-check, README/diagram validator와 `git diff --check`가 모두
통과하고 independent review에서 P0/P1이 0일 때 완료한다. push, PR, merge는 현재 범위에
포함하지 않는다.

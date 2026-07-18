# Issue #532 Order Lifecycle and Fulfillment Orchestrator 구현 계획

**목표:** Java 25 Spring Boot MVC application에서 독립적인 주문·결제·재고·배송·환불
상태 머신을 PostgreSQL에 유지하고, HTTP idempotency, Exposed-backed Spring Modulith
publication, deterministic provider failure, browser UI/SSE를 함께 검증한다.

**아키텍처:** REST command는 application-owned PostgreSQL idempotency record를 획득한 뒤
Exposed repository로 aggregate와 audit를 갱신한다. 작은 domain event는 Spring Modulith와
`bluetape4k-exposed-spring-modulith`가 listener별 durable publication으로 관리한다. provider
callback은 unique inbox를 통과하며, query API와 SSE는 상태 머신 revision과 운영 backlog를
payload 원문 없이 투영한다.

**기술 스택:** Kotlin 2.3 language level, Java 25, Spring Boot 4.1 MVC, Spring Modulith
2.1, JetBrains Exposed 1.3.0, PostgreSQL, HikariCP, Jackson 3, Micrometer, JUnit 5,
`bluetape4k-dependencies:1.3.1`, Bluetape Exposed 1.11.0, Bluetape virtual threads 1.11.0.

## Ecosystem capability selection

| Responsibility | Reused module/capability | Why used or not used | Unavailable/fake constraint |
|---|---|---|---|
| Versions | `bluetape4k-dependencies:1.3.1` | 모든 Bluetape/Exposed 버전의 단일 권위 | 개별 Bluetape BOM이나 명시 버전 금지 |
| Domain persistence | `bluetape4k-exposed-core`, `bluetape4k-exposed-jdbc` | auditable table과 JDBC repository 상속 | atomic lease/transition에는 bounded custom SQL 허용 |
| Spring JDBC wiring | `bluetape4k-exposed-spring-boot-jdbc` | Exposed와 Spring transaction 경계 재사용 | application-owned schema는 시작 시 명시적으로 생성 |
| Durable publication | `bluetape4k-exposed-spring-modulith` | #390의 completion/replay/observability 계약 재사용 | 범용 broker outbox는 만들지 않음 |
| Persistence tests | `bluetape4k-exposed-jdbc-tests` | `withTables`, PostgreSQL 계약 fixture 재사용 | starter 충돌 transitive는 필요한 경우에만 exclude |
| PostgreSQL | `bluetape4k-testcontainers` `PostgreSQLServer` | 권위 DB와 재시작/동시성 검증 | H2를 권위 검증에 사용하지 않음 |
| Concurrency | `bluetape4k-junit5` `MultithreadingTester` | lease acquisition과 duplicate event race 검증 | timing sleep에 의존하지 않음 |
| Virtual threads | `bluetape4k-virtualthread-api`, runtime `bluetape4k-virtualthread-jdk25` | blocking provider/SSE 경계와 lifecycle 재사용 | JDK 21 provider 제외 |
| IDs/hash | `bluetape4k-idgenerators`, JDK SHA-256 | UUID v7과 안정 fingerprint | raw idempotency key 저장/로그 금지 |
| JSON | `bluetape4k-jackson3`, `bluetape4k-exposed-jackson3` | canonical DTO와 closed snapshot mapping | default typing과 임의 payload 역직렬화 금지 |
| Logging/metrics | `bluetape4k-logging`, `bluetape4k-micrometer` | structured log와 observation 재사용 | low-cardinality/redacted label만 허용 |
| Redis | 사용하지 않음 | PostgreSQL transaction이 최종 권위이며 cache가 필요 없음 | 필요성이 새로 입증되면 Lettuce만 검토 |
| Payment/carrier/tax | deterministic application fake | 자격증명 없이 실패 순서 재현 | 실제 PCI, tax, carrier integration은 범위 밖 |

## 파일 구조

새 모듈 `commerce/order-lifecycle-fulfillment`를 만들고 package prefix는
`io.bluetape4k.workshop.commerce.order`로 둔다.

- `OrderLifecycleApplication.kt`: Spring Boot entrypoint
- `config/OrderLifecycleConfiguration.kt`: Java 25 executor, clock, schema/lifecycle bean
- `domain/LifecycleModels.kt`: aggregate, status, command, event value
- `domain/TransitionPolicies.kt`: 상태 머신별 허용 전이
- `persistence/LifecycleTables.kt`: aggregate, line, inbox, audit, idempotency table
- `persistence/LifecycleRepositories.kt`: Bluetape JDBC repository 구현
- `idempotency/HttpIdempotencyRepository.kt`: acquire/replay/conflict/lease/finalize
- `payment/DeterministicPaymentProvider.kt`: success/decline/delay/out-of-order/duplicate fixture
- `persistence/ProviderEventInboxRepository.kt`: provider event deduplication과 transition
- `application/OrderCommandService.kt`: transaction과 domain event publication
- `application/LifecycleListeners.kt`: payment, inventory, fulfillment, refund listener
- `application/ReconciliationService.kt`: publication/provider 보정 command
- `query/OrderLifecycleQueryService.kt`: browser snapshot과 backlog projection
- `web/OrderController.kt`: REST command/query
- `web/OrderEventStream.kt`: snapshot-first SSE, Last-Event-ID, bounded VT lifecycle
- `web/ApiExceptionHandler.kt`: stable/redacted error
- `src/main/resources/static/index.html`, `app.js`, `styles.css`: browser console
- `src/main/resources/application.yml`: PostgreSQL, Modulith UPDATE, bounded 설정
- repository, contract, integration, MVC, SSE, restart test

등록 표면:

- `settings.gradle.kts`, `gradle/libs.versions.toml`
- `README.md`, `README.ko.md`, `AGENTS.md`
- `.github/workflows/ci.yml`, `.github/workflows/Examples.yml`, `.github/workflows/nightly.yml`
- `scripts/smoke-validate.sh`와 stale/module validation
- module/group bilingual README와 `docs/lessons`, `docs/review`

## 구현 순서

### 1. 모듈과 버전 계약

- [x] `commerce` group과 `commerce-order-lifecycle-fulfillment` project를 등록한다.
- [x] 모듈의 Java/Kotlin toolchain을 25로 override한다.
- [x] versionless alias로 Exposed JDBC/Modulith/virtual-thread artifact를 추가한다.
- [x] dependency insight로 Exposed 1.3.0, Bluetape Exposed 1.11.0, JDK25 provider를 확인한다.

### 2. 상태 머신 TDD

- [x] Order/payment/reservation/fulfillment/refund transition test를 먼저 작성한다.
- [x] terminal transition 재적용과 revision 건너뛰기를 거부한다.
- [x] payment success가 fulfillment 완료를 직접 만들 수 없음을 테스트한다.

### 3. Exposed repository와 audit

- [x] auditable UUID repository를 aggregate별로 구현한다.
- [x] line/group association과 transition audit repository를 구현한다.
- [x] PostgreSQL `withTables` test로 aggregate별 revision CAS를 검증한다.
- [ ] audit unique key 중복 거부를 별도 repository test로 고정한다.

### 4. HTTP idempotency와 #1055/#391 fixture

- [x] canonical fingerprint와 hashed key scope test를 작성한다.
- [x] same/same replay, same/different conflict, in-flight policy를 구현한다.
- [x] PostgreSQL concurrent acquire와 expired lease takeover를 검증한다.
- [x] stale owner finalize 거부를 검증한다.
- [ ] terminal retention cleanup을 검증한다.

### 5. Spring Modulith publication

- [x] `bluetape4k-exposed-spring-modulith` auto-configuration을 연결한다.
- [x] command transaction에서 stable domain event를 publish한다.
- [x] listener failure가 FAILED publication으로 남고 bounded replay 후 한 번만 반영됨을 검증한다.
- [x] completed/failed/incomplete count와 oldest lag를 query projection에 포함한다.

### 6. Provider inbox와 lifecycle orchestration

- [ ] deterministic fake mode별 contract test를 작성한다.
- [x] duplicate/out-of-order/conflicting provider event 분류를 구현한다.
- [x] payment success 후 inventory commit, 그 후 fulfillment request 순서를 event로 연결한다.
- [ ] inventory commit failure는 `RECONCILIATION_REQUIRED`로 남긴다.

### 7. Split fulfillment, cancellation, refund

- [x] order line을 두 fulfillment group으로 나누는 lifecycle 처리를 구현한다.
- [x] group별 shipped/delivered revision을 독립 갱신한다.
- [x] 미배송 line partial cancel과 refund case 생성을 별도 audit로 남긴다.
- [x] `CancellationCase`와 `RefundCase`를 독립 aggregate/revision으로 노출한다.
- [x] cancel/refund bounded reason code를 browser snapshot에 노출한다.

### 8. Browser UI와 SSE

- [x] `RANDOM_PORT + WebTestClient.bindToServer()`로 REST query/command와 실제 Tomcat 경계를 검증한다.
- [x] snapshot-first SSE, event cursor, `Last-Event-ID`, heartbeat를 구현한다.
- [ ] emitter limit/timeout/disconnect와 virtual-thread executor close를 검증한다.
- [x] Tomcat fallback threads/max connections 8000과 60초 timeout을 검증한다.
- [x] Hikari pool 8을 유지하고 connection/transaction timeout 60초를 검증한다.
- [x] static UI에서 revision, line/fulfillment 잔여 수량, cancellation/refund, backlog, unresolved event, reason을 렌더링한다.
- [x] UI에서 fulfillment 진행, 부분 취소, delayed payment reconciliation을 실행한다.

### 9. 운영성과 실패 안전

- [ ] application-owned low-cardinality metric을 추가한다.
- [x] raw key/customer/payload가 출력되지 않는 redacted log test를 추가한다.
- [x] 모든 운영 component에 `bluetape4k-logging`을 적용하고 raw key/payload를 제외한다.
- [x] operator reconciliation endpoint의 batch bound와 안정 error code를 검증한다.
- [ ] restart 후 PostgreSQL 상태/inbox/publication을 재구성하는 통합 테스트를 추가한다.

### 10. 저장소 통합과 검증

- [x] Java 21 workflow runtime을 덮지 않고 module toolchain으로 Java 25를 provision하며 full container-backed group에 모듈을 추가한다.
- [x] module/group README, root bilingual index, AGENTS, lesson/review를 갱신한다.
- [x] module README에 영문·한글 예제 시나리오와 Architecture/Sequence Diagram을 추가한다.
- [x] targeted tests, compile, smoke/full, actionlint, stale-check, scoped README/diagram validator를 실행한다.
- [x] `git diff --check`와 dependency insight 결과를 최종 review 문서에 기록한다.

전역 README language/parity validator의 유일한 실패는 변경 범위 밖인
`image-processing/profile-image-moderation/README.md`의 기존 language switch와 한글 표기다.
#532가 변경한 세 README 쌍은 language switch, heading, code fence, image target parity를
별도 범위 검사로 통과했다.

## Six-lens 계획 리뷰

| Lens | 확인 결과 | 조치 |
|---|---|---|
| 요구사항 | 주문/결제/재고/배송/환불, browser, duplicate/out-of-order, idempotency를 모두 추적 | acceptance를 각 테스트 단계에 연결 |
| 아키텍처 | 상태 머신별 revision과 publication 책임이 분리됨 | 거대 order status와 신규 공용 outbox 금지 |
| 데이터 일관성 | PostgreSQL이 HTTP 결과와 업무 상태의 최종 권위 | lease token, CAS revision, unique inbox/audit 키 적용 |
| 실패/보안 | interrupted owner, failed listener, delayed callback, SSE disconnect 포함 | payload/key redaction과 bounded recovery API 적용 |
| 테스트 | 단위, PostgreSQL 동시성, Modulith replay, MVC/SSE, restart 계층 존재 | H2/time sleep/외부 network에 의존하지 않음 |
| 운영 | publication lag, unresolved event, reason, revision을 화면/metric에서 확인 | low-cardinality 집계와 operator batch limit 적용 |

리뷰 결론: 구현을 막는 P0/P1 누락은 0건이다. Redis, Kafka, 실제 payment provider,
공용 idempotency 모듈은 현재 범위에 추가하지 않는다.

## 완료 증거

- 상태 머신과 terminal revision tests 통과
- PostgreSQL same/same, same/different, concurrent owner, lease recovery tests 통과
- failed publication replay와 duplicate listener test 통과
- split shipment + partial cancellation + refund browser contract test 통과
- duplicate/out-of-order provider event가 terminal revision을 바꾸지 않음
- SSE snapshot/reconnect/lifecycle test 통과
- Java 25 compile/test와 JDK25 virtual-thread provider resolution 확인
- actionlint, stale-check, README language/link validator, `git diff --check` 통과

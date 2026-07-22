# Issue #552 — SaaS Usage Metering & Billing Ledger 설계

## 1. 목표

`commerce/usage-metering-billing-ledger`에 Spring Boot 4 기반의 실전형 SaaS 사용량 과금 예제를 추가한다. 이 예제는 단순히 `quantity * unitPrice`를 계산하는 샘플이 아니라, 운영 중 실제로 문제가 되는 다음 상황을 PostgreSQL 권위 모델로 해결한다.

- 동일한 사용량 이벤트의 반복 전달과 payload 충돌
- 늦게 도착하거나 순서가 뒤바뀐 사용량 이벤트
- billing period 도중 변경되는 가격
- 여러 인스턴스가 동시에 시작하는 period close
- close 도중 장애와 재시작
- finalized invoice를 직접 수정하지 않는 debit/credit 정정
- ledger와 invoice 사이의 재현 가능한 reconciliation
- 모든 데이터 경계의 tenant isolation

현재 구현은 normalized authoritative state와 append-only billing ledger를 사용한다. Full Event Sourcing은 별도 후속 이슈 [#553](https://github.com/bluetape4k/bluetape4k-workshop/issues/553)에서 동일한 black-box 계약과 결정적 시나리오를 기준으로 비교한다.

## 2. 성공 기준

1. 사용량 수집, 가격 선택, close, invoice 생성, 정정, reconciliation이 PostgreSQL transaction과 conditional update로 안전하게 연결된다.
2. 모든 production/fixture DB 경로는 JetBrains Exposed DSL만 사용한다. raw SQL과 JDBC escape hatch를 만들지 않는다.
3. 모든 concrete repository는 `ExposedJdbcRepository` 계열 계약을 직접 또는 공통 delegate를 통해 구현한다.
4. 동일한 command는 같은 terminal result를 replay하고, 동일 key의 다른 payload는 `409 Conflict`로 거부한다.
5. close worker가 중단되어도 checkpoint부터 재개하며 ledger entry를 중복 생성하지 않는다.
6. finalized ledger/invoice는 수정하지 않는다. 사후 변경은 연결된 debit/credit entry로 남긴다.
7. PostgreSQL Testcontainers에서 concurrency, restart, late event, adjustment, reconciliation, tenant isolation을 검증한다.
8. 영문·한국어 README와 architecture/state/sequence diagram, microservice extraction guide가 구현과 일치한다.

## 3. 범위

### 포함

- Spring MVC 기반 command/query/operator API
- Java 25 toolchain과 virtual thread runtime
- tenant-scoped usage ingestion
- flat per-unit price version과 effective window
- billing period lifecycle과 resumable close run
- append-only debit/credit ledger
- immutable issued invoice와 invoice line provenance
- late usage 및 operator adjustment
- read-only reconciliation report와 명시적 repair command
- deterministic fake clock/failpoint를 이용한 장애 시나리오
- Micrometer observation, health indicator, low-cardinality metrics
- modular monolith에서 microservice로 분리하는 단계별 가이드

### 제외

- graduated tier, volume discount, committed use, minimum charge
- 세금, 환율 변환, revenue recognition
- 실제 payment/tax/accounting provider
- Kafka 또는 Redis를 correctness authority로 사용하는 구조
- distributed exactly-once 주장
- generic billing framework 또는 generic workflow engine
- Full Event Sourcing과 CQRS projection infrastructure

복잡한 가격 정책은 별도 pricing engine으로 분리할 수 있지만, 이 예제의 학습 목표는 가격 공식보다 ingestion/close/ledger consistency이다. 따라서 baseline은 event-time 기준의 immutable flat rate version에 집중한다.

## 4. 현재 근거와 재사용 결정

| 근거 | 재사용 | 경계 |
|---|---|---|
| `spring-boot/multi-tenant-data-isolation` | 모든 query/update에 tenant predicate를 포함하는 repository 구조 | unsafe 비교용 repository는 새 예제에 두지 않는다. production-safe 경로만 제공한다. |
| `commerce/reservation-control-plane` | PostgreSQL command idempotency, fingerprint conflict, expired owner takeover 모델 | raw credential은 저장·로그하지 않고 digest만 저장한다. |
| `commerce/pre-generated-voucher-pool` | Java 25, Spring Boot 4, virtual thread, Exposed/Testcontainers Gradle 구성 | voucher 전용 batch abstraction은 가져오지 않는다. |
| `leader/job-safety-lab` | checkpoint, compare-and-set, restart proof, concrete `ExposedJdbcRepository` delegate | Redis fencing/leader는 필요하지 않다. PostgreSQL close run token과 checkpoint가 correctness authority이다. |
| `bluetape4k-dependencies` BOM | 모든 Bluetape4k artifact version authority | 개별 Bluetape BOM과 명시적 module version을 추가하지 않는다. |

GNO의 `bluetape4k-github`, `bluetape4k-docs`에서 `usage metering billing ledger` 검색 결과는 없었다. GitHub issue 전체 검색에서도 동일한 baseline 예제는 없었다. CodeGraph는 현재 worktree에서 알려진 Kotlin 파일을 0건으로 반환했으므로, 구체적인 source 근거는 bounded file read와 literal search로 보완했다.

## 5. 선택한 접근과 대안

### 선택: normalized workflow state + append-only financial ledger

사용량과 가격, close workflow 상태는 normalized table로 관리한다. 금전적 결과인 ledger entry와 issued invoice는 immutable하게 유지한다. 이 방식은 일반적인 SaaS 팀이 운영·조회·정정하기 쉽고, 재현성과 감사 가능성도 확보한다.

### 기각: 모든 aggregate의 Full Event Sourcing

Event Sourcing은 event schema evolution, replay, snapshot, projection lag, poison event까지 운영해야 한다. 필요한 조직도 있지만 baseline으로 사용하면 핵심 과금 문제보다 infrastructure가 더 커진다. 후속 #553이 이 비용을 동일 계약으로 비교한다.

### 기각: mutable invoice row 중심 설계

invoice total과 line을 직접 갱신하면 사후 정정이 원래 기록을 덮어쓰고, 장애 후 어떤 사용량과 가격이 반영되었는지 재현하기 어렵다. 학습 예제로도 잘못된 운영 습관을 남긴다.

### 기각: Kafka-first 또는 Redis-first 설계

분산 queue/cache를 먼저 두어도 PostgreSQL의 idempotency, close boundary, ledger uniqueness 문제는 사라지지 않는다. baseline은 한 DB에서 불변식을 명확히 증명하고, README의 extraction 단계에서 outbox와 broker를 추가한다.

## 6. 상위 아키텍처

```text
HTTP adapters
  ├─ Usage ingestion API
  ├─ Pricing/Billing command API
  ├─ Invoice query API
  └─ Operator reconciliation API
          │
Application services
  ├─ IdempotentCommandExecutor
  ├─ UsageIngestionService
  ├─ PricingService
  ├─ BillingPeriodService
  ├─ BillingCloseWorker
  ├─ AdjustmentService
  └─ ReconciliationService
          │
Domain policies
  ├─ PriceSelector
  ├─ MoneyCalculator
  ├─ BillingPeriodPolicy
  └─ LedgerRebuilder
          │
ExposedJdbcRepository implementations
          │
PostgreSQL authoritative tables
```

Controller는 tenant/credential/validation과 HTTP mapping만 담당한다. transaction boundary는 application service의 Spring `@Transactional` method가 소유하며 repository는 이미 열린 transaction 안에서 Exposed DSL을 실행한다. domain policy는 순수 함수로 유지해 Clock, DB, Spring 없이 단위 테스트할 수 있게 한다.

## 7. 패키지와 구성 요소

기본 package는 `io.bluetape4k.workshop.commerce.metering`이다.

| 패키지 | 책임 |
|---|---|
| `config` | Java 25 virtual thread, immutable properties, security, Clock bean |
| `domain` | value object, state enum, money/price/period policy |
| `persistence` | Exposed tables, entities/records, concrete repositories, row mapping |
| `idempotency` | command scope, fingerprint, acquire/replay/conflict state |
| `application` | ingestion, pricing, close, adjustment, reconciliation, receipt cleanup orchestration |
| `web` | request/response DTO, controllers, error mapping |
| `observability` | observation names, metrics, health/detail contributors |
| `testing` | production source에는 두지 않으며 test source의 deterministic fixtures로 제한 |

공통 repository delegate는 `MeteringExposedJdbcRepository<E, ID>`로 제한한다. 이는 `SimpleExposedJdbcRepository`에 위임해 concrete repository가 `ExposedJdbcRepository` contract를 충족하도록 돕지만, query DSL이나 transaction을 감추는 별도 framework가 아니다.

ledger와 issued invoice 계열은 `AppendOnlyMeteringExposedJdbcRepository<E, ID>`를 사용한다. 이 delegate는 상속받은 `delete*`와 existing-entity `save` 경로를 명시적으로 거부한다. Entity와 concrete repository는 module 내부로 제한하고 application service에는 insert/find/list만 가진 narrow port를 주입한다. DB 외부의 임의 접근까지 완전히 막는다고 주장하지 않지만, 예제의 production path와 public Spring bean surface에서는 update/delete가 불가능해야 한다.

### 7.1 주요 configuration 기본값

| Property | 기본값 | 제약 |
|---|---:|---|
| command receipt lease | 30초 | 5초 이상 5분 이하 |
| command receipt retention | 24시간 | lease보다 길어야 함 |
| allowed lateness | 48시간 | period 생성 시 snapshot |
| occurred-at retention horizon | 400일 | 더 오래된 event는 거부 |
| occurred-at future skew | 5분 | server Clock 기준 |
| close batch size | 200 | 1 이상 1,000 이하 |
| max batches per scheduler tick | 5 | 1 이상 20 이하 |
| close scheduler delay | 5초 | test/demo에서 비활성화 가능 |
| reconciliation finding page | 200 | 최대 500, `hasMore` 제공 |
| terminal idempotency response | 16 KiB | 초과 response는 저장/반환하지 않도록 API 자체를 bounded하게 설계 |

모든 property는 immutable `@ConfigurationProperties`로 제공하고 시작 시 validation한다. period나 receipt의 해석에 영향을 주는 값은 row 생성 시 snapshot해 설정 변경이 과거 결과를 바꾸지 않게 한다.

## 8. 핵심 도메인 규칙

### 8.1 Tenant와 identifier

- `TenantId`, `MeterCode`, `SourceSystem`, `SourceEventId`, `IdempotencyKey`는 validation을 수행하는 value object다.
- UUID identity는 Bluetape4k UUID generator를 사용한다.
- 모든 table은 `tenant_id`를 갖고, 모든 lookup/update/delete 조건에 tenant predicate를 포함한다.
- URL의 resource ID가 UUID여도 tenant predicate를 생략하지 않는다.
- raw API key, idempotency key, source payload는 operational log에 기록하지 않는다.

### 8.2 수량과 금액

- usage quantity는 양수 `BigDecimal`, 최대 scale 6이다.
- unit price는 음수가 아닌 `BigDecimal`, 최대 scale 6이다.
- currency는 ISO 4217 세 글자 code다.
- charge amount는 `quantity * unitPrice` 후 currency 기본 fraction digit으로 `RoundingMode.HALF_UP`을 적용한다.
- debit/credit direction과 amount를 분리한다. 저장되는 amount는 항상 0 이상이며 sign 해석은 entry type이 담당한다.
- 서로 다른 currency의 ledger entry를 한 invoice에 합치지 않는다.
- `occurredAt`은 UTC `Instant`로 받고 configurable retention horizon과 future-skew 범위 안인지 검증한다.
- `receivedAt`은 request에서 받지 않고 server-side `Clock`으로만 생성한다. close cutoff는 이 값을 기준으로 한다.

### 8.3 가격 version

`PriceVersion`은 tenant, meter, currency, `effectiveFrom` inclusive, `effectiveTo` exclusive, unit price를 가진다.

- ACTIVE price window는 동일 tenant/meter/currency에서 겹칠 수 없다.
- activation transaction은 tenant/meter/currency별 `PricingSchedule` authority row를 `insertIgnore` 후 다시 읽어 `FOR UPDATE`로 잠그고 overlap을 Exposed query로 검사한 뒤 insert한다.
- ACTIVE version의 unit price와 `effectiveFrom`은 수정하지 않는다. 새 version activation transaction만 직전 open-ended version의 `effectiveTo`를 새 `effectiveFrom`으로 한 번 닫을 수 있으며, 닫힌 window는 reopen하거나 이동하지 않는다.
- 일반 activation의 `effectiveFrom`은 server `Clock`의 현재 시각보다 과거일 수 없다. uncovered historical gap을 채우는 operator repair는 overlap이 없고 해당 window에 이미 생성된 ledger entry가 없을 때만 허용한다.
- usage는 `occurredAt`에 유효한 version으로 가격을 선택한다. `receivedAt`은 close cutoff 포함 여부에만 사용한다.
- 유효한 price가 없으면 ingestion 자체는 보존하되 `UNPRICED`로 조회되며 close finalization을 차단한다.

## 9. 데이터 모델

### 9.1 `metering_command_receipt`

- tenant, operation, key digest의 unique scope
- request fingerprint
- `IN_PROGRESS`, `SUCCEEDED`, `FAILED`
- owner token, lease deadline, retention deadline
- terminal HTTP status와 bounded response body

새 owner, expired lease takeover, terminal replay, fingerprint conflict, active owner를 구분한다. acquire는 짧은 `REQUIRES_NEW` transaction에서 `IN_PROGRESS` owner를 durable하게 만든다. domain mutation과 terminal receipt 전환은 두 번째 transaction에서 owner token CAS로 함께 commit한다. 따라서 domain commit만 성공하고 receipt가 `IN_PROGRESS`로 남는 창을 만들지 않는다. domain transaction 전 process가 종료되면 lease 만료 후 동일 fingerprint가 takeover할 수 있다. deterministic 4xx는 bounded terminal failure로 저장하지만, DB unavailable 같은 transient 5xx는 terminal result로 고정하지 않고 lease takeover를 허용한다.

fingerprint는 field order, decimal normalization, timestamp format을 고정한 canonical representation에 operation domain을 구분해 SHA-256으로 계산한다. raw idempotency key와 raw request body는 저장하지 않는다. terminal response body는 16 KiB 이하의 canonical JSON으로 제한한다.

`CommandReceiptCleanupWorker`는 retention이 지난 terminal receipt만 keyset/bounded batch로 삭제한다. live `IN_PROGRESS` receipt와 아직 lease takeover 가능성이 있는 row는 삭제하지 않는다. 여러 instance가 실행해도 delete predicate가 terminal status와 expiry를 다시 확인한다.

### 9.2 `metering_meter`

- tenant-scoped meter code와 unit
- 활성 상태
- 설명은 운영 표시용이며 가격 계산에 영향을 주지 않는다.

### 9.3 `metering_pricing_schedule` / `metering_price_version`

- schedule은 overlap 검사를 직렬화하는 작은 authority row다.
- version은 immutable price window와 unit price를 보존한다.
- invoice line은 적용한 price version ID를 반드시 보존한다.

### 9.4 `metering_usage_event`

- tenant, source system, source event ID unique
- normalized fingerprint
- meter, quantity, occurred/received timestamp
- accepted actor/correlation
- pricing status는 derived query로 판단하며 row를 재작성하지 않는다.

usage event는 append-only다. 동일 source event가 같은 fingerprint로 다시 오면 기존 결과를 replay하고, 다른 fingerprint면 conflict다.

### 9.5 `metering_billing_calendar` / `metering_billing_period`

- calendar는 tenant/currency period overlap 검사를 직렬화하는 authority row다.
- tenant, currency, `[startsAt, endsAt)`
- `OPEN`, `CLOSING`, `FINALIZED`
- version
- allowed-lateness deadline
- active close run ID와 cutoff received time
- finalized timestamp와 invoice ID

동일 tenant/currency의 period는 겹치지 않는다. 새 period 생성 시 calendar row를 `insertIgnore` 후 다시 읽어 `FOR UPDATE`로 잠그고 Exposed query로 overlap을 검사한다.

### 9.6 `metering_close_run`

- period와 run ID
- cutoff received time
- `RUNNING`, `READY_TO_FINALIZE`, `FINALIZED`, `FAILED_VALIDATION`
- last processed `(occurredAt, usageEventId)` composite ordering key
- scanned/priced/unpriced counters
- checkpoint version과 last error category

raw stack trace나 payload는 저장하지 않는다. worker가 다시 실행되면 run ID와 checkpoint version을 확인해 다음 batch만 처리한다.

close scan은 offset pagination을 사용하지 않는다. `(tenant_id, occurred_at, usage_event_id)` keyset index와 `(tenant_id, received_at)` cutoff index를 두고 composite keyset pagination을 사용한다. batch size와 한 worker invocation의 최대 batch 수는 immutable configuration property로 제한한다.

### 9.7 `metering_ledger_entry`

- posting period와 service period
- `CHARGE`, `DEBIT_ADJUSTMENT`, `CREDIT_ADJUSTMENT`
- source reference type/ID와 unique idempotency key
- meter, price version, quantity, unit price, amount, currency
- related original entry, reason, actor, created timestamp

ledger repository는 insert/find/list만 제공한다. update/delete API를 제공하지 않는다. source reference는 non-null canonical value로 저장해 nullable unique-key hole을 피한다.

### 9.8 `metering_invoice` / `metering_invoice_line` / `metering_invoice_line_entry`

- invoice는 tenant/period/currency 기준 한 건이며 unique하다.
- line은 meter, price version, entry type 단위로 집계한다.
- line-entry mapping이 어떤 ledger entry가 합계에 포함되었는지 보존한다.
- invoice total은 line amount 합과 같아야 한다.
- issued invoice에는 update/delete API를 제공하지 않는다.

### 9.9 `metering_reconciliation_run` / `metering_reconciliation_finding`

- run scope, 시작/완료 시간, deterministic summary
- `UNLEDGERED_USAGE`, `UNLEDGERED_USAGE_AFTER_CUTOFF`, `LEDGER_PRICE_MISMATCH`, `INVOICE_LINE_MISMATCH`, `INVOICE_TOTAL_MISMATCH`, `TENANT_OR_CURRENCY_MISMATCH`
- 대상 resource ID와 expected/actual digest 또는 금액

reconciliation 조회는 상태를 고치지 않는다. repair는 별도 idempotent command로 실행하며 새로운 ledger entry 또는 close checkpoint만 추가한다.

## 10. 상태 모델

### 10.1 Billing period

```text
OPEN
  ├─ close(now < endsAt + allowedLateness) ──> REJECTED / state unchanged
  └─ close(valid, CAS version) ──────────────> CLOSING

CLOSING
  ├─ process batch ─────────────────────────> CLOSING / checkpoint advances
  ├─ unpriced event exists ─────────────────> CLOSING / finalize blocked
  ├─ worker interruption ───────────────────> CLOSING / retry resumes
  └─ finalize(CAS run + version) ───────────> FINALIZED

FINALIZED
  ├─ same close idempotency key ────────────> replay issued invoice
  ├─ different close request ───────────────> conflict
  └─ correction ────────────────────────────> append adjustment to next OPEN period
```

`CLOSING`을 취소해 `OPEN`으로 되돌리는 API는 제공하지 않는다. 잘못 시작한 close는 operator가 validation finding을 해결한 뒤 같은 run을 재개한다. finalized period를 reopen하지 않는다.

### 10.2 Command receipt

```text
ABSENT -> IN_PROGRESS(owner, lease)
IN_PROGRESS + same fingerprint + live lease -> 409 command_in_progress
IN_PROGRESS + same fingerprint + expired lease -> IN_PROGRESS(new owner)
IN_PROGRESS + different fingerprint -> 409 fingerprint_conflict
IN_PROGRESS -> SUCCEEDED | FAILED
SUCCEEDED | FAILED + same fingerprint -> terminal replay
```

### 10.3 Close run

```text
RUNNING -> RUNNING(checkpoint advances)
RUNNING -> FAILED_VALIDATION(unpriced or invariant finding)
FAILED_VALIDATION -> RUNNING(after explicit repair, same run)
RUNNING -> READY_TO_FINALIZE(no eligible rows remain)
READY_TO_FINALIZE -> FINALIZED(invoice committed)
```

## 11. 주요 흐름

### 11.1 Usage ingestion

1. trusted authentication adapter가 tenant와 actor를 확정한다.
2. server `Clock`으로 `receivedAt`을 생성하고 request를 normalize해 fingerprint를 계산한다.
3. 짧은 acquire transaction에서 command receipt owner를 확정한다.
4. domain transaction에서 `(tenant, source, sourceEventId)` 기존 row를 확인한다.
5. 동일 fingerprint면 새 command receipt에 기존 result를 terminal replay로 연결하고, 다른 fingerprint면 conflict를 기록한다.
6. 새 usage event와 terminal command receipt를 domain transaction에서 함께 commit한다.
7. response는 `201 Created`, replay는 원래 terminal status/body와 `Idempotency-Replayed: true` header를 사용한다.

### 11.2 Price activation

1. tenant/meter/currency schedule row를 잠근다.
2. 새 window와 겹치는 ACTIVE version, historical-gap repair 시 해당 window의 기존 ledger를 조회한다.
3. 정상 activation이면 직전 open-ended window를 한 번 닫고 새 immutable unit-price version을 추가한다. gap repair는 기존 window를 변경하지 않는다.
4. 동일 command replay는 원래 version을 반환한다.

### 11.3 Period close start

1. `now >= endsAt + allowedLateness`를 확인한다.
2. `OPEN + expectedVersion` CAS로 `CLOSING` 전환한다.
3. close run ID와 `cutoffReceivedAt=now`를 고정한다.
4. 다른 worker/request는 active run을 관찰하고 새 run을 만들지 않는다.

close start는 `202 Accepted`와 close run location을 반환한다. 같은 idempotency key는 동일 run을 replay한다. 다른 key로 이미 CLOSING인 period를 닫으려 하면 `active_close_exists` conflict를 반환한다.

### 11.4 Close batch processing

1. `(occurredAt, usageEventId)` canonical order와 checkpoint 이후 조건으로 bounded batch를 읽는다.
2. service period 안에 있고 `receivedAt <= cutoffReceivedAt`인 usage만 대상이다.
3. 각 usage의 `occurredAt`에 유효한 price version을 선택한다.
4. price가 없으면 finding/counter를 남기고 finalization을 차단한다.
5. price가 있으면 source-reference unique key로 CHARGE ledger entry를 append한다.
6. 같은 transaction에서 checkpoint를 previous version CAS로 전진시킨다.
7. 이미 처리한 batch를 재실행해도 unique source reference와 checkpoint CAS 때문에 결과가 늘어나지 않는다.

`BillingCloseScheduler`는 configurable fixed delay로 RUNNING close run을 bounded하게 poll한다. 여러 application instance에서 동시에 실행해도 period/run/checkpoint CAS가 correctness를 보장한다. scheduler를 끄는 test/demo profile에서는 operator `process-next` endpoint가 동일 application use case를 호출한다. leader election은 throughput 최적화로 추가할 수 있지만 correctness prerequisite가 아니다.

### 11.5 Finalization

1. cutoff 대상 중 ledger가 없는 usage와 unpriced usage가 없는지 확인한다.
2. ledger를 meter/price version/entry type별로 집계한다.
3. immutable invoice, line, line-entry provenance를 생성한다.
4. period/run/token/version을 조건으로 `FINALIZED` CAS를 수행한다.
5. invoice와 period 전환은 같은 transaction에서 commit한다.

### 11.6 Late usage와 adjustment

- period 범위에 속하지만 close cutoff 이후 받은 usage는 원 period invoice에 끼워 넣지 않는다.
- reconciliation이 해당 usage를 `UNLEDGERED_USAGE_AFTER_CUTOFF`로 표시한다.
- operator repair command는 server `Clock`의 posting time을 포함하는 유일한 OPEN period에 DEBIT adjustment를 append하고 service period와 late usage를 참조한다.
- 과다 청구나 계약상 credit은 original ledger entry를 참조하는 CREDIT adjustment로 append한다.
- adjustment amount는 양수이며 direction은 entry type으로 표현한다.
- 다음 OPEN period가 없거나 currency가 다르면 command를 거부한다.

### 11.7 Reconciliation

reconciliation은 다음 값을 독립적으로 다시 계산한다.

1. close cutoff 대상 usage set
2. usage별 기대 price version과 expected charge
3. source reference별 ledger cardinality
4. invoice line별 ledger aggregation
5. invoice header total

결과는 finding으로 저장하지만 자동 수정하지 않는다. repair command는 finding ID를 입력받아 현재 상태와 digest가 여전히 같은지 확인한 뒤 실행한다. stale finding이면 `409`로 거부한다.

## 12. HTTP 계약

| Method | Path | 의미 |
|---|---|---|
| `POST` | `/api/v1/tenants/{tenantId}/meters` | meter 등록 |
| `POST` | `/api/v1/tenants/{tenantId}/price-versions` | price version 활성화 |
| `POST` | `/api/v1/tenants/{tenantId}/usage-events` | usage ingest/replay/conflict |
| `POST` | `/api/v1/tenants/{tenantId}/billing-periods` | OPEN period 생성 |
| `POST` | `/api/v1/tenants/{tenantId}/billing-periods/{periodId}/close` | close run 시작 또는 replay |
| `POST` | `/api/v1/operator/tenants/{tenantId}/close-runs/{runId}/process-next` | 한 bounded batch 처리 |
| `GET` | `/api/v1/tenants/{tenantId}/billing-periods/{periodId}` | period/close 상태 조회 |
| `GET` | `/api/v1/tenants/{tenantId}/invoices/{invoiceId}` | invoice와 provenance 조회 |
| `POST` | `/api/v1/operator/tenants/{tenantId}/adjustments` | debit/credit adjustment append |
| `POST` | `/api/v1/operator/tenants/{tenantId}/reconciliations` | read-only reconciliation 실행 |
| `POST` | `/api/v1/operator/tenants/{tenantId}/reconciliation-findings/{findingId}/repair` | stale-safe 명시적 repair |

모든 command endpoint는 `Idempotency-Key`를 요구한다. usage request는 추가로 `sourceSystem`과 `sourceEventId`를 요구한다. operator endpoint는 tenant-scoped operator credential을 요구한다. 예제 credential adapter는 digest comparison과 role 분리를 보여주며, README는 production에서 gateway/JWT claim으로 교체하는 경계를 설명한다.

## 13. 오류 계약

| HTTP | code | 조건 |
|---|---|---|
| `400` | `invalid_request` | value object/DTO validation 실패 |
| `401` | `invalid_credential` | ingest/operator credential 불일치 |
| `403` | `tenant_scope_denied` | authenticated tenant와 path tenant 불일치 |
| `404` | `resource_not_found` | tenant predicate 안에서 resource 없음 |
| `409` | `fingerprint_conflict` | 같은 idempotency/source key의 다른 payload |
| `409` | `command_in_progress` | live lease owner가 처리 중 |
| `409` | `state_conflict` | expected version/state/run 불일치 |
| `409` | `stale_reconciliation_finding` | finding 이후 authority가 변경됨 |
| `422` | `pricing_unavailable` | close/finalize/repair 대상 usage의 event-time에 유효한 price 없음; ingestion은 usage를 보존함 |
| `422` | `close_not_ready` | lateness deadline 이전 또는 unresolved finding 존재 |

응답과 log는 raw key, token, payload, stack trace를 노출하지 않는다. correlation ID, tenant, operation, stable error code, digest prefix처럼 bounded context만 기록한다.

## 14. Transaction과 concurrency 불변식

1. command receipt acquire는 짧은 `REQUIRES_NEW` transaction이며, domain mutation과 terminal receipt CAS는 다음 transaction에서 함께 commit한다.
2. terminal receipt update는 current owner token과 `IN_PROGRESS` 상태를 조건으로 한다.
3. period state change는 `(tenantId, periodId, expectedState, expectedVersion)` predicate를 사용한다.
4. close batch checkpoint는 `(runId, expectedCheckpointVersion)` CAS를 사용한다.
5. ledger source reference unique key는 batch retry의 중복 insert를 막는다.
6. invoice unique period key와 finalization CAS는 concurrent finalizer 중 하나만 성공하게 한다.
7. PostgreSQL unique violation은 무조건 성공으로 해석하지 않는다. authority row를 다시 읽어 fingerprint/state가 같은지 확인한다.
8. DB transaction을 잡은 채 외부 network call을 수행하지 않는다.
9. deadlock/serialization/lock-timeout은 transaction 안에서 무한 재시도하지 않는다. command는 stable retriable error와 `Retry-After`를 반환하고 caller가 동일 idempotency key로 재시도한다.
10. Redis/leader availability는 correctness 판정에 사용하지 않는다.

## 15. 장애 모드와 복구

| 장애 | 탐지 | 복구 |
|---|---|---|
| usage insert 전 process 종료 | receipt lease 만료, usage 없음 | 같은 fingerprint로 takeover 후 재실행 |
| usage commit 후 response 유실 | terminal receipt 존재 | 원 status/body replay |
| close start 직후 종료 | period CLOSING, run checkpoint 초기값 | 같은 run process 재개 |
| ledger batch commit 후 worker 종료 | ledger와 checkpoint가 같은 transaction에 commit됨 | 다음 checkpoint부터 계속 |
| concurrent close start | OPEN/version CAS 한 건만 성공 | loser는 active run 또는 conflict 반환 |
| DB lock timeout/deadlock | stable transient error와 rollback | `Retry-After` 이후 같은 idempotency key로 재시도 |
| price version 누락 | unpriced finding과 blocked finalization | price version 추가 후 같은 run 재개 |
| invoice insert 경쟁 | period unique key와 finalization CAS | committed invoice를 읽어 replay하거나 conflict |
| finalized period에 late usage | reconciliation finding | 다음 OPEN period에 linked DEBIT adjustment |
| stale repair 요청 | finding authority digest 불일치 | 새 reconciliation 요구 |
| cross-tenant identifier 시도 | tenant predicate 결과 없음 | 404/403 정책에 따라 응답, 상세 존재 여부 비공개 |

## 16. Observability

### Metrics

- `metering.usage.ingested{result}`
- `metering.command.idempotency{operation,result}`
- `metering.billing.close{result}`
- `metering.billing.close.batch{result}`
- `metering.billing.unpriced.current`
- `metering.billing.late.current`
- `metering.reconciliation.findings{type}`
- `metering.adjustment.posted{type}`

tenant ID, event ID, period ID는 metric tag로 사용하지 않는다. 상세 식별자는 structured log와 trace correlation에만 둔다.

### Health와 운영 조회

- PostgreSQL connectivity
- 가장 오래된 CLOSING run age
- unresolved unpriced usage count
- last successful reconciliation age
- stale command receipt count

health detail은 credential이나 raw payload를 포함하지 않는다. readiness는 DB 불가처럼 요청 처리가 불가능한 조건에만 실패하고, business backlog는 degraded detail/metric으로 노출한다.

운영 runbook은 `oldest CLOSING age`, checkpoint progress, unpriced count, last reconciliation을 순서대로 확인한다. release rollback은 신규 module job/route를 비활성화하는 것으로 제한하며, 이미 append된 ledger/invoice row를 삭제하거나 되돌리지 않는다.

## 17. 테스트 전략

### 순수 단위 테스트

- value object validation
- price effective-window selection
- amount rounding과 currency mismatch
- period lateness/transition policy
- ledger/invoice deterministic aggregation

### PostgreSQL repository/integration 테스트

- unique source event와 fingerprint conflict
- command lease acquire/replay/takeover
- terminal receipt cleanup이 live/expired `IN_PROGRESS`를 삭제하지 않음
- price window overlap 직렬화
- price activation의 과거시점 거부와 safe historical-gap repair
- server-generated `receivedAt`과 occurred-at skew/retention validation
- tenant-scoped repository predicates
- concurrent OPEN→CLOSING CAS
- batch retry의 ledger cardinality 1
- checkpoint CAS와 restart
- concurrent finalization invoice cardinality 1
- immutable ledger/invoice repository surface
- late usage adjustment와 linked credit
- reconciliation finding과 stale repair rejection

PostgreSQL concurrency가 acceptance authority다. H2는 편의성 테스트에 사용하지 않는다. container test는 `PostgreSQLServer.Launcher`를 재사용하고 별도 Gradle process와 병렬 실행하지 않는다.

### HTTP 테스트

`WebTestClient` JDK connector로 다음을 검증한다.

- new/replay/conflict status와 body 동일성
- validation/error code
- tenant/role isolation
- close start/process/finalize 조회 흐름
- operator adjustment/reconciliation authorization
- response에서 secret/raw payload 비노출
- `Idempotency-Replayed` header와 `202 Accepted` close-run location

### 결정적 장애 fixture

주입 가능한 `Clock`, bounded batch size, `CloseFailpoint` test implementation을 사용한다. failpoint는 production default에서 항상 no-op이며 다음 위치만 대상으로 한다.

- close run 생성 직후
- 한 batch commit 이후 다음 batch 이전
- READY_TO_FINALIZE 진입 이후 invoice transaction 이전

production code에 sleep, random failure, test-only branch를 넣지 않는다.

## 18. Gradle과 repository 등록

- module path: `commerce/usage-metering-billing-ledger`
- Gradle project: `:commerce-usage-metering-billing-ledger`
- Java/Kotlin toolchain: 25, Kotlin JVM target 25
- Spring Boot main class를 명시한다.
- root `bluetape4k-dependencies` platform만 사용한다.
- 기본 test는 빠른 unit/slice test, `integrationTest`는 PostgreSQL/HTTP, `stressTest`는 concurrency 반복으로 분리한다.
- root settings auto-registration 결과를 `./gradlew projects`로 확인한다.
- `.github/workflows/Examples.yml`의 path filter, full job, summary `needs`, artifact를 추가한다.
- container-backed module은 daily smoke가 아니라 full/nightly 그룹에 둔다.
- stale validation script와 repository module map/README locale을 함께 갱신한다.
- publishable library가 아니므로 BOM/publication entry는 추가하지 않는다.

## 19. Bluetape4k 사용 계획

- `bluetape4k-core`: validation과 공통 value helper
- `bluetape4k-logging`: `KLogging` operational logging
- `bluetape4k-idgenerators`: UUID identity
- `bluetape4k-micrometer`: observation/metric integration
- `bluetape4k-virtualthread-api`, `bluetape4k-virtualthread-jdk25`: blocking boundary 실행
- `bluetape4k-exposed-core`, `bluetape4k-exposed-jdbc`, `bluetape4k-exposed-spring-boot-jdbc`: schema/repository/transaction integration
- `bluetape4k-exposed-jdbc-tests`: repository test support
- `bluetape4k-testcontainers`: PostgreSQL launcher
- `bluetape4k-junit5`, `bluetape4k-assertions`: test lifecycle와 assertions

실제 catalog alias는 구현 전에 `gradle/libs.versions.toml`과 유사 Java 25 commerce module에서 다시 검증한다. 기능에 필요하지 않은 Redis, leader, cache, messaging module은 “ecosystem 사용량”을 늘리기 위한 목적으로 추가하지 않는다.

## 20. README와 시각 자료

`README.md`와 `README.ko.md`는 같은 구조와 사실을 유지한다.

`demo` profile은 repository와 application service를 통해 sample tenant, meter, price, period만 seed한다. default profile에서는 seed하지 않는다. fixture와 demo initializer도 raw SQL이나 direct JDBC를 사용하지 않는다.

1. 5분 실행 경로
2. duplicate/conflict/late/close/restart/adjustment/reconciliation curl 시나리오
3. architecture diagram
4. billing-period state diagram
5. ingestion idempotency sequence diagram
6. resumable close/reconciliation sequence diagram
7. tables and invariants
8. dashboard/alert suggestions
9. incident runbook
10. microservice extraction guide
11. baseline과 Advanced #553 선택 기준

diagram은 canonical SVG와 PNG를 함께 저장한다. Graphviz는 사용하지 않는다. 각 asset은 XML parse, CairoSVG scale 2 render, type-specific audit, full-size PNG inspection을 통과해야 한다. diagram label은 두 README가 공유할 수 있도록 English를 기본으로 한다.

## 21. Microservice extraction guide

baseline 코드는 modular monolith로 유지한다. README는 다음 순서의 extraction을 설명한다.

1. **Usage ingestion service 분리**: source-event uniqueness와 command receipt를 함께 이동한다.
2. **Pricing service 분리**: immutable price version을 API/event contract로 제공하되 close는 적용한 version ID를 보존한다.
3. **Billing close service 분리**: period, close run, ledger, invoice를 하나의 consistency boundary로 유지한다.
4. **Query projection 분리**: invoice/operator view는 outbox-fed projection으로 옮길 수 있다.
5. **Reconciliation 독립 운영**: authoritative billing DB를 read하고 finding/repair command만 billing boundary로 전달한다.

DB table을 서비스별로 임의 분할하지 않는다. ingestion과 billing 사이가 비동기화되면 transactional outbox, at-least-once delivery, consumer idempotency를 추가한다. event ordering이나 broker delivery가 financial authority를 대체하지 않는다.

## 22. 보안 경계

- demo credential은 config에서 tenant/role별 digest로 제공한다.
- 비교는 constant-time helper가 있으면 Bluetape4k helper를 우선한다.
- secret은 request context 밖으로 전달하지 않고 log/metric/DB에 raw form으로 남기지 않는다.
- operator command는 ingest credential로 호출할 수 없다.
- path tenant와 authenticated tenant가 다르면 resource 존재 여부를 노출하지 않는다.
- reconciliation report는 tenant-scoped이며 다른 tenant ID를 finding에 포함하지 않는다.
- Actuator exposure는 health/prometheus 최소 surface로 제한한다.

## 23. 호환성과 migration

신규 독립 module이므로 기존 API/schema migration은 없다. `test`와 명시적인 `demo` profile만 Exposed `SchemaUtils` initializer를 활성화한다. default/production profile은 자동 DDL을 수행하지 않는다. Flyway/Liquibase raw migration SQL은 이번 학습 범위에서 제외한다. README는 production 적용 시 review된 migration tool로 동일 schema를 관리하되 repository runtime query는 계속 Exposed로 유지해야 한다고 설명한다.

## 24. 완료 정의

- [ ] 이슈 #552의 required scenario와 acceptance criterion이 구현/테스트/문서에 추적된다.
- [ ] 모든 concrete repository가 `ExposedJdbcRepository` contract를 충족한다.
- [ ] production/test fixture에 raw SQL 또는 직접 JDBC 실행이 없다.
- [ ] PostgreSQL duplicate/concurrency/restart/reconciliation test가 fresh run으로 통과한다.
- [ ] targeted test, module test, detekt, Kover XML/report-only, workflow validation, `git diff --check`가 통과한다.
- [ ] module/workflow/nightly/stale-check/README/AGENTS 등록이 일치한다.
- [ ] 영문·한국어 README 내용이 동등하다.
- [ ] 모든 diagram SVG/PNG가 render/audit/full-size inspection을 통과한다.
- [ ] inline 성능·안정성·보안·운영·API·사용자 관점 검토의 P0/P1이 0이다.
- [ ] lesson과 PR DoD가 exact branch head의 검증 결과를 담는다.
- [ ] 별도 merge 승인 후 rebase merge, local develop sync, merged worktree/feature branch 삭제가 완료된다.

## 25. 설계 단계 위험

| 위험 | 신호 | 완화 |
|---|---|---|
| close batch가 너무 많은 row를 transaction에 포함 | transaction duration/lock wait 증가 | bounded batch와 checkpoint, final aggregate query 분리 |
| price window overlap race | 같은 meter에 두 ACTIVE version | schedule authority row lock과 overlap test |
| unique violation을 duplicate 성공으로 오판 | 다른 fingerprint/state가 기존 row에 존재 | 충돌 후 authority reread와 fingerprint/state 비교 |
| metric cardinality 폭증 | tenant/resource ID tag | 고정 result/type tag만 허용 |
| late usage가 이전 invoice를 변경 | finalized row update 발생 | next-period linked adjustment만 허용 |
| fixture가 production invariant를 우회 | direct insert/raw SQL | fixture도 repository/Exposed DSL 사용 |
| Full Event Sourcing 범위가 baseline에 섞임 | generic event store/projection abstraction 등장 | #553으로 분리하고 baseline production code dependency 금지 |

## 26. Spec self-review와 관점별 검토

사용자 지시에 따라 subagent를 사용하지 않고 동일 artifact를 여섯 관점으로 나누어 순차적으로 inline 검토했다.

| 우선순위 | 관점 | 발견 | 반영 결과 |
|---|---|---|---|
| P1 | 성능 | close scan이 offset 또는 unbounded query로 구현될 여지 | composite keyset, index, batch/tick 상한을 명시 |
| P1 | 안정성 | receipt acquire와 domain transaction을 하나로 설명하면 durable lease와 atomic terminal result가 동시에 성립하지 않음 | acquire `REQUIRES_NEW`, domain+terminal CAS transaction으로 분리 |
| P1 | 보안 | caller가 `receivedAt`을 지정하면 close cutoff를 우회할 수 있음 | server Clock 전용 값, occurred-at horizon/skew, canonical fingerprint를 명시 |
| P1 | 운영 | manual endpoint만으로는 실제 multi-instance worker 운영 방식이 불명확함 | bounded scheduler와 operator endpoint가 동일 use case를 공유하도록 명시 |
| P1 | 개발자/API | `ExposedJdbcRepository`가 CRUD를 상속하므로 append-only 설명만으로 update/delete를 막지 못함 | append-only delegate가 inherited mutation을 거부하고 narrow port만 노출 |
| P1 | 사용자 | 복잡한 시나리오의 첫 실행 경로가 불명확함 | default-off `demo` profile seed와 5분 실행 문서 요구를 추가 |
| P2 | 성능 | invoice line-entry provenance는 데이터량에 비례해 증가 | 감사 추적을 위해 유지하며 period bounded scan, keyset/index, 운영 지표로 비용을 노출 |
| P2 | 운영 | 예제에 production migration SQL이 없음 | raw SQL 금지 범위를 지키고 test/demo만 SchemaUtils, production migration 책임을 README에 명시 |

placeholder, 상충되는 transaction 설명, nullable unique source key, mutable finalized data, Full Event Sourcing 범위 혼입을 다시 검사했다. 최신 결과는 **P0=0, P1=0**이며 P2는 위 표의 명시적 trade-off로 수용한다.

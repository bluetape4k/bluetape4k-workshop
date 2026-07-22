# Issue #555 Event-sourced Usage Billing Microservices 설계

## 1. 배경과 목표

[#552](https://github.com/bluetape4k/bluetape4k-workshop/issues/552)는 PostgreSQL
normalized ledger로, [#553](https://github.com/bluetape4k/bluetape4k-workshop/issues/553)은
단일 Spring Boot application의 Event Sourcing/CQRS로 사용량 과금의 정확성을 보였다.
이 예제는 같은 business contract를 서비스별 PostgreSQL과 Kafka at-least-once delivery로
분리했을 때 새로 생기는 실패 경계를 가르친다.

목표는 microservices를 기본 선택으로 권하지 않는 것이다. 독자가 다음 질문에 production-grade
근거로 답할 수 있어야 한다.

- local transaction 밖에서 publish, duplicate, delay, poison, restart가 생겨도 금액이 중복되지
  않게 하려면 무엇을 durable state로 남겨야 하는가?
- 독립 배포와 independent envelope evolution을 유지하면서, 어느 service가 어느 financial
  decision의 authority인지 어떻게 드러내는가?
- Kafka, Redis, consumer offset을 financial correctness authority로 쓰지 않고도 operator가
  backlog와 recovery를 안전하게 다룰 수 있는가?

## 2. 성공 기준

1. Meter, Usage, Billing, Invoice, Query는 각각 독립 Spring Boot deployment artifact와 own
   PostgreSQL database를 가진다.
2. service 간 table read/write, shared schema, cross-database query, XA transaction은 없다.
3. service-local business state/event stream 변경과 outbox enqueue는 같은 Exposed transaction으로
   commit한다.
4. consumer는 `(tenantId, eventId)` inbox uniqueness와 payload digest를 사용해 duplicate delivery가
   financial effect를 중복하지 않음을 보인다.
5. future sequence, missing predecessor, poison payload, unknown envelope version이 명시적
   `DEFERRED` 또는 `QUARANTINED` 상태와 operator recovery path를 가진다.
6. Pricing, accepted usage, rated charge/adjustment, invoice, query projection의 authority와 tenant
   boundary가 README와 source에서 추적 가능하다.
7. #552/#553 black-box totals, immutable correction, idempotency, tenant isolation contract를
   cross-service composition test에서 재현한다.
8. 모든 database production/fixture operation은 JetBrains Exposed DSL/DAO와
   `bluetape4k-exposed-jdbc`만 사용한다. raw SQL/JDBC/`Transaction.exec`는 추가하지 않는다.
9. README는 architecture/state/sequence diagram과 modular-monolith 대 microservices 선택 가이드,
   staged extraction/rollback runbook을 제공한다.

## 3. 승인된 구조와 대안

### 선택: 물리적으로 분리된 다섯 Spring Boot service module

`settings.gradle.kts`는 `commerce/` 바로 아래 directory를 module로 자동 등록한다. 따라서 다음
five sibling module을 만들고 모두 Java 25 Spring Boot application으로 packaging한다.

```text
commerce/
  usage-billing-meter-service/
  usage-billing-usage-service/
  usage-billing-billing-service/
  usage-billing-invoice-service/
  usage-billing-query-service/
  usage-billing-microservices-composition-tests/
```

마지막 module은 deployable service가 아니라 Kafka와 service별 PostgreSQL을 함께 기동해
black-box contract를 검증하는 test-only composition module이다. 다른 service의 production
domain class, entity, repository를 dependency로 공유하지 않는다.

### 기각: 하나의 module 안에 여러 `main` class

artifact와 compile classpath를 공유하면 service boundary를 위반해도 compiler가 막지 못한다.
독립 배포와 independent schema evolution을 보여주는 예제가 되지 않는다.

### 기각: in-process service simulation

inbox/outbox, consumer restart, topic outage, offset replay, service-owned database를 실제로 검증할
수 없다. 이 방식은 #553 modular application과 구분되는 학습 가치를 만들지 못한다.

### 기각: Kafka EOS 또는 XA를 end-to-end financial guarantee로 주장

Kafka transaction은 Kafka read-process-write에 한정된 도구다. PostgreSQL과 Kafka 사이에는
crash/retry 경계가 남고 database 반영은 idempotent여야 한다. 이 예제는 local outbox/inbox와
idempotent application state만으로 at-least-once delivery를 처리한다.

## 4. Service ownership

| Service | Own database authority | Commands / local stream | Published event | Consumed event |
|---|---|---|---|---|
| Meter | meter, immutable price-version timeline | meter registration, price activation/gap repair | `PriceActivated`, `PriceGapRepaired` | 없음 |
| Usage | accepted/rejected raw usage와 request receipt | usage ingestion/idempotency | `UsageAccepted`, `UsageRejected`, `UsageCorrected` | price availability event |
| Billing | billing period, immutable rated charge와 debit/credit adjustment | period close, rating, correction decision | `ChargeRated`, `AdjustmentPosted`, `BillingPeriodClosed` | pricing, accepted/corrected usage |
| Invoice | immutable invoice/document lineage | issue invoice, record correction document | `InvoiceIssued`, `InvoiceCorrectionIssued` | charge, adjustment, period close |
| Query | operator/customer read models, inbox/quarantine/checkpoint | rebuild/redrive only; financial command 없음 | 없음 | 모든 integration event |

Billing만 charge amount, price-version provenance, late debit/credit adjustment의 financial
authority다. Invoice는 Billing의 immutable financial event를 document로 materialize할 뿐이며,
Query는 어떤 command의 authority도 아니다. price selection이 필요한 Billing은 Meter event를
자신의 database에 replicated pricing evidence로 저장한다. 이 cache/projected evidence는
Meter의 price authority를 대체하지 않는다.

## 5. Integration envelope와 topic contract

runtime에서 shared Kotlin DTO jar를 쓰지 않는다. 각 service는 own codec/compatibility registry로
다음 JSON envelope를 decode하고, composition contract fixture는 JSON sample과 semantic assertion만
공유한다.

| Field | 의미 |
|---|---|
| `eventId` | UUID v7 integration event identity |
| `eventType` / `schemaVersion` | stable discriminator와 independent evolution version |
| `tenantId` | 모든 storage/query/authorization predicate의 tenant |
| `aggregateType` / `aggregateId` / `aggregateVersion` | producer-local ordered aggregate identity |
| `occurredAt` / `recordedAt` | business time과 local durable-record time |
| `correlationId` / `causationId` | operator trace와 causal lineage |
| `payload` / `payloadDigest` | versioned JSON과 SHA-256 conflict detector |
| `producer` | service name과 contract compatibility metadata |

Kafka record key는 `tenantId|aggregateType|aggregateId`다. Kafka ordering은 partition 안에서만
보장되므로, 이 key가 같은 aggregate event를 같은 partition에 보낸다는 사실 외의 global order를
가정하지 않는다. 각 producer는 service-owned topic(`meter.events.v1`, `usage.events.v1`,
`billing.events.v1`, `invoice.events.v1`)에 publish한다. topic 이름의 major version 변경은
parallel read/write migration으로 처리하며 과거 event를 rewrite하지 않는다.

## 6. Local persistence와 Exposed contract

각 service는 아래 persistence boundary를 own database에 둔다.

```text
domain state/event stream + command receipt
          └── same Exposed transaction ──> outbox_event

Kafka listener ──> inbox_event + local effect/checkpoint
                         └── same Exposed transaction
```

- 모든 concrete repository는 service-local `XxxExposedJdbcRepository`를 통해
  `ExposedJdbcRepository`를 구현하고, `SimpleExposedJdbcRepository` delegate를 사용한다.
- service-owned `outbox_events`, `inbox_events`, `quarantine_events`, `consumer_checkpoints`,
  command receipt와 aggregate/entity table은 Exposed table/entity/repository로만 access한다.
- fixture는 `SchemaUtils`, service port, repository를 사용한다. `Connection`, `DriverManager`,
  `JdbcTemplate`, `PreparedStatement`, `Statement`, `Transaction.exec`, migration SQL은 금지한다.
- architecture test는 concrete repository assignability와 raw database API import/usage를 모두
  검사한다.

### Outbox state machine

```text
PENDING -> CLAIMED -> PUBLISHED
             |          ^
             v          |
         RETRY_WAIT -----+
             |
             v
        QUARANTINED
```

service command transaction은 local state/event와 `PENDING` row를 함께 commit한다. publisher는
bounded batch를 conditional claim으로 `CLAIMED`로 바꾼 뒤 Kafka send를 시도한다. send 성공 뒤
`PUBLISHED` mark 전에 process가 죽으면 lease expiry 후 재전송한다. 이것은 duplicate가 가능한
정상 경로이며 receiver inbox가 absorb한다. `QUARANTINED` outbox row는 operator의 explicit
redrive만 허용한다.

### Inbox state machine

```text
RECEIVED -> CLAIMED -> APPLIED
    |          |          |
    |          v          v
    |      DEFERRED   DUPLICATE
    |          |
    v          v
QUARANTINED <- RETRY_WAIT
```

consumer는 먼저 `(tenant_id, event_id)` unique insert를 시도한다. existing row의 digest가 같으면
`DUPLICATE`로 종료하고 local effect를 반복하지 않는다. digest가 다르면 security/correctness
conflict로 `QUARANTINED`한다. 새 record는 aggregate version이 expected next version이면 local
effect와 `APPLIED`를 같은 transaction으로 commit한다. future version 또는 필요한 pricing evidence가
없으면 `DEFERRED`로 저장하고 bounded reconciliation worker가 predecessor/evidence가 도착한 뒤
처리한다. malformed payload, unknown mandatory schema, retry budget exhaustion은
`QUARANTINED`로 저장한다.

quarantine은 affected aggregate의 progression만 block한다. consumer는 durable inbox/quarantine
outcome 뒤 offset을 commit하므로 같은 partition의 무관한 aggregate가 poison record 때문에
영구 정지하지 않는다. redrive는 payload를 바꾸지 않고 새 attempt/audit record를 남긴다.

## 7. 정확성, ordering, correction

- producer aggregate version은 해당 producer database의 optimistic/CAS stream version이다.
- consumer는 `last_applied_version + 1`만 `APPLIED`로 전이한다. lower version은 duplicate이고,
  higher version은 `DEFERRED`다.
- aggregate lock은 raw advisory SQL이 아니라 tenant/aggregate/version 조건부 update와 unique
  constraint로 표현한다. claim/complete는 owner token과 status를 모두 predicate로 둔다.
- Usage correction은 기존 accepted usage를 update하지 않는다. `UsageCorrected`가 Billing에
  도착하면 Billing은 original charge provenance를 reference하는 `AdjustmentPosted` debit/credit을
  append한다.
- Invoice와 Query는 correction event를 소비해 새 document/read-model record를 만들며, finalized
  invoice/ledger row를 overwrite하지 않는다.
- HTTP idempotency는 command service local receipt에만 저장한다. request fingerprint mismatch는
  `409`, terminal replay는 original response와 `Idempotency-Replayed: true`를 반환한다.

## 8. API, security, observability

각 command API는 `/api/v1/tenants/{tenantId}/...` 형태이며 authenticated principal tenant와
path/body tenant가 일치해야 한다. operator endpoint는 `ROLE_OPERATOR`와 explicit tenant scope를
요구한다. Query는 customer read API와 operator diagnostic API만 제공하고 financial mutation endpoint를
제공하지 않는다.

low-cardinality Micrometer metric은 다음을 포함한다.

- `usage_billing_outbox_backlog{service,state}`
- `usage_billing_inbox_outcome_total{service,outcome,event_type}`
- `usage_billing_consumer_lag{service,topic,partition}`
- `usage_billing_projection_lag_seconds{service,projection}`
- `usage_billing_quarantine_backlog{service,reason}`
- `usage_billing_redrive_total{service,outcome}`

tenant ID, event ID, idempotency key, payload, raw exception은 metric tag/log field에 넣지 않는다.
operator API는 backlog, oldest age, deferred cause, quarantine count, redrive eligibility, consumer
checkpoint만 노출하고 financial state를 직접 수정하지 않는다.

## 9. Testcontainers verification matrix

`usage-billing-microservices-composition-tests`는 Kafka 1개와 service-owned PostgreSQL 5개를
`PostgreSQLServer.Launcher` style fixture로 sequentially 기동한다. seed/inspection은 HTTP port,
service port, Exposed repository를 사용한다.

| Scenario | Required proof |
|---|---|
| publish failure | committed command/outbox survives failure, later publish converges |
| duplicate delivery | one inbox outcome와 one financial effect |
| delayed/reordered | explicit `DEFERRED`/`APPLIED`/`QUARANTINED` policy와 no silent skip |
| poison | affected aggregate block, other aggregate progress, operator redrive audit |
| restart | claim expiry, replay, checkpoint/inbox/outbox survive new application context |
| schema evolution | v1/v2 envelope reader compatibility and unknown mandatory version quarantine |
| Kafka outage | bounded outbox backlog, recovery after broker restore, no lost committed command |
| tenant isolation | cross-tenant HTTP/event/read-model access rejected |
| totals parity | #552/#553 shared black-box scenario totals/invoice/correction parity |
| cross-service correction | immutable original + one compensating adjustment/document/read-model result |

default module tests remain container-free. Kafka/PostgreSQL composition tests use a dedicated
`integrationTest` task, run serially with the repository test mutex and `--max-workers=1`, and produce
non-empty JUnit XML/Kover report evidence.

## 10. README, diagrams, workflow, rollout

- 각 deployable service has English/Korean README. A parent guide explains ownership map, topic
  compatibility, failure policy, operation API, modular-monolith comparison, staged extraction, and
  rollback.
- architecture, outbox/inbox state, normal delivery, delayed/poison recovery, correction, and staged
  extraction diagrams use `bluetape-diagram`. SVG and CairoSVG PNG must pass the full diagram checklist,
  including direct arrow-head geometry comparison and full-size PNG inspection.
- root/commerce README locale pairs, `scripts/smoke-validate.sh`, Examples/Nightly workflow paths,
  container lane artifacts, validation/stale checks, lesson and review documents change in the same
  branch.
- staged extraction is Meter/Usage first, Billing next, Invoice/Query last. Every stage dual-runs
  projection parity, has a backlog drain criterion, and rolls back traffic routing only; it never copies
  a database or rewrites financial history.

## 11. Explicit non-goals

- Kubernetes, cloud deployment automation, service discovery, schema registry product adoption
- generalized saga/Event Sourcing/messaging framework
- Redis/leader election as financial authority
- XA transaction, end-to-end exactly-once claim, cross-service synchronous write
- tax, FX, revenue recognition, payment provider integration
- raw SQL/JDBC fixture shortcut or destructive history rewrite

## 12. Implementation invariants

1. PostgreSQL state and stream/version CAS in the owning service are the only correctness authority.
2. Kafka delivery and offsets are transport progress signals, never proof of financial effect.
3. Every retry has a stable durable row, bounded attempt/lease, observable state, and operator action.
4. A service can be redeployed/restarted without reading another service database.
5. All money-changing changes are append-only and trace original event, correlation, causation, and actor.
6. No code is implemented until the accompanying TDD implementation plan passes the six inline review
   lenses and receives separate approval.

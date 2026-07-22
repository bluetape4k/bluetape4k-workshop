# Issue #553 Event-sourced Usage Metering & Billing 설계

## 1. 배경

SaaS 사용량 과금 시스템은 사용량을 받는 것보다 이미 처리한 명령을 다시 받았을 때 같은 결과를 돌려주고, 과거 시점의 가격으로 금액을 재현하며, 마감 이후 정정을 감사 가능한 형태로 남기는 일이 더 어렵다.

[#552](https://github.com/bluetape4k/bluetape4k-workshop/issues/552)는 normalized PostgreSQL state와 append-only financial ledger로 이 문제를 해결한다. 이 구조는 대부분의 서비스 회사에 가장 현실적인 기본안이다. [#553](https://github.com/bluetape4k/bluetape4k-workshop/issues/553)은 같은 black-box 계약을 Event Sourcing과 CQRS로 다시 구현해 다음 질문에 답한다.

- 전체 변경 이력이 실제 비즈니스 가치가 있을 때 Event Sourcing이 무엇을 더 제공하는가?
- optimistic stream concurrency, replay, snapshot, upcasting, projection rebuild를 어떻게 안전하게 운영하는가?
- normalized baseline보다 늘어난 저장 공간, 개발 난이도, 장애 복구 비용을 감수할 기준은 무엇인가?

이 예제는 baseline을 대체하지 않는다. 독자가 두 구현을 같은 시나리오로 비교하고 자신의 시스템에 맞는 구조를 선택하도록 돕는 advanced reference다.

시리즈의 다음 단계는 다음과 같이 분리한다.

- [#555](https://github.com/bluetape4k/bluetape4k-workshop/issues/555): Kafka와 서비스별 PostgreSQL을 사용하는 microservices 구현
- [bluetape4k-projects#1070](https://github.com/bluetape4k/bluetape4k-projects/issues/1070): 최소 두 소비 사례가 확인된 뒤 공통 Event Sourcing primitive 추출 여부 평가

## 2. 성공 기준

1. #552의 겹치는 HTTP command, idempotency, tenant, monetary, close, invoice, adjustment 계약을 production 코드 공유 없이 재현한다.
2. PostgreSQL의 append-only event stream이 aggregate state의 유일한 business authority가 된다.
3. 같은 expected stream version으로 경쟁하는 append 중 하나만 성공한다.
4. 성공하거나 거부된 command replay가 event를 중복 append하지 않고 동일한 terminal response를 돌려준다.
5. 전체 event replay와 유효 snapshot 이후 replay가 동일한 aggregate state와 digest를 만든다.
6. 오래된 event schema를 역사 데이터 수정 없이 deterministic upcaster로 읽는다.
7. usage, ledger, invoice, operator timeline, reconciliation projection을 중단 후 재개하거나 새 generation으로 재구축할 수 있다.
8. duplicate, delayed, interrupted, poison, concurrent projection 처리가 checkpoint와 금액을 손상시키지 않는다.
9. finalized invoice는 수정하지 않으며 correction event가 compensating debit/credit projection을 만든다.
10. 모든 stream, snapshot, command receipt, projection, rebuild, operator API가 tenant 경계를 지킨다.
11. README에서 baseline과 advanced 구조의 선택 기준, 복구 명령, projection lag, microservice 추출 경로를 실제 운영 관점으로 설명한다.

## 3. 범위

### 포함

- Java 25 toolchain을 사용하는 Spring Boot 4 MVC 애플리케이션
- PostgreSQL event store와 global event position
- versioned domain event envelope와 SHA-256 stream hash chain
- application-owned command receipt와 owner-token lease
- aggregate reducer, deterministic replay, optimistic append
- schema-version upcaster chain
- snapshot 생성, 검증, 무효화, fallback replay
- generation 기반 CQRS projection과 durable checkpoint
- duplicate suppression, projector lease, poison-event quarantine
- usage, ledger, invoice, operator timeline, reconciliation projection
- projection lag, rebuild, quarantine, reconciliation operator API
- Spring Security tenant/operator boundary
- Micrometer metric과 health indicator
- #552와 공유 가능한 test-only black-box scenario contract
- PostgreSQL Testcontainers concurrency/recovery/stress test
- 영어/한국어 README와 Architecture/State/Sequence/Rebuild Diagram

### 제외

- 범용 Event Sourcing framework 또는 공개 Bluetape4k API
- Kafka, Redis, distributed leader election을 correctness authority로 사용하는 구조
- microservice runtime 구현
- XA transaction 또는 distributed exactly-once 주장
- tax, foreign exchange, revenue recognition, payment provider 연동
- event history를 수정하는 destructive migration
- event payload 암호화나 external key-management system
- production schema migration 도구 선정

## 4. 승인된 접근과 대안

### 선택: 단일 Spring Boot modular application

한 프로세스와 한 PostgreSQL database 안에서 event store, command handler, projector, operator API를 분리한다. 각 경계는 interface와 별도 table ownership으로 나누지만 배포 단위는 하나다.

이 접근은 Event Sourcing 자체의 비용을 분명하게 보여준다. Kafka delivery, service discovery, 배포 orchestration 같은 분산 시스템 변수를 섞지 않아 replay와 projection correctness를 집중해서 검증할 수 있다. 이후 #555가 같은 event contract를 microservice로 분리한다.

### 기각: 처음부터 microservices

서비스별 database와 Kafka를 동시에 도입하면 outbox/inbox, partition ordering, schema registry, consumer lag가 핵심 설계를 가린다. 이 문제는 필요하지만 #555의 독립된 학습 목표로 둔다.

### 기각: 범용 Event Sourcing framework 선행 개발

현재 확인된 consumer는 billing 예제 하나뿐이다. generic aggregate, serializer, checkpoint API를 먼저 만들면 application 요구를 framework 요구로 오판할 가능성이 높다. 공통화는 #553과 #555에서 반복되는 semantic contract를 확인한 뒤 bluetape4k-projects#1070에서 결정한다.

## 5. 근거와 재사용 결정

### #552에서 재사용하는 계약

- tenant-scoped URL과 인증 사용자 tenant 일치 검증
- `Idempotency-Key`의 acquire/replay/conflict/in-progress semantics
- 성공 replay의 `Idempotency-Replayed: true` header
- event-time price selection과 explicit price-gap repair
- fixed close cutoff와 restartable processing
- immutable invoice와 late debit/credit adjustment
- read-only reconciliation과 stale-safe repair 원칙
- deterministic clock과 PostgreSQL fixture scenario

### #552에서 재사용하지 않는 production 구현

- normalized usage, billing-period, ledger, invoice repository
- conditional workflow-state update
- baseline reconciliation query 구현
- baseline close worker 내부 코드

advanced module은 독립된 package와 table을 사용한다. 비교를 위한 request/response fixture와 black-box test DSL만 test-support source set 또는 test fixture로 추출할 수 있다. production class, entity, repository는 공유하지 않는다.

### Bluetape4k/Exposed에서 재사용하는 기능

- `ExposedJdbcRepository`와 `SimpleExposedJdbcRepository` delegate
- `SpringTransactionManager` 기반 Spring transaction
- Bluetape4k logging, validation, UUID generator, Micrometer helper
- Java 25 virtual-thread runtime
- Jackson 3 serialization
- Bluetape4k JUnit/assertion/Testcontainers fixture

모든 concrete repository는 `ExposedJdbcRepository` contract를 구현한다. append-only repository는 generic `save`/`delete`를 차단하고 좁은 `append`, `load`, `claim`, `advance`, `quarantine` operation만 공개한다.

## 6. 모듈과 package

모듈 경로는 `commerce/usage-metering-billing-event-sourcing`으로 한다.

package prefix는 `io.bluetape4k.workshop.commerce.metering.eventsourcing`이다.

```text
eventsourcing/
  application/       command handler와 orchestration
  domain/            aggregate, command, event, reducer
  eventstore/        envelope, append/load, upcaster, snapshot
  idempotency/       command receipt와 replay
  projection/        projector runtime, checkpoint, generation, quarantine
  projection/usage/  usage read model
  projection/billing/ledger와 invoice read model
  projection/ops/    operator timeline와 reconciliation
  persistence/       Exposed table/entity/repository
  config/            transaction, properties, metrics, health, security
  web/               baseline-compatible API와 operator API
  worker/            bounded projector와 snapshot worker
```

각 package는 자신의 책임을 감추는 작은 interface를 제공한다. domain reducer는 Exposed, Spring, JSON을 알지 않는다. event store는 billing 정책을 알지 않는다. projector는 command handler를 호출하지 않는다.

## 7. aggregate와 stream 경계

하나의 tenant stream에 모든 event를 넣지 않는다. tenant가 커질수록 단일 hot stream이 되기 때문이다.

| Aggregate type | Stream ID | 주요 event | 역할 |
|---|---|---|---|
| `Meter` | `{tenantId}:meter:{meterCode}` | `MeterRegistered`, `PriceActivated`, `PriceGapRepaired` | meter와 price timeline |
| `Usage` | `{tenantId}:usage:{externalEventId}` | `UsageAccepted`, `UsageRejected` | external usage uniqueness와 audit |
| `BillingPeriod` | `{tenantId}:period:{periodId}` | `BillingPeriodOpened`, `BillingCloseStarted`, `UsageRated`, `BillingCloseCompleted` | fixed cutoff, close cursor, charge provenance |
| `Invoice` | `{tenantId}:invoice:{invoiceId}` | `InvoiceIssued` | finalized invoice와 line provenance |
| `Adjustment` | `{tenantId}:adjustment:{adjustmentId}` | `DebitAdjustmentPosted`, `CreditAdjustmentPosted` | post-close correction |

`UsageRated`는 charge amount, price version, usage event ID, source position을 담는다. ledger projection은 이 event에서만 생성한다. `InvoiceIssued`는 line별 source `UsageRated` event ID와 amount를 기록한다. 따라서 projection을 삭제해도 invoice provenance를 event history에서 재현할 수 있다.

같은 `externalEventId`를 다른 idempotency key로 다시 제출하면 새 `Usage` stream을 만들지 않는다. 이미 존재하는 stream의 request fingerprint와 terminal state를 읽어 동일 payload는 duplicate acceptance로, 다른 payload는 `usage_event_conflict`로 판정한다.

여러 aggregate event가 하나의 command 결과로 필요하면 한 PostgreSQL transaction 안에서 각 stream의 expected version을 확인하고 append한다. 모든 stream append가 성공하거나 전체 transaction이 rollback된다. 이 atomicity는 modular example의 장점이며 #555에서는 outbox/inbox와 compensation으로 경계가 달라진다.

여러 stream head를 갱신하는 transaction은 `(tenantId, aggregateType, aggregateId)` lexical order로 처리한다. 서로 다른 command가 같은 stream 집합을 반대 순서로 잡아 deadlock을 만드는 일을 피한다.

## 8. event envelope

`EventEnvelope`은 다음 필드를 가진다.

| Field | 의미 |
|---|---|
| `globalPosition` | projector가 사용하는 단조 증가 database position |
| `eventId` | UUID v7 event identity |
| `tenantId` | 모든 query와 authority check에 포함되는 tenant |
| `aggregateType` | 안정적인 aggregate discriminator |
| `aggregateId` | tenant가 포함된 stream identity |
| `streamVersion` | stream 내부 1-based version |
| `eventType` | 안정적인 event name. Kotlin class name에 의존하지 않음 |
| `schemaVersion` | payload schema version |
| `payload` | Jackson 3 JSONB payload |
| `metadata` | 낮은 cardinality의 확장 metadata JSONB |
| `commandId` | command receipt와의 연결 |
| `correlationId` | 한 사용자 작업의 전체 흐름 |
| `causationId` | 직접 원인이 된 command/event |
| `actorId` | tenant user 또는 operator identity |
| `eventTime` | 비즈니스 사건 시각 |
| `recordedAt` | PostgreSQL에 기록된 시각 |
| `previousHash` | 이전 stream event의 integrity hash |
| `integrityHash` | canonical envelope 핵심 필드의 SHA-256 |

`metadata`에 request body, credential, raw exception, 높은 cardinality debug data를 넣지 않는다. hash chain은 application 또는 database administrator의 악의적 수정을 방지한다고 주장하지 않는다. 예상하지 못한 변조와 잘못된 migration을 탐지하는 audit signal이다.

hash input은 UTF-8, sorted object keys, stable field order, normalized number representation을 사용하는 canonical JSON으로 만든다. serializer의 일반 출력 순서나 whitespace에 digest가 의존하지 않는다.

reader는 저장된 original payload와 schema version으로 integrity hash를 먼저 검증하고 그다음 upcast한다. upcast 결과를 과거 event hash와 비교하지 않는다. `recordedAt`은 application clock이 아니라 PostgreSQL current timestamp를 사용한다.

## 9. event store schema와 optimistic concurrency

### `event_stream_heads`

- primary key: `(tenant_id, aggregate_type, aggregate_id)`
- `current_version`
- `last_event_id`
- `last_integrity_hash`
- `updated_at`

### `domain_events`

- primary key: `global_position`
- unique: `event_id`
- unique: `(tenant_id, aggregate_type, aggregate_id, stream_version)`
- append-only event envelope columns

주요 index는 stream replay용 `(tenant_id, aggregate_type, aggregate_id, stream_version)`, projector scan용 `global_position`, close source scan용 `(tenant_id, event_type, event_time, global_position)`, command correlation 조회용 `(tenant_id, command_id)`다.

`globalPosition`은 rollback이나 sequence allocation 때문에 gap이 생길 수 있다. projector는 값이 연속이라고 가정하지 않고 `globalPosition > checkpoint` 조건만 사용한다.

append algorithm은 다음 순서를 한 transaction에서 실행한다.

1. caller가 `expectedVersion`과 append할 event 목록을 넘긴다.
2. 신규 stream이면 version `0` head를 insert한다. 동시 insert의 unique conflict는 concurrency conflict로 변환한다.
3. 기존 head는 tenant/aggregate ID와 `currentVersion == expectedVersion` 조건으로 update한다.
4. update count가 1이 아니면 event를 쓰지 않고 `StreamVersionConflict`를 반환한다.
5. event에 연속 stream version과 hash chain을 부여해 insert한다.
6. head의 version, last event, hash와 command receipt terminal result를 같은 transaction에서 확정한다.

head는 event store의 concurrency index이며 별도 business state가 아니다. reconciliation은 마지막 event와 head의 version/hash가 다르면 `STREAM_HEAD_DIVERGED`를 보고한다. 자동으로 고치지 않는다.

## 10. command idempotency

`command_receipts`는 `(tenant_id, operation, idempotency_key_hash)`로 unique하다.

상태는 `PROCESSING`, `SUCCEEDED`, `REJECTED`다. receipt는 request fingerprint, owner token, lease deadline, HTTP status, stable response JSON, stable error code를 가진다.

처리 흐름은 다음과 같다.

1. receipt를 acquire한다.
2. 같은 fingerprint의 terminal receipt면 저장된 response를 replay한다.
3. fingerprint가 다르면 `409 idempotency_conflict`다.
4. 살아 있는 다른 owner lease면 `409 command_in_progress`와 `Retry-After`를 반환한다.
5. 만료 lease는 새 owner token으로 takeover한다.
6. aggregate를 replay하고 command를 결정한다.
7. event append와 receipt terminal update를 같은 transaction에서 commit한다.
8. commit 뒤 connection이 끊겨도 retry는 receipt의 terminal response를 replay한다.

실패 response는 시간이 지나도 같은 계약을 유지할 수 있는 domain rejection만 저장한다. 예상하지 못한 infrastructure exception은 receipt lease를 만료시켜 재시도 가능하게 하고 안정적인 terminal response로 저장하지 않는다.

command가 lease 절반 이상 실행되면 current owner token을 조건으로 lease를 연장한다. append transaction은 event를 쓰기 전에 receipt owner token과 lease가 여전히 유효한지 확인하고, terminal receipt update count가 1이 아니면 전체 transaction을 rollback한다. stale owner는 event만 남기고 response를 잃는 상태를 만들 수 없다.

## 11. aggregate replay와 reducer

aggregate는 다음 contract를 따른다.

```kotlin
interface EventSourcedAggregate<S : Any, E : DomainEvent> {
    val initialState: S
    fun evolve(state: S, event: E): S
}
```

`evolve`는 clock, database, UUID generator, logger를 호출하지 않는 pure function이다. 같은 ordered event는 항상 같은 state와 digest를 만든다.

command handler는 현재 state를 replay한 뒤 validation과 decision을 수행해 새 event를 만든다. command handler가 projection을 authority로 읽어 business 결정을 내리지 않는다. projection lag가 command correctness에 영향을 주지 않아야 한다.

## 12. upcasting과 schema evolution

event type은 class 이름이 아닌 명시적 registry key를 사용한다.

```text
eventType + schemaVersion
  -> JSON payload
  -> one-step upcaster chain
  -> current payload schema
  -> typed domain event
```

규칙은 다음과 같다.

- upcaster는 `v1 -> v2`처럼 한 version만 올린다.
- upcaster는 pure하고 deterministic해야 한다.
- 역사 event row와 original schema version은 수정하지 않는다.
- rename, default 추가, 구조 분해를 실제 오래된 fixture로 검증한다.
- 알 수 없는 event type/version은 조용히 건너뛰지 않는다.
- aggregate replay에서는 명시적 실패, projector에서는 해당 bounded projection quarantine으로 전환한다.

예제는 최소 하나의 실제 schema evolution을 포함한다. 예를 들어 `UsageAccepted v1`의 단일 `quantity`를 `UsageAccepted v2`의 `measuredQuantity`와 `sourceUnit`으로 올리는 upcaster를 제공한다.

## 13. snapshot

snapshot은 replay 최적화일 뿐 authority가 아니다.

`aggregate_snapshots`는 tenant, aggregate type/ID, stream version, state schema version, serialized state JSONB, last event ID/hash, serializer fingerprint, created time을 저장한다.

snapshot key는 `(tenant, aggregate type, aggregate ID, stream version)`이다. 새 snapshot은 이전 row를 덮어쓰지 않는다. 오래된 snapshot 삭제는 event history가 아닌 optimization retention 작업이며 active replay가 참조하지 않는 범위에서만 수행한다.

snapshot은 다음 조건을 모두 만족할 때만 사용한다.

1. snapshot stream version이 현재 stream version 이하이다.
2. snapshot이 가리키는 event ID/hash가 event store와 일치한다.
3. state schema version과 serializer fingerprint를 현재 reader가 지원한다.
4. tenant, aggregate type/ID가 request와 일치한다.

하나라도 다르면 snapshot을 삭제하거나 덮어쓰지 않고 무시한 뒤 genesis부터 replay한다. invalidation metric과 operator timeline을 남긴다. 이후 성공적인 replay가 새 snapshot을 append/upsert할 수 있다.

snapshot threshold는 event count 기반 property로 설정하고 기본값은 충분히 작게 두어 예제에서 동작을 관찰할 수 있게 한다. correctness test는 snapshot 사용 여부와 무관하게 같은 state digest를 요구한다.

## 14. projection runtime

projection은 event store에서 언제든 다시 만들 수 있는 derived state다.

### projection generation

각 projection은 `ACTIVE`, `BUILDING`, `FAILED`, `RETIRED` generation을 가진다.

- active generation은 query API가 읽는 generation이다.
- rebuild는 새 generation을 만들고 global position `0`부터 처리한다.
- rebuild 중 기존 active generation은 계속 서비스한다.
- rebuild 시작 시 high watermark를 기록하고, 그 지점까지 처리한 뒤 현재 event-store tail을 따라잡는 catch-up loop를 실행한다.
- 새 generation이 validation을 통과하고 current tail까지 따라잡으면 작은 control row transaction으로 active generation을 바꾼다.
- 이전 generation은 즉시 삭제하지 않고 운영자가 확인할 retention 기간을 둔다.

이 방식은 production table을 비운 뒤 rebuild하는 위험을 피한다.

generation switch는 expected active generation과 control-row version을 확인하는 conditional update다. switch 직후 문제가 발견되면 retention 중인 이전 generation으로 같은 조건부 rollback을 수행할 수 있다. rollback은 event history를 바꾸지 않으며 새 generation의 failure evidence를 보존한다.

### checkpoint와 duplicate suppression

`projection_checkpoints` key는 `(projection_name, tenant_partition, generation)`이다. 다음 필드를 가진다.

- last global position
- last event ID
- state
- owner token과 lease deadline
- processed/failed count
- updated time

`projection_applied_events`는 `(projection, generation, event_id)`로 unique하여 duplicate delivery를 막는다. projector는 event 적용, applied marker, checkpoint advance를 한 transaction에서 수행한다.

각 projector는 event compatibility catalog를 가진다. 명시적으로 `ignored`로 선언된 다른 bounded-context event만 no-op 처리할 수 있다. catalog에 없는 새 event type/version을 단순히 무시하지 않고 quarantine한다.

checkpoint는 다음 position으로 건너뛰지 않는다. event가 delayed되었다는 표현은 worker delivery 순서가 아니라 retry scheduling을 의미한다. authoritative scan은 항상 `globalPosition > checkpoint` keyset order다.

tenant partition의 lag는 database 전체 max position과 단순 비교하지 않는다. 해당 tenant/projection이 관심을 가지는 마지막 event position과 checkpoint를 비교한다. 다른 tenant의 많은 event 때문에 lag가 부풀려지지 않아야 한다.

### concurrent projector

PostgreSQL owner-token lease가 한 projection partition의 liveness를 조정한다. lease 획득만으로 correctness를 주장하지 않는다. checkpoint advance는 현재 owner token과 expected last position 조건을 함께 확인한다. stale owner update는 0 rows로 실패한다.

Redis나 `bluetape4k-leader`는 이 예제에서 사용하지 않는다. 단일 PostgreSQL authority로 projection ordering과 recovery를 설명하는 편이 더 명확하다. #555에서 Kafka consumer group이 추가되어도 inbox/checkpoint는 PostgreSQL에 남는다.

## 15. poison event와 quarantine

projector가 event를 current schema로 decode하지 못하거나 invariant를 만족하지 못하면 `projection_failures`에 다음 정보를 기록한다.

- projection, generation, tenant partition
- event ID와 global position
- stable failure code와 exception type
- payload가 아닌 redacted digest
- attempt count, first/last failure time
- operator resolution state

affected projection partition은 `FAILED`가 되고 그 event 이전 checkpoint에서 멈춘다. 다른 projection과 다른 tenant partition은 계속 실행한다.

operator는 다음 중 하나를 선택한다.

- reader/upcaster 수정 후 retry
- 새 generation rebuild
- event가 business history 자체를 위반하면 manual investigation

financial event를 skip하는 API는 제공하지 않는다. tutorial fixture에 의도적인 poison event를 넣고 quarantine, 수정, retry를 검증한다.

## 16. read model

| Projection | 주요 query | 핵심 invariant |
|---|---|---|
| `usage` | accepted usage와 source event | event ID당 한 row |
| `ledger` | period charge/debit/credit | source domain event당 한 entry |
| `invoice` | finalized invoice, line, provenance | invoice total = line sum |
| `operator_timeline` | command/event/projection/rebuild 흐름 | tenant와 correlation order 보존 |
| `reconciliation` | divergence finding | read-only, stale-safe resolution |

모든 projection table은 `generation`과 `tenant_id`를 key에 포함한다. query repository가 generation이나 tenant filter를 생략하면 architecture test가 실패하도록 한다.

## 17. HTTP consistency contract

command endpoint의 URL, request, created/replay/conflict status는 #552와 동일하게 유지한다. command response는 projection이 아니라 append 후 aggregate state와 event metadata로 만든다.

query endpoint는 active projection을 읽고 다음 response header를 추가한다.

- `Projection-Position`
- `Projection-Lag`

read-your-write가 필요한 caller는 command response의 `Event-Position`을 query의 optional `X-Wait-For-Position` header로 전달할 수 있다. server는 설정된 짧은 budget 안에서 checkpoint를 기다린다. budget을 넘으면 projection을 authority처럼 즉석 수정하지 않고 `409 projection_not_caught_up`과 `Retry-After`를 반환한다.

baseline black-box parity test는 projector를 deterministic하게 drain한 뒤 겹치는 query 결과를 비교한다. HTTP transport test는 projection lag response와 retry를 별도로 검증한다.

## 18. billing close

close command는 `BillingCloseStarted`에 다음 값을 고정한다.

- period ID
- `cutoffReceivedAt`
- close generation
- price timeline digest
- 시작 global position

worker는 accepted usage projection을 authority로 사용하지 않는다. event store에서 해당 tenant/period의 `UsageAccepted` event를 stable keyset으로 읽어 `UsageRated` event를 append한다. cursor는 `BillingPeriod` aggregate event로 남긴다.

source scan은 `(tenant_id, event_type, event_time, global_position)` index와 fixed cutoff를 사용한다. batch 안에서 필요한 meter price stream을 한 번씩 replay해 immutable price timeline map을 만들고 usage마다 stream을 다시 읽는 N+1 query를 피한다.

중단 후 replay하면 이미 기록된 `UsageRated` source usage event ID를 state에서 알 수 있다. 같은 source에 charge event를 두 번 만들지 않는다. 모든 usage가 처리되면 `InvoiceIssued`와 `BillingCloseCompleted`를 append한다.

price gap이 있으면 `BillingCloseBlockedByPriceGap` event를 남기고 멈춘다. operator가 `PriceGapRepaired`를 append한 뒤 resume command가 같은 cutoff와 generation을 이어간다.

## 19. late usage와 correction

close cutoff 이후 늦게 도착한 usage는 원 invoice를 수정하지 않는다.

1. `UsageAccepted`가 append된다.
2. reconciliation이 finalized period 이후 uncharged usage를 찾는다.
3. operator가 debit adjustment command를 실행한다.
4. `DebitAdjustmentPosted`가 append된다.
5. ledger projection이 compensating debit entry를 만든다.

credit도 동일한 append-only 흐름을 따른다. correction event는 reason, actor, source event, correlation, amount, currency를 기록한다.

## 20. reconciliation

reconciliation은 active projection을 event replay 결과와 비교한다. 최소 finding category는 다음과 같다.

- `STREAM_HEAD_DIVERGED`
- `PROJECTION_CHECKPOINT_GAP`
- `MISSING_LEDGER_ENTRY`
- `UNEXPECTED_LEDGER_ENTRY`
- `INVOICE_TOTAL_MISMATCH`
- `PROVENANCE_MISMATCH`
- `UNLEDGERED_LATE_USAGE`
- `EVENT_HASH_MISMATCH`

finding은 observed event position, projection generation, expected digest, actual digest를 가진다. repair command는 이 observed context가 여전히 현재인지 확인한다. stale finding이면 `409 stale_reconciliation_finding`으로 거부한다.

repair는 financial history를 직접 수정하지 않는다. projection 문제는 새 generation rebuild와 검증 후 switch로 고친다. business correction은 새 domain event를 append한다.

## 21. 보안과 tenant isolation

- tenant command/query URL의 tenant는 authenticated principal과 같아야 한다.
- operator API는 `ROLE_OPERATOR`만 접근한다.
- repository method는 tenant ID를 필수 parameter로 받는다.
- stream ID만으로 조회하는 repository API를 제공하지 않는다.
- snapshot, checkpoint, failure, generation key에도 tenant 또는 명시적 global operator scope를 넣는다.
- event metadata와 log에 credential, authorization header, request payload를 남기지 않는다.
- event payload는 event type별 allowlisted field와 최대 byte 크기를 검증한다. arbitrary class name이나 polymorphic type metadata를 역직렬화하지 않는다.
- customer PII 원문을 event payload에 저장하지 않고 stable internal subject ID를 사용한다.
- exception response는 internal SQL, table, hash chain detail을 노출하지 않는다.
- cross-tenant replay/rebuild/repair test를 각 persistence boundary에 둔다.

## 22. 관측 가능성과 운영 API

metric tag는 projection name, event type family, result, failure code처럼 낮은 cardinality만 사용한다. tenant, aggregate ID, command ID는 tag로 사용하지 않는다.

주요 metric은 다음과 같다.

- append latency와 version conflict count
- replay event count와 snapshot hit/miss/invalid count
- upcast count와 failure count
- projection lag, batch size, checkpoint advance, duplicate count
- rebuild processed count, duration, state
- quarantine count와 oldest failure age
- reconciliation finding count
- command acquire/replay/conflict/takeover count

operator API는 status 조회, rebuild 시작/상태 조회, quarantine retry, reconciliation inspect/repair를 제공한다. 모든 mutation API는 idempotency key와 audit actor를 요구한다.

health indicator는 database 연결 여부만으로 `UP`을 반환하지 않는다. active projection failure, excessive lag, stale rebuild, quarantine age를 detail로 제공한다. correctness authority가 살아 있어도 query freshness가 나쁘면 degraded 상태를 명확히 보여준다.

## 23. configuration

`workshop.metering-events` 아래에 다음 property를 둔다.

- command lease와 retention
- snapshot threshold와 retention
- projector batch size, lease, poll interval
- query wait-for-position budget
- rebuild batch size와 old-generation retention
- allowed projection lag
- close scheduler enable/interval/batch

모든 duration과 batch는 양수 검증을 거친다. test는 default, override, zero/negative rejection을 검증한다. Java 25 virtual thread를 사용하되 correctness는 thread scheduling에 의존하지 않는다.

## 24. database와 transaction 원칙

- 모든 persistence와 fixture는 JetBrains Exposed를 사용한다.
- `JdbcTemplate`, `java.sql.*`, `PreparedStatement`, `Transaction.exec`, migration SQL을 도입하지 않는다.
- schema fixture는 Exposed `SchemaUtils`를 `MeteringEventsJdbcExecutor` transaction 안에서 실행한다.
- 모든 concrete repository는 `ExposedJdbcRepository` delegate를 가진다.
- append-only event와 financial projection repository의 generic mutation path를 차단한다.
- Spring service transaction은 named `springTransactionManager`인 Exposed `SpringTransactionManager`를 사용한다.
- PostgreSQL unique/conditional update가 필요한 경쟁 시나리오는 H2로 대체하지 않는다.
- production schema는 application이 자동 생성하지 않는다.

## 25. 테스트 전략

### unit test

- aggregate reducer determinism과 invalid transition
- event envelope canonical hash
- one-step/chained upcaster와 unknown version
- snapshot validity matrix
- command fingerprint와 stable error mapping
- configuration validation

### PostgreSQL integration test

- same expected version 20-way contention에서 한 append만 성공
- command replay/conflict/lease takeover/stale owner
- append와 terminal receipt atomic commit
- replay와 snapshot replay state/digest parity
- invalid snapshot fallback
- projector duplicate/interruption/concurrent owner fencing
- poison event quarantine와 retry
- generation rebuild parity와 atomic active switch
- all reconciliation category와 stale repair rejection
- cross-tenant stream/snapshot/projection/rebuild isolation

### black-box parity test

#552와 공유하는 test-only scenario가 두 Spring Boot application에 같은 command를 실행한다. 겹치는 HTTP status, error code, money, invoice provenance, adjustment 결과를 비교한다. 내부 event count나 projection table shape는 비교하지 않는다.

### stress test

최소 10,000 usage event를 append하고 snapshot을 포함한 aggregate replay, bounded close, projection restart, full generation rebuild를 수행한다. 결과는 source usage event당 한 `UsageRated`, 한 ledger charge, 같은 invoice total이어야 한다.

### architecture test

- 모든 concrete persistence class가 `ExposedJdbcRepository` contract를 구현
- production/fixture raw SQL 금지
- production `!!`, `println`, broad unlogged exception handling 금지
- projection table query의 tenant/generation boundary
- domain package의 Spring/Exposed dependency 금지

## 26. Diagram과 README

README.md와 README.ko.md는 같은 구조를 유지한다.

1. 왜 baseline 다음에 advanced 예제가 필요한가
2. 세 가지 기억할 규칙
3. command → event stream → projection architecture
4. aggregate와 event state diagram
5. idempotent append sequence diagram
6. snapshot/upcasting/replay sequence
7. projection checkpoint/quarantine/rebuild state diagram
8. billing close와 correction sequence
9. operator recovery command
10. projection lag와 metric
11. baseline 대 advanced 선택표
12. #555 microservice extraction roadmap
13. 실행과 검증 방법

PNG를 README의 authoritative embed로 사용하고 SVG source를 함께 보관한다. label, arrow endpoint, state transition, mixed-corner geometry를 CI에서 검증한다.

## 27. baseline과 advanced 선택 기준

| 질문 | #552 normalized baseline | #553 Event Sourcing |
|---|---|---|
| 현재 상태 query가 가장 중요한가 | 적합 | projection 운영 비용이 추가됨 |
| 모든 business decision replay가 필요한가 | ledger 범위만 제공 | 전체 aggregate history 제공 |
| schema evolution 난이도 | row migration 중심 | event upcaster와 snapshot invalidation 필요 |
| 장애 복구 | workflow resume와 reconciliation | replay, checkpoint, generation rebuild 필요 |
| 저장 공간 | 상대적으로 작음 | event와 projection을 함께 보관해 큼 |
| correction audit | append-only ledger로 충분 | causation을 포함한 전체 history 제공 |
| 팀 경험 | 일반 Spring/SQL 경험으로 가능 | Event Sourcing 운영 경험 필요 |
| 기본 추천 | 대부분의 서비스 | 강한 audit/replay 요구가 검증된 경우 |

## 28. microservice 추출 경로

#553 README는 구현이 아닌 단계별 추출 가이드를 제공한다.

1. 먼저 event type과 envelope compatibility contract를 고정한다.
2. projection을 독립 worker process로 분리하되 같은 database authority를 유지한다.
3. transactional outbox를 추가하고 event publication을 event-store commit과 연결한다.
4. query projection을 별도 service/database로 이동하고 idempotent inbox를 둔다.
5. command service의 aggregate stream ownership을 분리한다.
6. lag, replay, poison, schema evolution runbook을 서비스별로 검증한다.

서비스별 PostgreSQL과 Kafka를 실제로 구현하는 일은 #555에서 수행한다. #553은 database sharing을 microservice architecture라고 부르지 않는다.

## 29. 주요 실패 모드

| 실패 | 원인 | 탐지 | 대응 |
|---|---|---|---|
| duplicate event append | receipt와 append transaction 분리 | stream/event unique conflict | terminal receipt와 append atomic commit |
| lost update | expected version 확인 누락 | contention test | head conditional update와 unique version |
| corrupted replay | reducer side effect 또는 순서 오류 | state digest mismatch | pure reducer와 ordered load |
| stale snapshot 사용 | schema/hash 검증 누락 | snapshot parity test | snapshot 무효화 후 genesis replay |
| silent schema loss | unknown event를 skip | replay/projector count drift | explicit failure와 quarantine |
| checkpoint skip | event 적용 전 advance | rebuild digest mismatch | apply/marker/checkpoint 한 transaction |
| duplicate projection effect | retry dedup 없음 | unique source event violation | applied-event unique key |
| rebuild 중 query 공백 | active table truncate | availability test | shadow generation과 atomic switch |
| poison event 전체 중단 | global projector 상태 공유 | unrelated tenant lag | bounded partition failure |
| stale worker overwrite | lease만 확인 | owner-token update count 0 | owner token + expected checkpoint CAS |
| cross-tenant leak | stream ID 단독 query | security integration test | tenant-required repository API |
| invoice history rewrite | projection을 authority로 correction | provenance reconciliation | new adjustment event와 entry |
| metric cardinality 폭증 | tenant/aggregate tag | meter registry inspection | fixed result/type tags만 허용 |

## 30. 성능과 용량 고려

- event load는 `(tenant, aggregateType, aggregateId, streamVersion)` index를 사용한다.
- projector scan은 `globalPosition` keyset pagination을 사용한다.
- snapshot은 correctness가 아닌 replay 비용 감소 수단이다.
- payload와 metadata는 필요한 audit data만 저장하고 동일 response body를 event에 중복 저장하지 않는다.
- close와 rebuild batch는 transaction 시간을 제한한다.
- rebuild가 active projector를 굶기지 않도록 별도 configurable budget을 둔다.
- 성능 비교는 append latency, aggregate replay count, rebuild throughput, storage row/byte 증가를 baseline과 함께 설명하되 단일 개발 장비 결과를 production capacity라고 주장하지 않는다.

## 31. repository integration

새 모듈과 함께 다음 surface를 같은 branch에서 갱신한다.

- root README.md / README.ko.md module table
- `.github/workflows/Examples.yml` smoke/full group
- `.github/workflows/nightly.yml` container-backed group
- validation matrix
- stale-check와 smoke validation script
- Kover artifact verification
- diagram generation/QA script
- durable lesson

Java 25와 PostgreSQL Testcontainers 모듈이므로 lightweight smoke와 full container validation을 분리한다.

## 32. 호환성과 migration

기존 #552 module, table, API는 수정하지 않는다. #553은 독립 database schema prefix와 application name을 사용한다. 두 module을 동시에 실행할 수 있어야 한다.

black-box fixture 추출이 필요하면 test-only source로 제한하고 #552 production dependency를 추가하지 않는다. fixture extraction이 기존 #552 test behavior를 바꾸면 두 module test를 함께 실행한다.

event schema migration은 역사 row rewrite가 아니라 reader/upcaster 배포로 수행한다. incompatible change는 새 event type 또는 새 schema version을 추가한다.

## 33. 실행 제약

- 구현과 검토는 현재 main session에서 inline으로 수행한다.
- subagent에 production code나 review를 위임하지 않는다.
- Testcontainers와 heavy Gradle validation은 순차 실행한다.
- 설치된 local JDK가 다시 hang하면 unrelated Java process를 종료하지 않는다. exact-head GitHub Actions를 fresh authoritative execution evidence로 사용하고 local validation gap을 명시한다.
- merge는 exact-head CI와 review가 수렴한 뒤 별도 승인을 받는다.
- merge 후 local `develop`을 fast-forward하고 이 issue의 worktree와 local feature branch를 삭제한다.

## 34. Acceptance criteria traceability

| Issue requirement | 설계 위치 | 검증 |
|---|---|---|
| optimistic concurrency | 9 | PostgreSQL 20-way append contention |
| idempotent replay | 10 | replay/conflict/takeover integration test |
| replay/snapshot parity | 11, 13 | state/digest parity test |
| upcasting | 12 | historical fixture chain test |
| projection rebuild parity | 14, 16 | original/rebuild digest comparison |
| duplicate/delayed/interrupted/concurrent projector | 14 | checkpoint/restart/fencing tests |
| poison event | 15 | bounded quarantine/retry test |
| schema evolution | 12, 13 | old event + invalid snapshot test |
| corrections | 19 | debit/credit provenance test |
| tenant isolation | 21 | cross-tenant boundary matrix |
| Exposed only | 24 | architecture/raw SQL scan |
| same black-box scenarios | 17, 25 | shared test-only scenario contract |
| operational docs/diagrams | 22, 26, 28 | bilingual validator와 diagram QA |

## 35. Spec review 결과

| Lens | 검토 결과 | P0/P1 |
|---|---|---|
| Performance | hot tenant stream을 aggregate별로 분리했고 close N+1, keyset scan, batch/rebuild budget, snapshot, index를 명시했다. | 0/0 |
| Stability | multi-stream lock order, command owner fencing, atomic receipt/append, projector CAS, poison isolation, generation rollback을 명시했다. | 0/0 |
| Security | tenant-required repository, operator role, payload allowlist/size, safe deserialization, PII/log 제한을 명시했다. | 0/0 |
| Operator/Ops | lag/health, quarantine, retry, shadow rebuild, conditional switch/rollback, retention과 recovery API를 명시했다. | 0/0 |
| Developer/API | domain과 persistence를 분리하고 application-local abstraction, `ExposedJdbcRepository`, no-raw-SQL contract를 고정했다. | 0/0 |
| User/caller | baseline-compatible command, projection freshness header/wait contract, 선택표와 #555 추출 가이드를 포함했다. | 0/0 |

Main-session integration review에서 authority가 event store인지 projection인지 모호한 부분, history rewrite 가능성, baseline production 코드 공유 가능성을 다시 확인했다. business decision은 event replay만 사용하고 projection은 query/reconciliation 파생 상태로 제한했으며, production 구현 공유는 금지했다. 최신 integrated result는 P0=0, P1=0이다.

남은 non-blocking trade-off는 두 가지다.

- `BillingPeriod` stream은 usage 수에 비례해 커진다. 이 예제에서는 snapshot과 10,000-row stress proof로 비용을 드러내며, stream sharding은 #555의 서비스 경계 설계에서 다시 평가한다.
- `projection_applied_events`는 active generation 동안 event 수에 비례해 증가한다. duplicate proof를 위해 유지하고 retired generation cleanup과 storage metric을 제공한다. 범용 압축/retention abstraction은 bluetape4k-projects#1070의 근거로 남긴다.

## 36. Definition of Done

- 승인된 spec과 상세 implementation plan이 commit되어 있다.
- Java 25 Spring Boot module이 독립 실행된다.
- 모든 persistence와 fixture가 Exposed와 `ExposedJdbcRepository`를 사용하며 raw SQL이 없다.
- event append, replay, snapshot, upcast, projection, quarantine, rebuild, correction, reconciliation contract가 PostgreSQL에서 검증된다.
- #552와 겹치는 black-box scenario가 advanced implementation에서도 통과한다.
- 10,000 usage stress rebuild가 source event당 정확히 한 financial effect를 증명한다.
- Spring Security, Micrometer, health, operator recovery API가 문서와 일치한다.
- 영어/한국어 README, Architecture/State/Sequence/Rebuild Diagram, baseline 비교, #555 추출 가이드가 있다.
- module/workflow/nightly/validation/Kover/stale-check/lesson surface가 갱신된다.
- detekt, tests, static validation, diagram QA, CI가 exact head에서 통과한다.
- P0/P1 review finding이 0개이고 unresolved PR thread가 없다.
- PR이 merge-ready로 보고된 뒤 별도 merge 승인을 기다린다.

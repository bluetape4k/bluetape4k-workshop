# Event-Sourced Usage Metering & Billing

[English](README.md) | 한국어

이 예제는 사용량 청구의 현재 상태를 직접 갱신하지 않고, 발생한 사실을 append-only event로 저장한 뒤 replay와 projection으로 상태를 다시 만드는 advanced 구현이다. 감사 추적, 과거 재현, 무중단 read-model 재구축이 필요한 서비스 회사를 위한 Spring Boot modular monolith 기준 아키텍처다.

Java 25, Spring Boot 4, PostgreSQL, JetBrains Exposed JDBC, `bluetape4k-exposed-jdbc`, Micrometer를 사용한다. 모든 concrete repository는 `ExposedJdbcRepository`를 구현하고 production/test fixture 모두 Exposed DAO/DSL만 사용한다. `JdbcTemplate`, `java.sql.*`, `PreparedStatement`, `Transaction.exec`, raw migration SQL은 없다.

![전체 아키텍처](../../docs/images/readme-diagrams/usage-billing-event-sourcing-architecture-01.png)

[아키텍처 SVG 원본](../../docs/images/readme-diagrams/usage-billing-event-sourcing-architecture-01.svg)

## 먼저 baseline과 advanced를 고른다

대부분의 팀은 먼저 [`usage-metering-billing-ledger`](../usage-metering-billing-ledger/) baseline으로 시작하는 것이 좋다. Event Sourcing은 이력을 남기는 기능 하나가 아니라 저장, schema 진화, replay, projection 장애 복구를 함께 운영하는 선택이기 때문이다.

| 판단 기준 | Baseline ledger | 이 advanced 예제 |
|---|---|---|
| 현재 상태 조회 | 정규화된 PostgreSQL row | ACTIVE projection |
| 감사 이력 | 불변 ledger/invoice provenance | 모든 domain event + hash chain |
| 과거 상태 재현 | 별도 audit query | 특정 stream version까지 replay |
| read-model 변경 | schema/data migration | shadow generation rebuild 후 switch |
| 운영 복잡도 | 낮음 | 높음: upcast, replay, lag, poison event, generation 운영 |

규제 감사, 복잡한 요금 규칙의 시간 여행, 여러 read model, 원본 event 재처리가 실제 요구사항일 때 advanced를 선택한다. “나중에 필요할 수도 있다”는 이유만으로 선택하지 않는다.

## 실행과 빠른 검증

JDK 25와 Docker 호환 container runtime이 필요하다. PostgreSQL은 Bluetape Testcontainers fixture로 실행된다.

```bash
java -version
./gradlew :commerce-usage-metering-billing-event-sourcing:test --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:integrationTest --rerun-tasks --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:stressTest --rerun-tasks --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:koverXmlReport
```

`test`는 reducer, hash, upcast, Kotlin/Exposed architecture contract를 검증한다. `integrationTest`는 PostgreSQL unique/CAS/fencing/replay/snapshot/projection/reconciliation/HTTP 경계를 검증한다. `stressTest`는 usage 10,000건을 bounded batch로 닫고 projection을 generation 2로 처음부터 재구축한다. 이 수치는 capacity benchmark가 아니라 재시작·정확성 회귀 증거다.

## Aggregate state와 금지된 전이

Aggregate는 크게 만들지 않는다. Meter, Usage, Billing Period, Invoice, Adjustment가 각자 작은 불변식을 소유하고 서로의 row를 직접 고치지 않는다.

![Aggregate 상태도](../../docs/images/readme-diagrams/usage-billing-event-sourcing-aggregate-state-01.png)

[Aggregate 상태도 SVG 원본](../../docs/images/readme-diagrams/usage-billing-event-sourcing-aggregate-state-01.svg)

- `Usage.Accepted`는 같은 source identity를 다시 받더라도 두 번째 사실을 만들지 않는다.
- `Period.Finalized`와 `Invoice.Issued`는 terminal state다.
- 이미 확정한 금액은 수정하지 않고 `Adjustment.Posted`의 `DEBIT` 또는 `CREDIT`으로 보정한다.
- 모든 command service는 event를 쓰기 전에 stream을 replay해 현재 상태와 expected version을 얻는다.

## Event envelope, hash chain, schema 진화

event store authority는 `(tenantId, streamType, streamId, streamVersion)`과 단조 증가 `globalPosition`이다. 저장 envelope에는 event ID/type/schema version, canonical payload/metadata, `occurredAt`, PostgreSQL이 기록하는 `recordedAt`, `previousHash`, `eventHash`가 들어간다. bounded metadata에는 `commandId`, `correlationId`, `causationId`, `actorId`를 기록하고 credential이나 request body는 저장하지 않는다.

한 transaction이 stream head를 확인하고 expected version과 일치할 때만 event를 append한다. Hash는 canonical material에 대해 계산하므로 payload, metadata, 순서가 바뀌면 replay가 fail closed한다. 과거 payload는 절대 덮어쓰지 않는다. `EventCodecRegistry`가 schema-version별 decoder와 한 단계씩 연결된 upcaster를 적용해 오늘의 reducer 입력으로 바꾼다. upcast 경로가 끊기면 임의로 건너뛰지 않는다.

## 멱등 command와 optimistic append

`Idempotency-Key`의 digest와 canonical request fingerprint를 command receipt에 저장한다. 같은 key와 같은 fingerprint는 저장된 HTTP status/body를 그대로 replay하고, 다른 fingerprint는 `409`다. lease가 끝난 receipt는 새 owner token이 인수할 수 있지만 terminal CAS는 현재 owner만 성공한다.

![Command append sequence](../../docs/images/readme-diagrams/usage-billing-event-sourcing-command-sequence-01.png)

[Command sequence SVG 원본](../../docs/images/readme-diagrams/usage-billing-event-sourcing-command-sequence-01.svg)

receipt와 domain append는 retry가 두 번째 사실을 만들 수 없도록 transaction 경계를 공유한다. 동시에 같은 stream을 갱신한 command는 expected version 충돌로 실패하고 최신 상태를 replay한 뒤 다시 판단해야 한다.

## Replay와 snapshot은 어떻게 안전한가

Replay 순서는 고정돼 있다. snapshot 검증, snapshot 이후 event 로드, hash chain 검증, schema upcast, decode, pure reducer fold 순이다.

![Replay sequence](../../docs/images/readme-diagrams/usage-billing-event-sourcing-replay-sequence-01.png)

[Replay sequence SVG 원본](../../docs/images/readme-diagrams/usage-billing-event-sourcing-replay-sequence-01.svg)

snapshot은 authority가 아니라 최적화다. reducer version, stream version, last event hash가 현재 event history와 맞을 때만 seed로 사용한다. 손상되거나 오래된 snapshot은 삭제·수정해서 맞추지 않고 genesis부터 replay한다. 같은 event sequence는 언제나 같은 state, version, last hash를 만들어야 한다.

## Projection generation state와 무중단 rebuild

query는 `ACTIVE` generation 하나만 읽는다. 새 projection은 `BUILDING`으로 만들고 별도 generation key 아래에서 event store의 global position을 따라잡는다.

![Projection generation 상태도](../../docs/images/readme-diagrams/usage-billing-event-sourcing-projection-state-01.png)

[Projection 상태도 SVG 원본](../../docs/images/readme-diagrams/usage-billing-event-sourcing-projection-state-01.svg)

`BUILDING → ACTIVE`는 checkpoint가 capture한 high watermark 이상이고 현재 lease owner의 fencing token이 유효할 때만 가능하다. 이전 ACTIVE는 같은 switch에서 `RETIRED`가 된다. decode/handler 오류는 poison event를 quarantine하고 generation을 `FAILED`로 바꾸며, 건강한 ACTIVE view는 그대로 둔다. `FAILED → ACTIVE`, stale owner checkpoint, 부분 alias switch는 금지한다.

![Online rebuild](../../docs/images/readme-diagrams/usage-billing-event-sourcing-rebuild-01.png)

[Rebuild SVG 원본](../../docs/images/readme-diagrams/usage-billing-event-sourcing-rebuild-01.svg)

운영 순서는 high watermark capture → N+1 생성 → keyset page catch-up → 조건부 alias switch → lag/reconciliation 관찰이다. rollback은 보존한 RETIRED generation으로 조건부 전환하며 event를 되돌리지는 않는다.

## 청구 마감, 보정, reconciliation

마감은 event replay로 period와 usage 상태를 확인하고 bounded batch마다 `UsageRated`를 append한다. cursor와 누적 합계도 period stream event이므로 worker가 중간에 죽어도 다음 실행이 마지막 확정 event부터 계속한다. 완료 조건을 만족하면 `BillingPeriodFinalized`와 immutable `InvoiceIssued`를 append한다.

늦은 usage나 과다 청구는 기존 event 또는 projection row를 고치지 않는다. operator finding이 관찰한 event-store position과 digest가 여전히 유효할 때 adjustment stream에 새 근거를 append한다.

![불변 이력 보정](../../docs/images/readme-diagrams/usage-billing-event-sourcing-correction-01.png)

[Correction SVG 원본](../../docs/images/readme-diagrams/usage-billing-event-sourcing-correction-01.svg)

Reconciliation은 authoritative replay total과 ACTIVE projection의 total/provenance를 비교한다. 차이는 finding으로 기록할 뿐 자동 수정하지 않는다. repair는 expected digest가 stale이면 거부한다.

## HTTP consistency와 security 경계

Tenant command/query는 `/api/v1/tenants/{tenantId}` 아래에 있고 principal name이 path tenant와 같아야 한다. 쓰기는 `TENANT_BILLING_WRITE`, 읽기는 `TENANT_BILLING_READ`, `/api/admin/event-sourcing/**`는 `ROLE_OPERATOR`가 필요하다. 예제의 Basic Auth 사용자는 local demonstration용이며 실제 배포는 조직의 JWT/OAuth2 provider로 교체한다.

`POST /meters`는 `Idempotency-Key`가 필수다. query response는 `Projection-Position`, `Projection-Lag`를 제공한다. client가 command response의 global position을 알고 있으면 `X-Wait-For-Position`으로 최대 100ms의 bounded read-your-write wait를 요청할 수 있다. 시간 안에 catch-up하지 못하면 `409 projection_not_caught_up`을 반환하며 무한 대기하거나 event store를 query fallback으로 사용하지 않는다.

운영 API는 event history 수정 권한을 주지 않고 복구 절차만 노출한다.

| Endpoint | 용도 |
|---|---|
| `GET /api/admin/event-sourcing/projections/{name}` | ACTIVE checkpoint, high watermark, lag, quarantine 조회 |
| `GET /api/admin/event-sourcing/projections/{name}/generations/{generation}` | BUILDING/FAILED/RETIRED generation 상태 조회 |
| `POST /api/admin/event-sourcing/projections/{name}/rebuilds` | `Idempotency-Key`가 필요한 fenced rebuild 시작. 동시에 두 BUILDING generation은 거부 |
| `GET /api/admin/event-sourcing/reconciliation?...` | authoritative financial event와 ACTIVE projection 비교 |
| `GET /actuator/metrics/**` | `ROLE_OPERATOR`로 bounded-tag Micrometer 지표 조회 |

bounded scheduler는 ACTIVE와 BUILDING generation을 서로 다른 lease로 실행한다. BUILDING은 최신 event-store high watermark까지 따라잡은 상태가 switch 직전에도 유지될 때만 ACTIVE가 되며, 그렇지 않으면 다음 cycle이 checkpoint부터 이어서 처리한다.

orchestration을 위한 health status는 공개하지만 projection/quarantine 상세 정보는 `ROLE_OPERATOR`에게만 보여준다.

## 운영 신호와 장애 runbook

Micrometer는 append latency/outcome, replay event count/duration, snapshot fallback, projection batch/lag/rebuild/quarantine, close batch, reconciliation finding을 기록한다. tenant, stream ID, event ID를 tag로 사용하지 않는다. Actuator health는 event store 연결, ACTIVE generation, checkpoint/lag, quarantine 상태를 bounded query로 확인한다.

| 증상 | 먼저 확인 | 안전한 조치 |
|---|---|---|
| projection lag 증가 | worker lease, checkpoint, event-store head | 처리량을 제한해 catch-up; stale owner를 강제 완료시키지 않는다 |
| poison event | failed position, type, failure digest | codec/upcaster/handler를 수정하고 새 generation을 rebuild한다 |
| snapshot fallback 증가 | reducer version, last hash | snapshot을 폐기하고 replay 비용을 관찰한다 |
| reconciliation mismatch | expected/actual provenance | event history를 수정하지 말고 bounded adjustment 또는 rebuild를 선택한다 |
| command in progress 반복 | receipt lease와 owner token | lease 만료 후 takeover; 이전 owner terminal write는 CAS로 거부한다 |
| ACTIVE projection 없음 | generation state와 마지막 switch | 건강한 RETIRED generation rollback 또는 새 BUILDING generation 복구 |

## Microservice로 분리할 때

처음부터 서비스를 나누지 않는다. modular monolith에서 stream boundary, event schema, lag SLO, rebuild runbook을 먼저 검증한다.

![Microservice extraction](../../docs/images/readme-diagrams/usage-billing-event-sourcing-microservices-01.png)

[Microservice SVG 원본](../../docs/images/readme-diagrams/usage-billing-event-sourcing-microservices-01.svg)

분리할 때 Meter, Usage, Billing, Invoice, Query가 각자 PostgreSQL과 event/outbox를 소유한다. shared database와 XA를 쓰지 않는다. Local transaction에서 domain event와 outbox를 함께 commit하고 Kafka는 at-least-once로 전달한다. 소비자는 `(tenantId, eventId)` inbox receipt로 멱등 처리한다. Broker의 exactly-once 또는 ordering을 database fencing, stream version, inbox 대신 사용하지 않는다. 상세 후속 설계는 [workshop #555](https://github.com/bluetape4k/bluetape4k-workshop/issues/555)에 기록한다.

Projection job lease/fencing의 재사용 라이브러리 위치와 API는 [bluetape4k-projects #1070](https://github.com/bluetape4k/bluetape4k-projects/issues/1070)에서 추적한다.

## 코드 탐색 순서

1. `domain/`의 event와 `AggregateReducers.kt`에서 불변식과 상태 전이를 읽는다.
2. `eventstore/`의 `CanonicalEventHash`, `EventCodecRegistry`, `AggregateReplayer`를 따라간다.
3. `persistence/EventStoreRepository.kt`와 `EventSourcingExposedJdbcRepository.kt`에서 Exposed authority를 확인한다.
4. `idempotency/CommandReceiptService.kt`에서 owner fencing과 replay를 본다.
5. `projection/`과 `worker/ProjectionWorker.kt`에서 generation, lease, poison recovery를 본다.
6. `BillingEventSourcingStressTest`에서 10,000 event close/rebuild/reconciliation 전체 경로를 실행한다.

이 순서로 보면 controller보다 먼저 “무엇이 권위이고 무엇이 다시 만들 수 있는가”가 보인다.

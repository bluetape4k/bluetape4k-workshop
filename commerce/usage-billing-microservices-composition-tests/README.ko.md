# 이벤트 소싱 사용량 과금 마이크로서비스

[English](README.md) | 한국어

이 고급 Spring Boot 4 / Java 25 예제는 Meter, Usage, Billing, Invoice, Query를 독립 배포 가능한 다섯 서비스로 분리한다. 단일 PostgreSQL ledger 예제를 이미 이해했고, 서비스별 데이터베이스와 Kafka at-least-once delivery가 만드는 실패 경계를 운영 가능한 방식으로 학습하려는 팀을 위한 예제다.

![서비스 소유권과 전달 경계](../../docs/images/readme-diagrams/usage-billing-microservices-architecture-01.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-microservices-architecture-01.svg)

각 서비스는 자신의 PostgreSQL과 integration decoder를 소유한다. 모든 DB 접근은 JetBrains Exposed와 `bluetape4k-exposed-jdbc`만 사용하고, concrete repository는 `ExposedJdbcRepository`를 구현한다. 공유 DB, raw SQL/JDBC, XA, end-to-end exactly-once 주장은 없다.

각 decoder는 required envelope field에 Bluetape validation helper를 사용하고, durable boundary payload type은 명시적인 serialization ID를 둔다. debug outcome log에는 event ID, event type, quarantine reason처럼 안정적인 운영 필드만 기록하며 raw financial payload는 남기지 않는다.

## 핵심 원칙

| 질문 | 답 |
| --- | --- |
| Kafka send 뒤 프로세스가 죽으면? | outbox lease 만료 뒤 재전송한다. duplicate는 정상 경로다. |
| duplicate가 금액을 두 번 만들면? | receiver가 `(tenantId, eventId, payloadDigest)` inbox를 먼저 durable하게 결정한다. |
| 같은 event ID인데 payload가 다르면? | 재처리하지 않고 `QUARANTINED`으로 남긴다. |
| poison event가 partition 전체를 멈추면? | permanent contract 오류는 Query quarantine으로 기록한 뒤 offset을 진행한다. transient DB 오류만 Kafka redelivery를 위해 전파한다. |
| 가격의 authority는 누구인가? | Meter가 원본 가격 authority이며, Billing은 자신의 DB에 복제한 pricing evidence로만 rating한다. |

![Outbox와 Inbox 상태 전이](../../docs/images/readme-diagrams/usage-billing-microservices-outbox-inbox-state-01.png)

[State diagram SVG source](../../docs/images/readme-diagrams/usage-billing-microservices-outbox-inbox-state-01.svg)

![At-least-once 전달 경로](../../docs/images/readme-diagrams/usage-billing-microservices-delivery-01.png)

[Delivery SVG source](../../docs/images/readme-diagrams/usage-billing-microservices-delivery-01.svg)

![Poison 격리와 redrive](../../docs/images/readme-diagrams/usage-billing-microservices-poison-recovery-01.png)

[Poison recovery SVG source](../../docs/images/readme-diagrams/usage-billing-microservices-poison-recovery-01.svg)

## 언제 선택해야 하나

대부분의 팀은 먼저 [`usage-metering-billing-ledger`](../usage-metering-billing-ledger/)를 선택해야 한다. 이 예제는 독립 배포/소유권/스케일링 경계가 실제로 필요하고, topic·lag·schema compatibility·outbox/inbox·quarantine/redrive를 함께 운영할 준비가 된 경우에만 적합하다.

## 운영 결정 규칙

- 가격 선택은 Billing이 로컬에 복제한 근거로 수행하며, Meter는 가격 이력의
  권위 있는 발행자로 남습니다.
- `UsageAccepted` payload는 Billing이 Usage나 Meter table을 동기 조회하지 않고
  자체 근거로 rate할 수 있는 가격 provenance를 포함합니다.
- Invoice는 기존 line을 수정하지 않습니다. correction은 원본 event를 가리키는
  새 `AdjustmentPosted` 기반 line입니다.
- Query에는 financial command endpoint가 없습니다. read projection,
  checkpoint, quarantine과 operator redrive audit만 소유합니다.
- offset commit은 durable inbox/quarantine 결정 뒤에만 수행하며 best-effort
  log line 뒤에는 수행하지 않습니다.

## 검증 실행

```bash
./gradlew :commerce-usage-billing-meter-service:test \
  :commerce-usage-billing-usage-service:test \
  :commerce-usage-billing-billing-service:test \
  :commerce-usage-billing-invoice-service:test \
  :commerce-usage-billing-query-service:test \
  --max-workers=1

./gradlew :commerce-usage-billing-meter-service:integrationTest \
  :commerce-usage-billing-usage-service:integrationTest \
  :commerce-usage-billing-billing-service:integrationTest \
  :commerce-usage-billing-invoice-service:integrationTest \
  :commerce-usage-billing-query-service:integrationTest \
  --max-workers=1

./gradlew :commerce-usage-billing-microservices-composition-tests:test \
  :commerce-usage-billing-microservices-composition-tests:integrationTest \
  --max-workers=1
```

기본 test는 container 없이 envelope/decoder/idempotency/state/repository boundary를 검증한다. PostgreSQL integration test는 Bluetape Testcontainers fixture로 Exposed unique constraint, local effect+outbox atomicity, replay, digest conflict, fenced outbox completion을 검증한다.

## Composition 검증과 운영 의미

composition module은 Kafka broker 하나와 서로 분리된 PostgreSQL container 다섯 개를 시작한다. 서비스끼리
Spring context, 데이터베이스, decoder, producer DTO를 공유하지 않는다. 이 suite는 exactly-once를 증명하는
benchmark가 아니라, 장애 모드를 실행 가능한 형태로 보여 주는 카탈로그다.

| 시나리오 | 테스트가 증명하는 것 | 운영에서의 대응 |
| --- | --- | --- |
| publication 지연 | commit된 Meter price는 relay가 복구될 때까지 local outbox에 남는다 | 다른 서비스 DB에서 가격을 재구성하지 말고 outbox부터 확인한다 |
| duplicate delivery | `UsageAccepted` 재전달은 Billing financial effect를 하나만 만든다 | event ID와 digest를 보존하고 duplicate를 정상 성공 경로로 다룬다 |
| aggregate 순서 역전 | version 2는 version 1이 올 때까지 defer되고 이후 retry할 수 있다 | aggregate key/version을 관측하고 gap 상태에서 임의 rating하지 않는다 |
| 결정적 transport fault | test-only Meter fault는 claimed row를 `RETRY_WAIT`로 옮기고, 정상 Kafka transport가 이후 전달한다 | 빠른 기본 증명은 결정적으로 유지하고, 기존 row를 재시도하며 financial fact를 새로 만들지 않는다 |
| 실제 broker-path 장애 | Toxiproxy가 host-JVM service와 Kafka custom listener 사이 TCP 양방향을 끊으면 기존 Meter outbox row가 `RETRY_WAIT`가 되고, 경로 복구 뒤 전달된다 | outbox를 recovery authority로 사용하고, 예외 simulation만이 아니라 실제 client route를 검증한다 |
| 서비스 재시작 | Usage 재시작 뒤에도 price evidence가 남아 있고 publication을 계속한다 | process memory나 consumer offset이 아니라 local PostgreSQL이 복구 authority다 |
| poison contract | 지원하지 않는 Query record 하나는 quarantine되고, 독립 valid record는 계속 처리되며 redrive request가 audit된다 | permanent failure를 격리하고 replay 전에는 external retained source에서 immutable original envelope을 조회한다 |
| schema evolution | Query는 additive v2를 수용하고 v99는 quarantine한다 | compatibility를 Jackson 기본 동작이 아니라 decoder의 명시적 결정으로 둔다 |
| tenant isolation | `TENANT_a` principal은 tenant `b` projection에 접근할 수 없다 | projection도 read boundary에서 target tenant를 authorize한다 |
| correction | `AdjustmentPosted`는 기존 line을 바꾸지 않고 두 번째 Invoice line과 correction event를 추가한다 | financial history는 원본 event를 참조하는 새 fact로 보정한다 |
| raw-access guard | service source에서 raw JDBC/SQL 실행 API를 탐지한다 | persistence는 Exposed repository 안에만 두고 test fixture도 예외로 만들지 않는다 |

test-only Meter fault switch는 의도적으로 결정적이다. composition fixture가 실행되는 동안만 production Kafka
transport를 감싸고, 복구 시에는 동일한 실제 Kafka transport에 위임한다. 이 방식은 빠른 outbox retry 증명을
안정적으로 유지한다.

`BrokerPathRecoveryIntegrationTest`는 이를 보완하는 nightly 증명이다. Toxiproxy와 Kafka를 하나의 Docker
network에서 시작하고, Kafka custom listener가 proxy mapped endpoint를 advertise하도록 만든 뒤 proxy 양방향을
끊는다. 따라서 Spring Kafka client는 metadata가 돌려준 direct broker endpoint로 우회해 복구할 수 없다. toxic을
제거한 뒤에는 같은 outbox row를 재시도하고 Usage price evidence가 도착할 때까지 기다린다. 이는 single-broker TCP
path recovery이지 Kafka leader election, ISR, replication, cluster failover 증명이 아니며, 그 범위는 독립 multi-broker
reference에 둔다.

![Append-only correction 경로](../../docs/images/readme-diagrams/usage-billing-microservices-correction-01.png)

[Correction SVG source](../../docs/images/readme-diagrams/usage-billing-microservices-correction-01.svg)

## 단계적 추출과 rollback

1. ledger/modular monolith를 source of truth로 유지하고 Meter와 Usage를 먼저
   분리하면서 accepted usage 수와 price evidence를 dual-check합니다.
2. Billing의 replicated price evidence와 rated-charge parity를 추가하고,
   downstream routing 전에 outbox를 비웁니다.
3. Invoice materialization과 Query read model은 마지막에 추가하고 mutable row가
   아니라 immutable source-event ID와 total을 비교합니다.
4. rollback은 traffic routing만 되돌립니다. service DB를 역복사하거나 공개된
   financial history를 고치지 않고 durable event, outbox, inbox, quarantine
   audit을 reconciliation에 남깁니다.

![단계적 extraction과 rollback](../../docs/images/readme-diagrams/usage-billing-microservices-extraction-01.png)

[Extraction SVG source](../../docs/images/readme-diagrams/usage-billing-microservices-extraction-01.svg)

운영자는 먼저 outbox backlog/state, oldest retry, inbox/quarantine reason과
영향받은 aggregate key를 확인합니다. Query redrive는 audit 가능한 요청이며,
immutable original envelope를 조회·재발행하는 일은 외부 retained source가
수행합니다. 금액이나 가격을 수정하는 표면이 아닙니다.

## 모듈 맵

| 모듈 | 목적 |
| --- | --- |
| `usage-billing-meter-service` | 가격 권위와 outbox |
| `usage-billing-usage-service` | usage receipt, price evidence inbox, accepted usage outbox |
| `usage-billing-billing-service` | pricing evidence inbox, rating, charge outbox |
| `usage-billing-invoice-service` | charge inbox와 불변 invoice line |
| `usage-billing-query-service` | projection inbox, checkpoint, quarantine, 운영 진단 |
| `usage-billing-microservices-composition-tests` | contract/composition 테스트 전용 경계 |

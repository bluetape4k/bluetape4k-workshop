# 이벤트 소싱 사용량 과금 마이크로서비스

[English](README.md) | 한국어

이 고급 Spring Boot 4 / Java 25 예제는 Meter, Usage, Billing, Invoice, Query를 독립 배포 가능한 다섯 서비스로 분리한다. 단일 PostgreSQL ledger 예제를 이미 이해했고, 서비스별 데이터베이스와 Kafka at-least-once delivery가 만드는 실패 경계를 운영 가능한 방식으로 학습하려는 팀을 위한 예제다.

![서비스 소유권과 전달 경계](../../docs/images/readme-diagrams/usage-billing-microservices-architecture-01.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-microservices-architecture-01.svg)

각 서비스는 자신의 PostgreSQL과 integration decoder를 소유한다. 모든 DB 접근은 JetBrains Exposed와 `bluetape4k-exposed-jdbc`만 사용하고, concrete repository는 `ExposedJdbcRepository`를 구현한다. 공유 DB, raw SQL/JDBC, XA, end-to-end exactly-once 주장은 없다.

## 핵심 원칙

| 질문 | 답 |
| --- | --- |
| Kafka send 뒤 프로세스가 죽으면? | outbox lease 만료 뒤 재전송한다. duplicate는 정상 경로다. |
| duplicate가 금액을 두 번 만들면? | receiver가 `(tenantId, eventId, payloadDigest)` inbox를 먼저 durable하게 결정한다. |
| 같은 event ID인데 payload가 다르면? | 재처리하지 않고 `QUARANTINED`으로 남긴다. |
| poison event가 partition 전체를 멈추면? | permanent contract 오류는 Query quarantine으로 기록한 뒤 offset을 진행한다. transient DB 오류만 Kafka redelivery를 위해 전파한다. |
| 가격의 authority는 누구인가? | Meter가 원본 가격 authority이며, Billing은 자신의 DB에 복제한 pricing evidence로만 rating한다. |

![Outbox와 Inbox 상태 전이](../../docs/images/readme-diagrams/usage-billing-microservices-state-01.png)

[State diagram SVG source](../../docs/images/readme-diagrams/usage-billing-microservices-state-01.svg)

## 언제 선택해야 하나

대부분의 팀은 먼저 [`usage-metering-billing-ledger`](../usage-metering-billing-ledger/)를 선택해야 한다. 이 예제는 독립 배포/소유권/스케일링 경계가 실제로 필요하고, topic·lag·schema compatibility·outbox/inbox·quarantine/redrive를 함께 운영할 준비가 된 경우에만 적합하다.

## 단계적 추출과 rollback

1. ledger를 authority로 유지한 채 Meter와 Usage를 먼저 분리하고 accepted usage/price evidence를 dual-run 비교한다.
2. Billing의 replicated price evidence와 charge total parity를 확인하고, outbox backlog를 비운 뒤 downstream routing을 전환한다.
3. Invoice와 Query는 마지막에 추가한다. mutable row가 아니라 immutable source event ID와 total을 비교한다.
4. rollback은 traffic routing만 되돌린다. 서비스 DB 복사, published financial history rewrite, 금액/가격 수정은 하지 않는다.

운영자는 먼저 outbox backlog/state, oldest retry, inbox/quarantine reason, aggregate key를 확인한다. redrive는 저장된 payload를 수정하지 않고 audit을 남기는 재전달 요청이지, 재무 상태 수정 API가 아니다.

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
| transport 장애 | 결정적인 Meter transport fault는 claimed row를 `RETRY_WAIT`로 옮기고, 정상 Kafka transport가 이후 전달한다 | 재시도는 기존 row로 수행하고 financial fact를 새로 만들지 않는다 |
| 서비스 재시작 | Usage 재시작 뒤에도 price evidence가 남아 있고 publication을 계속한다 | process memory나 consumer offset이 아니라 local PostgreSQL이 복구 authority다 |
| poison contract | 지원하지 않는 Query record 하나는 quarantine되고, 독립 valid record는 계속 처리되며 redrive가 기록된다 | permanent failure를 격리하고 원본 payload를 운영 검토용으로 보존한다 |
| schema evolution | Query는 additive v2를 수용하고 v99는 quarantine한다 | compatibility를 Jackson 기본 동작이 아니라 decoder의 명시적 결정으로 둔다 |
| tenant isolation | `TENANT_a` principal은 tenant `b` projection에 접근할 수 없다 | projection도 read boundary에서 target tenant를 authorize한다 |
| correction | `AdjustmentPosted`는 기존 line을 바꾸지 않고 두 번째 Invoice line과 correction event를 추가한다 | financial history는 원본 event를 참조하는 새 fact로 보정한다 |
| raw-access guard | service source에서 raw JDBC/SQL 실행 API를 탐지한다 | persistence는 Exposed repository 안에만 두고 test fixture도 예외로 만들지 않는다 |

test-only Meter fault switch는 의도적으로 결정적이다. composition fixture가 실행되는 동안만 production Kafka
transport를 감싸고, 복구 시에는 동일한 실제 Kafka transport에 위임한다. 이 방식은 local Docker broker를
pause하는 것이 모든 outage를 대표한다고 과장하지 않으면서 outbox retry를 안정적으로 검증한다.

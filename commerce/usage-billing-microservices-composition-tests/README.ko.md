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

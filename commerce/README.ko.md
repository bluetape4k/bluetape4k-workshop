# Commerce 예제

[English](README.md) | 한국어

이 그룹은 서로 독립적으로 revision을 갖는 여러 aggregate, durable application
event, 운영자가 확인할 수 있는 복구 경계가 함께 필요한 end-to-end commerce
workflow를 다룹니다.

usage-billing 서비스 경계는 아래 그림과 같습니다. 각 서비스는 로컬
PostgreSQL 권위를 소유하고 Kafka topic으로 서비스 경계를 넘습니다. 이 그림은
처리량 chart가 아니라 소스 코드에 근거한 소유권 구조를 보여 줍니다.

![Usage billing 서비스 경계](../docs/images/readme-diagrams/usage-billing-service-boundaries-01.ko.png)

[Architecture SVG source](../docs/images/readme-diagrams/usage-billing-service-boundaries-01.ko.svg)

## 모듈

| 모듈 | 초점 | 인프라 |
|------|------|--------|
| [`order-lifecycle-fulfillment`](order-lifecycle-fulfillment/) | 독립적인 주문, 결제, 재고, 배송, 환불 생명주기 | PostgreSQL (Testcontainers) |
| [`reservation-control-plane`](reservation-control-plane/) | PostgreSQL이 권위를 갖는 예약, 멱등 재시도, waitlist offer, expiry | PostgreSQL + Redis (Testcontainers) |
| [`event-sourced-promotion-voucher-campaign`](event-sourced-promotion-voucher-campaign/) | Append-only campaign/claim stream, snapshot, lease 기반 projection, fencing rebuild, position-aware HTTP/SSE | PostgreSQL (Testcontainers) |
| [`promotion-voucher-campaign`](promotion-voucher-campaign/) | 캠페인 수량, 바우처 할당/사용, review, SSE, reconciliation | PostgreSQL + Redis (Testcontainers) |
| [`pre-generated-voucher-pool`](pre-generated-voucher-pool/) | PostgreSQL이 권위를 갖는 사전 생성 바우처 예약, 일회 reveal/교체, revoke와 reconciliation | PostgreSQL + Redis (Testcontainers) |
| [`concert-ticket-flash-sale`](concert-ticket-flash-sale/) | 대기실 admission, USER/IP 구매 guard, 결제/환불 복구, 티켓 상태 기반 restock | PostgreSQL + Redis (Testcontainers) |
| [`usage-metering-billing-ledger`](usage-metering-billing-ledger/) | 멱등 usage, 시간 버전 가격, 재시작 가능한 close, 불변 ledger/invoice | PostgreSQL (Testcontainers) |
| [`usage-metering-billing-event-sourcing`](usage-metering-billing-event-sourcing/) | Event append/replay/upcast, snapshot, fenced projection rebuild, correction/reconciliation | PostgreSQL (Testcontainers) |
| [`usage-billing-microservices`](usage-billing-microservices-composition-tests/) | local outbox/inbox와 명시적 Kafka delivery boundary를 가진 독립 Spring Boot 서비스 5개 | PostgreSQL + Kafka (Testcontainers) |
| [`usage-billing-meter-service`](usage-billing-meter-service/) | 불변 가격 버전, 멱등 활성화, `meter.events.v1` 발행 | PostgreSQL + Kafka |
| [`usage-billing-usage-service`](usage-billing-usage-service/) | 가격 근거 inbox, 멱등 사용량 접수, `usage.events.v1` 발행 | PostgreSQL + Kafka |
| [`usage-billing-billing-service`](usage-billing-billing-service/) | 복제된 가격 근거, 결정적 charge 산정, `billing.events.v1` 발행 | PostgreSQL + Kafka |
| [`usage-billing-invoice-service`](usage-billing-invoice-service/) | append-only invoice line, correction event, `invoice.events.v1` 발행 | PostgreSQL + Kafka |
| [`usage-billing-query-service`](usage-billing-query-service/) | 여러 topic projection, tenant summary, quarantine, operator redrive audit | PostgreSQL + Kafka |

각 모듈은 blocking Spring MVC와 Exposed JDBC 작업에 Java 25 virtual thread를
사용합니다. 요청 동시성은 virtual thread로 확장하지만, PostgreSQL 동시성은
HikariCP로 제한합니다.

## 실행

```bash
./gradlew :commerce-order-lifecycle-fulfillment:test --max-workers=1
./gradlew :commerce-reservation-control-plane:test --max-workers=1
./gradlew :commerce-event-sourced-promotion-voucher-campaign:integrationTest --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:test --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:test --max-workers=1
./gradlew :commerce-concert-ticket-flash-sale:test --max-workers=1
./gradlew :commerce-usage-metering-billing-ledger:integrationTest --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:integrationTest --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:stressTest --max-workers=1
./gradlew :commerce-usage-billing-meter-service:test --max-workers=1
./gradlew :commerce-usage-billing-usage-service:test --max-workers=1
./gradlew :commerce-usage-billing-billing-service:test --max-workers=1
./gradlew :commerce-usage-billing-invoice-service:test --max-workers=1
./gradlew :commerce-usage-billing-query-service:test --max-workers=1
./gradlew :commerce-usage-billing-microservices-composition-tests:integrationTest --max-workers=1
./scripts/smoke-validate.sh commerce
```

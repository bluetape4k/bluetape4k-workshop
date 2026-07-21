# Commerce 예제

[English](README.md) | 한국어

이 그룹은 서로 독립적으로 revision을 갖는 여러 aggregate, durable application
event, 운영자가 확인할 수 있는 복구 경계가 함께 필요한 end-to-end commerce
workflow를 다룹니다.

## 모듈

| 모듈 | 초점 | 인프라 |
|------|------|--------|
| [`order-lifecycle-fulfillment`](order-lifecycle-fulfillment/) | 독립적인 주문, 결제, 재고, 배송, 환불 생명주기 | PostgreSQL (Testcontainers) |
| [`reservation-control-plane`](reservation-control-plane/) | PostgreSQL이 권위를 갖는 예약, 멱등 재시도, waitlist offer, expiry | PostgreSQL + Redis (Testcontainers) |
| [`promotion-voucher-campaign`](promotion-voucher-campaign/) | 캠페인 수량, 바우처 할당/사용, review, SSE, reconciliation | PostgreSQL + Redis (Testcontainers) |
| [`concert-ticket-flash-sale`](concert-ticket-flash-sale/) | 대기실 admission, USER/IP 구매 guard, 결제/환불 복구, 티켓 상태 기반 restock | PostgreSQL + Redis (Testcontainers) |

각 모듈은 blocking Spring MVC와 Exposed JDBC 작업에 Java 25 virtual thread를
사용합니다. 요청 동시성은 virtual thread로 확장하지만, PostgreSQL 동시성은
HikariCP로 제한합니다.

## 실행

```bash
./gradlew :commerce-order-lifecycle-fulfillment:test --max-workers=1
./gradlew :commerce-reservation-control-plane:test --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:test --max-workers=1
./gradlew :commerce-concert-ticket-flash-sale:test --max-workers=1
./scripts/smoke-validate.sh commerce
```

# Transactional Outbox Pattern — bluetape4k Workshop

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Transactional Outbox Pattern — bluetape4k Workshop**을 실행 가능한 메시지 기반 워크플로 워크숍 조각으로 다룹니다. 개발자가 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 맞춥니다.

## 시퀀스 다이어그램

Kotlin, Spring Boot 4, JetBrains Exposed, Kafka를 사용해 **Transactional Outbox** 패턴을 보여 줍니다.
이 패턴은 도메인 상태 변경과 그에 대응하는 Kafka 이벤트가 원자적으로 기록되도록 보장합니다. 이중 쓰기 문제도, 조용한 메시지 손실도 없습니다.

---

## 아키텍처

![Transactional Outbox Pattern — bluetape4k Workshop Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/messaging-transactional-outbox-readme-architecture-01.png)

---

## 문제 / 해결

### Before — 순진한 이중 쓰기(깨진 방식)

```kotlin
// WRONG: two independent I/O operations — no atomicity guarantee
orderRepository.save(order)          // succeeds
kafkaTemplate.send("order-events", …) // crashes → event lost forever
```

두 호출 사이에서 프로세스가 중단되면 Kafka 메시지가 조용히 유실됩니다.
Kafka 전송은 성공했지만 DB 쓰기가 실패하면, 대응하는 주문 row 없이 메시지만 발행됩니다.

### After — Transactional Outbox(올바른 방식)

```kotlin
// RIGHT: one transaction, two table writes
@Transactional
fun placeOrder(…): OrderResponse {
    val orderId = OrderTable.insertAndGetId { … }          // domain row
    OutboxEventTable.insert { … }                          // outbox row (same tx)
    return getOrderResponse(orderId.value)
}
```

백그라운드 `OutboxPublisher`는 `outbox_events`를 폴링하고 Kafka로 발행한 뒤 row를 `PUBLISHED`로 표시합니다. 각 이벤트는 별도의 `REQUIRES_NEW` 트랜잭션에서 처리됩니다.
Kafka를 일시적으로 사용할 수 없으면 row는 `FAILED` 상태로 남고, `MAX_RETRY`(3)회까지 재시도한 뒤 `DEAD_LETTER`로 이동합니다.

---

## 핵심 개념

| 개념 | 상세 |
|---------|--------|
| **원자적 쓰기** | 주문 row와 outbox event row가 하나의 ACID 트랜잭션을 공유합니다. 둘 다 기록되거나 둘 다 기록되지 않습니다. |
| **At-least-once delivery** | 스케줄러는 `PENDING`/`FAILED` 이벤트가 `PUBLISHED`가 될 때까지 재시도합니다. Consumer는 **반드시** 멱등적이어야 합니다. |
| **멱등 publisher** | 이벤트가 이미 `PUBLISHED`이면 `publishEvent(id)`가 즉시 `false`를 반환해, 동시 스케줄러 실행에서 중복 전송을 막습니다. |
| **Dead-letter 승격** | `MAX_RETRY`(3)회 실패 후 이벤트는 수동 점검이나 전용 DLQ consumer 처리를 위해 `DEAD_LETTER`로 이동합니다. |
| **REQUIRES_NEW 재시도 카운터** | `incrementRetry`와 `markPublished`는 각자의 중첩 트랜잭션에서 실행되므로, 외부 트랜잭션이 롤백되어도 재시도 횟수는 항상 저장됩니다. |

---

## 사용한 bluetape4k 기능

| 기능 | 모듈 | 코드 참조 | 이점 |
|---------|--------|----------------|---------|
| `KLogging` | `bluetape4k-logging` | `OrderService`, `OutboxPublisher` | 보일러플레이트 없는 구조적, 컨텍스트 인식 로깅 |
| `requireNotBlank`, `requirePositiveNumber` | `bluetape4k-core` | `OrderService.placeOrder` | 검증된 자기 설명적 입력 계약 |
| `PostgreSQLServer.Launcher` | `bluetape4k-testcontainers` | `AbstractOutboxTest` | 모든 테스트가 공유하는 설정 없는 싱글턴 PostgreSQL 컨테이너 |
| `KafkaServer.Launcher` | `bluetape4k-testcontainers` | `AbstractOutboxTest` | 모든 테스트가 공유하는 설정 없는 싱글턴 Kafka 컨테이너 |
| `Fakers.faker` | `bluetape4k-junit5` | `AbstractOutboxTest` | 하드코딩 문자열 없이 현실적인 locale-aware 테스트 데이터 제공 |

---

## Outbox 테이블 스키마

```sql
CREATE TABLE outbox_events (
    id             BIGSERIAL    PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,          -- e.g. "Order"
    aggregate_id   VARCHAR(100) NOT NULL,          -- string PK of the domain entity
    event_type     VARCHAR(100) NOT NULL,          -- e.g. "OrderPlaced", "OrderStatusChanged"
    payload        TEXT         NOT NULL,          -- JSON-serialised event payload
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING | PUBLISHED | FAILED | DEAD_LETTER
    retry_count    INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    processed_at   TIMESTAMP                       -- set when status → PUBLISHED
);
```

애플리케이션 시작 시 `ExposedConfig`를 통해 Exposed의 `SchemaUtils.create()`가 관리합니다.

---

## 테스트 커버리지

| 테스트 | 시나리오 |
|------|----------|
| `placeOrder creates order and outbox event in same transaction` | 원자적 쓰기를 검증합니다. `placeOrder` 호출마다 주문 row 하나와 outbox event row 하나가 생성됩니다. |
| `POST api-orders creates order and returns 201` | HTTP 계층 smoke test입니다. Controller가 service에 올바르게 연결되는지 확인합니다. |
| `PUT api-orders-id-status updates order status` | REST를 통한 상태 전이가 업데이트된 본문과 함께 `200 OK`를 반환합니다. |
| `publishEvent publishes to Kafka and marks event PUBLISHED` | Happy path입니다. Kafka 전송이 성공하고 row가 `PUBLISHED`로 전이됩니다. |
| `failed publish increments retry count and sets status FAILED` | Kafka를 사용할 수 없을 때 `retryCount`가 1 증가하고 상태가 `FAILED`가 됩니다. |
| `event exceeding max retries moves to DEAD_LETTER` | 기존 실패가 `MAX_RETRY - 1`회인 상태에서 한 번 더 실패하면 상태가 `DEAD_LETTER`가 됩니다. |
| `duplicate publish call is idempotent` | 이미 `PUBLISHED`인 이벤트에 대한 두 번째 `publishEvent` 호출은 `false`를 반환하고 상태는 바뀌지 않습니다. |

---

## 실행

### 사전 조건

Docker가 실행 중이어야 합니다. Testcontainers가 PostgreSQL과 Kafka를 자동으로 시작합니다.

### 테스트

```bash
# Run all tests in this module
./gradlew :messaging-transactional-outbox:test

# Run a specific test class
./gradlew :messaging-transactional-outbox:test \
    --tests "io.bluetape4k.workshop.messaging.outbox.OutboxTransactionTest"

# Run with verbose output
./gradlew :messaging-transactional-outbox:test --info
```

### 애플리케이션(standalone)

실행 중인 PostgreSQL과 Kafka 인스턴스를 가리키는 환경 변수를 설정한 뒤 다음을 실행합니다.

```bash
./gradlew :messaging-transactional-outbox:bootRun
```

스케줄러는 자동으로 시작되며 2초마다 `outbox_events`를 폴링합니다.
Swagger UI는 `http://localhost:8080/swagger-ui/index.html`에서 사용할 수 있습니다.

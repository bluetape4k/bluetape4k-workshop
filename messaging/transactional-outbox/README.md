# Transactional Outbox Pattern

[한국어](README.ko.md) | English

`messaging/transactional-outbox` demonstrates the Transactional Outbox pattern with Spring Boot 4, JetBrains Exposed,
PostgreSQL, and Kafka. Order mutations and outbox rows are written in one database transaction; a scheduled publisher
later sends publishable outbox rows to Kafka and records the delivery state.

## Architecture

![Transactional outbox architecture](../../docs/images/readme-diagrams/messaging-transactional-outbox-readme-architecture-01.png)

The module avoids the dual-write failure mode by never writing the domain row and Kafka message as two independent
operations. `OrderService` writes `orders` and `outbox_events` together. `OutboxPublisher` polls `PENDING` and retryable
`FAILED` rows, sends the payload to `order-events`, and updates the row status in a separate status transaction.

## Publish Lifecycle

![Transactional outbox publish lifecycle](../../docs/images/readme-diagrams/messaging-transactional-outbox-readme-publish-lifecycle-01.png)

`OutboxPublisher.publishEvent(id)` is idempotent for non-publishable rows. A successful Kafka send moves the event to
`PUBLISHED`; a failed send increments `retry_count` and leaves the row retryable until `MAX_RETRY` moves it to
`DEAD_LETTER`.

## Schema ERD

![Transactional outbox ERD](../../docs/images/readme-diagrams/messaging-transactional-outbox-readme-erd-01.png)

`outbox_events.aggregate_id` stores the order id as text because the outbox table is aggregate-neutral. It is a logical
link to `orders.id`, not a database foreign key.

## REST API

| Method | Path | Behavior |
|---|---|---|
| `POST` | `/api/orders` | Inserts an `orders` row and an `OrderPlaced` outbox row in one transaction. |
| `PUT` | `/api/orders/{id}/status` | Updates `orders.status` and writes an `OrderStatusChanged` outbox row. |
| `GET` | `/api/orders/{id}` | Reads one order. |
| `GET` | `/api/orders` | Lists orders ordered by id. |
| `GET` | `/api/orders/outbox/pending` | Demo endpoint for pending outbox rows. |

## Key Components

| Component | Role |
|---|---|
| `OrderService` | Validates input and writes order + outbox rows inside the same Spring transaction. |
| `OrderTable` | Exposed table for `orders`: customer, product, quantity, status, and timestamps. |
| `OutboxEventTable` | Exposed table for publishable integration events and retry state. |
| `OutboxPublisher` | Scheduled publisher; polls every 2 seconds and sends to Kafka topic `order-events`. |
| `KafkaTemplate<String, String>` | Sends `aggregate_id` as the key and serialized payload as the value. |
| `ExposedConfig` | Creates missing `orders` and `outbox_events` tables on startup. |

## Failure Semantics

| State | Meaning |
|---|---|
| `PENDING` | Event is written in the order transaction and has not been sent yet. |
| `PUBLISHED` | Kafka send succeeded and `processed_at` was set. |
| `FAILED` | Last Kafka send failed; the row is still eligible for retry while `retry_count < 3`. |
| `DEAD_LETTER` | Retry budget is exhausted and the row needs manual inspection or a DLQ workflow. |

## bluetape4k Usage

| Feature | Where | Why it matters |
|---|---|---|
| `requireNotBlank`, `requirePositiveNumber` | `OrderService.placeOrder` | Keeps input validation close to the transaction boundary. |
| `KLogging` | `OrderService`, `OutboxPublisher`, `ExposedConfig` | Provides concise structured logging. |
| `PostgreSQLServer.Launcher` | `AbstractOutboxTest` | Starts a shared PostgreSQL Testcontainer for integration tests. |
| `KafkaServer.Launcher` | `AbstractOutboxTest` | Starts a shared Kafka Testcontainer for publisher tests. |
| `Fakers.faker` | `AbstractOutboxTest` | Supplies realistic test data without hard-coded literals. |

## Atomic Write

```kotlin
@Transactional
fun placeOrder(customerId: String, product: String, quantity: Int): OrderResponse {
    val orderId = OrderTable.insertAndGetId { /* domain row */ }

    OutboxEventTable.insert {
        it[aggregateType] = "Order"
        it[aggregateId] = orderId.value.toString()
        it[eventType] = "OrderPlaced"
        it[status] = OutboxStatus.PENDING
    }

    return getOrderResponse(orderId.value)
}
```

## Publisher

```kotlin
@Scheduled(fixedDelay = 2000)
@Transactional(readOnly = true)
fun publishPendingEvents() {
    val pendingIds = OutboxEventTable.selectAll()
        .where { publishableRowsWithRetryBudget }
        .map { it[OutboxEventTable.id].value }

    pendingIds.forEach { id -> publishEvent(id) }
}
```

## Tests

```bash
./gradlew :messaging-transactional-outbox:test
```

The test suite covers atomic writes, REST endpoints, successful publish, retry increment, dead-letter transition, and
duplicate publish idempotency.

## Running

Docker must be available when the integration tests use Testcontainers. For `bootRun`, configure a PostgreSQL database
and Kafka broker matching `src/main/resources/application.yml`.

```bash
./gradlew :messaging-transactional-outbox:bootRun
```

Swagger UI is available at `http://localhost:8080/swagger-ui/index.html`.

# exposed/javers-persistence-audit

[English](README.md) | 한국어

이 모듈은 작은 `exposed/javers-audit` 경계를 durable JaVers repository로 확장합니다.
Exposed JDBC는 여전히 `OrderTable`의 현재 row만 담당하고, Redis는
`RedissonCdoSnapshotRepository`를 통해 조회 가능한 JaVers snapshot을 저장합니다.

In-memory JaVers history에서 외부 audit store로 넘어가면 무엇이 달라지는지
학습자가 확인할 수 있도록, 웹 컨트롤러나 분산 트랜잭션 없이 저장소 경계만 드러냅니다.

![exposed/javers-persistence-audit architecture diagram](../../docs/images/readme-diagrams/exposed-javers-persistence-audit-readme-architecture-01.png)

## 런타임 흐름

![exposed/javers-persistence-audit write-order diagram](../../docs/images/readme-diagrams/exposed-javers-persistence-audit-readme-write-order-01.png)

## 이 모듈에서 확인할 내용

| Operation | 소스 기준 동작 |
|---|---|
| `place(author, order)` | author를 검증하고, 초기 JaVers snapshot을 Redis에 commit한 뒤 현재 Exposed row를 upsert |
| `markPaid(author, orderId)` | 현재 row를 읽고, 갱신된 JaVers snapshot을 commit한 뒤 paid row를 materialize |
| `delete(author, orderId)` | `commitShallowDelete`로 terminal JaVers snapshot을 남긴 뒤 현재 row 삭제 |
| `getHistory(orderId)` | instance id로 Redis-backed JaVers snapshot을 조회하고 오래된 순서로 반환 |
| `getLatestSnapshot(orderId)` | bluetape4k `latestSnapshotOrNull<Order>()`로 최신 snapshot 조회 |
| `diff(old, new)` | JaVers나 Exposed에 쓰지 않고 두 immutable order를 비교 |

## Persistence 선택지

| Backend | 적합한 상황 | Read 동작 |
|---|---|---|
| In-memory JaVers | `exposed/javers-audit`의 첫 audit-boundary 학습 | 서비스를 다시 만들면 history가 사라짐 |
| Redis / Redisson | 이 모듈의 durable audit history 경로 | 서비스를 다시 만들어도 history와 latest snapshot을 조회 가능 |
| Kafka JaVers repository | Event-stream fan-out과 downstream projection | Write-only stream이므로 history 조회 전 Redis, Exposed 또는 다른 read model로 projection 필요 |

이 모듈은 snapshot을 저장하고 다시 읽을 수 있는 Redis 경로를 구현합니다.
Kafka는 write-only audit stream boundary로 설명하여, `getHistory()`를 Kafka repository가
직접 처리한다고 오해하지 않게 합니다.

## Order Schema

`OrderTable`은 materialized current row만 저장합니다. 이 모듈에서 JaVers history는
관계형 테이블로 모델링하지 않습니다.

| Column | Type | Notes |
|---|---|---|
| `id` | `varchar(64)` | Primary key이자 JaVers entity id |
| `customer_id` | `varchar(64)` | 예제 aggregate의 customer reference |
| `status` | `varchar(16)` | `PLACED` 또는 `PAID` lifecycle state |
| `total_amount` | `decimal(19,4)` | Floating-point rounding 없는 decimal storage |

## 사용 예

```kotlin
val service = RedisOrderAuditFactory.create("workshop-orders", redisson)

val order = Order(
    id = "order-100",
    customerId = "customer-100",
    status = OrderStatus.PLACED,
    totalAmount = BigDecimal("19.99"),
)

service.place("alice", order)
val paid = service.markPaid("alice", order.id)

val history = service.getHistory(order.id)
val latest = service.getLatestSnapshot(order.id)
val diff = service.diff(order, paid)

service.delete("alice", order.id)
```

## Failure Boundary

서비스는 현재 Exposed row를 쓰기 전에 JaVers snapshot을 먼저 commit합니다.
Redis-backed audit sink가 실패하면 예외를 그대로 전파하고, audit 없는 current-row write를
성공으로 처리하지 않습니다. 이 규칙은 워크숍에서 경계를 명확히 보여주기 위한 계약이며,
cross-store distributed transaction을 대체하지는 않습니다.

## 테스트

```bash
./gradlew :exposed-javers-persistence-audit:test
```

테스트는 service rebuild 이후 Redis-backed history 유지, read-only diff,
terminal delete snapshot, audit sink failure propagation을 검증합니다.

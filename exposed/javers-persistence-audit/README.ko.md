# exposed/javers-persistence-audit

[English](README.md) | 한국어

이 모듈은 작은 `exposed/javers-audit` 경계를 durable JaVers repository로 확장합니다.
직접 저장 경로는 Issue #892의 Exposed current row와 bounded Redisson history를 유지합니다.
최초 모듈 Issue #290의 후속 projection 경로는 snapshot을 Kafka에 발행하고,
dependencies 2.0.0이 관리하는 JaVers Kafka projection API로 조회 가능한 Lettuce Redis
repository를 복원합니다.

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
| `getHistory(orderId, limit = 100)` | `1..100` 상한을 instance query에 pushdown하고 bounded snapshot을 newest-first로 반환 |
| `getLatestSnapshot(orderId)` | bluetape4k `latestSnapshotOrNull<Order>()`로 최신 snapshot 조회 |
| `diff(old, new)` | JaVers나 Exposed에 쓰지 않고 두 immutable order를 비교 |
| `replayUntilIdle(maxIdlePolls = 3)` | 기본 연속 idle poll 3회까지 single-partition Kafka stream을 Redis로 replay |

## Persistence 선택지

| Backend | 적합한 상황 | Read 동작 |
|---|---|---|
| In-memory JaVers | `exposed/javers-audit`의 첫 audit-boundary 학습 | 서비스를 다시 만들면 history가 사라짐 |
| Redis / Redisson | 이 모듈의 durable audit history 경로 | Exact-instance history에서 요청한 tail range만 읽고 newest-first로 decode |
| Kafka → Lettuce Redis | Event-stream fan-out, restart rebuild, 조회 가능한 projection | Kafka는 write-only이고 `KafkaCdoSnapshotProjector`가 Redis read/head repository를 복원 |

`KafkaRedisOrderAuditPipeline`은 command와 Redis query만 노출하고 write-only Kafka
repository를 숨깁니다. JaVers가 다음 version과 snapshot type을 계산하려면 projected Redis
head가 필요하므로 `place` 뒤 `markPaid` 또는 `delete` 전에 replay해야 합니다. Facade는 해당
head가 없으면 fail-closed로 거부합니다.

## Kafka에서 Redis로 Projection

```kotlin
KafkaRedisOrderAuditFactory.create(
    repositoryName = "workshop-orders-projection",
    topic = "order-audit-snapshots",
    producerConfigs = producerConfigs,
    consumerConfigs = consumerConfigs + (ConsumerConfig.GROUP_ID_CONFIG to "order-audit-projector"),
    redisClient = lettuceClient,
).use { pipeline ->
    // Single-partition topic은 application/operator가 미리 provision합니다.
    pipeline.replayUntilIdle()
    pipeline.place("alice", order)
    pipeline.replayUntilIdle()

    pipeline.markPaid("bob", order.id)
    pipeline.replayUntilIdle()

    val latest = pipeline.getLatestSnapshot(order.id)
}
```

Consumer 계약은 nonblank group id를 요구하고 auto commit을 끄며 `earliest`를 사용합니다.
Application/operator는 topic을 partition 하나로 미리 provision합니다. 새 pipeline은 첫 mutation
전에 initial catch-up을 요구하므로 재시작한 process가 stale Redis head에서 write할 수 없습니다.
성공했거나 commit-unknown인 모든 mutation 뒤에는 다음 mutation 전 catch-up이 필요합니다.
Projector는 multi-partition topic을 poll하거나 Redis head를 변경하기 전에 거부합니다.
Batch가 실패하면 Kafka committed offset은 전진하지 않습니다.
실패한 instance를 닫고 같은 group의 새 consumer/projector를 시작하면 이미 projected된 snapshot은
skip하고 실패한 snapshot부터 replay한 뒤 batch를 commit합니다.

`replayUntilIdle`은 daemon이 아닌 finite catch-up helper입니다. 기본 연속 empty poll 3회는 최초
assignment empty poll을 허용합니다. 실제 application은 외부 startup deadline, retry budget,
continuous worker lifecycle을 별도로 소유해야 합니다.

## Bounded History 계약

`getHistory(orderId, limit)`은 `1..100`을 허용하고 한 인자 JVM overload는 100을 사용합니다.
Service는 상한을 `QueryBuilder.limit`에 전달하며, 이미 materialize한 결과를 다시 정렬하거나
잘라내지 않습니다. 반환 순서는 JaVers 2.0.0 consumer 계약인 newest-first입니다. 이는 이전
workshop의 oldest-first 동작을 의도적으로 바꾸므로 `first()`를 초기 snapshot으로 사용한
호출자는 `last()`를 사용하거나 presentation ordering을 명시적으로 적용해야 합니다.

Filter 없는 exact-instance query는 `BoundedRedissonCdoSnapshotRepository`가 기존 snapshot
list에서 `range(-limit, -1)`만 읽고 선택된 구간만 decode합니다. Skip, aggregate,
author/date/version, commit, property, snapshot-type filter가 있으면 의미 보존을 위해 upstream
repository로 fallback합니다.

빈 결과는 unknown order와 audit commit이 없는 order를 구분하지 않으므로 존재 여부는
materialized store에서 확인해야 합니다. `CdoSnapshot`은 domain field를 포함하므로 HTTP/API
호출자는 외부 노출 전에 authorization과 redaction을 적용해야 합니다.

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

val history = service.getHistory(order.id, limit = 2)
val latest = service.getLatestSnapshot(order.id)
val diff = service.diff(order, paid)

service.delete("alice", order.id)
```

## Failure Boundary

서비스는 현재 Exposed row를 쓰기 전에 JaVers snapshot을 먼저 commit합니다.
Redis-backed audit sink가 실패하면 예외를 그대로 전파하고, audit 없는 current-row write를
성공으로 처리하지 않습니다. 이 규칙은 워크숍에서 경계를 명확히 보여주기 위한 계약이며,
cross-store distributed transaction을 대체하지는 않습니다.

Kafka projection은 Lettuce `MULTI`/`EXEC`를 사용합니다. `EXEC` 전 실패는 target, head,
Kafka offset을 그대로 유지합니다. Redis는 `EXEC` 안의 개별 command error를 rollback하지 않으므로
`EXEC` 뒤 command error나 connection loss는 commit-unknown이고 partial projection을 남길 수
있습니다. 이 workshop은 이 경계의 자동 복구를 약속하지 않습니다. 직접 Redisson history 경로만
bounded decode query 예제이며 Lettuce projection 경로는 같은 storage-decode bound를 주장하지 않습니다.

Kafka와 Redis transport security는 caller-owned입니다. Workshop 밖에서는 TLS/SASL, ACL,
credential, topic authorization, snapshot redaction, query authorization을 구성해야 합니다.

`close()`는 idempotent하며 owned resource를 각각 한 번씩 닫습니다. Cleanup이 실패하면 후속
실패를 첫 예외에 suppress하며, `close()`를 다시 호출해 실패 resource를 재시도한다고 가정하면 안 됩니다.

## 테스트

```bash
./gradlew :exposed-javers-persistence-audit:test
```

테스트는 Redis-backed history 유지, bounded Redisson decode, Kafka projection과 duplicate replay,
restart rebuild, 최초 empty poll, mutation 전 single-partition 거부, pre-EXEC batch failure 뒤
same-group retry, lifecycle cleanup, newest-first ordering, audit sink failure propagation을 검증합니다.

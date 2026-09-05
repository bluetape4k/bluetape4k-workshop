# exposed/javers-persistence-audit

[한국어](README.ko.md) | English

This module extends the small `exposed/javers-audit` boundary with durable
JaVers repositories. The direct path keeps the Exposed current row and bounded
Redisson history from Issue #892. The projection path added as a follow-up to
the original module Issue #290 publishes snapshots to Kafka and rebuilds a
queryable Lettuce Redis repository with the dependencies 2.0.0-managed JaVers
Kafka projection API.

Use it when learners need to see what changes after moving from in-memory
JaVers history to an external audit store, without adding web controllers or a
distributed transaction.

![exposed/javers-persistence-audit architecture diagram](../../docs/images/readme-diagrams/exposed-javers-persistence-audit-readme-architecture-01.png)

## Runtime Flow

![exposed/javers-persistence-audit write-order diagram](../../docs/images/readme-diagrams/exposed-javers-persistence-audit-readme-write-order-01.png)

## What This Module Shows

| Operation | Source-backed behavior |
|---|---|
| `place(author, order)` | Validates the author, commits the initial JaVers snapshot to Redis, then upserts the current Exposed row |
| `markPaid(author, orderId)` | Reads the current row, commits an updated JaVers snapshot, then materializes the paid row |
| `delete(author, orderId)` | Records a terminal JaVers snapshot with `commitShallowDelete`, then deletes the current row |
| `getHistory(orderId, limit = 100)` | Pushes a `1..100` limit into the instance query and returns bounded snapshots newest-first |
| `getLatestSnapshot(orderId)` | Uses bluetape4k `latestSnapshotOrNull<Order>()` to read the latest snapshot |
| `diff(old, new)` | Compares two immutable orders without writing to JaVers or Exposed |
| `replayUntilIdle(maxIdlePolls = 3)` | Replays a single-partition Kafka stream into Redis until three consecutive idle polls by default |

## Persistence Choices

| Backend | Best fit | Read behavior |
|---|---|---|
| In-memory JaVers | First audit-boundary lesson in `exposed/javers-audit` | History is lost when the service is rebuilt |
| Redis / Redisson | This module's durable audit history path | Exact-instance history reads only the requested tail range and decodes it newest-first |
| Kafka → Lettuce Redis | Event-stream fan-out, restart rebuild, and queryable projection | Kafka remains write-only; `KafkaCdoSnapshotProjector` restores the Redis read/head repository |

`KafkaRedisOrderAuditPipeline` deliberately exposes commands and Redis queries,
not the write-only Kafka repository. JaVers needs the projected Redis head to
calculate the next version and snapshot type, so callers replay after `place`
before `markPaid` or `delete`; the facade fails closed when that head is absent.

## Kafka to Redis Projection

```kotlin
KafkaRedisOrderAuditFactory.create(
    repositoryName = "workshop-orders-projection",
    topic = "order-audit-snapshots",
    producerConfigs = producerConfigs,
    consumerConfigs = consumerConfigs + (ConsumerConfig.GROUP_ID_CONFIG to "order-audit-projector"),
    redisClient = lettuceClient,
).use { pipeline ->
    // The single-partition topic is provisioned by the application/operator.
    pipeline.replayUntilIdle()
    pipeline.place("alice", order)
    pipeline.replayUntilIdle()

    pipeline.markPaid("bob", order.id)
    pipeline.replayUntilIdle()

    val latest = pipeline.getLatestSnapshot(order.id)
}
```

The consumer contract requires a nonblank group id, disables auto commit, and
uses `earliest`. The application or operator pre-provisions the topic with
exactly one partition. A new pipeline requires an initial catch-up before its
first mutation, so a restarted process cannot write from a stale Redis head.
Every successful or commit-unknown mutation requires another catch-up before a
later mutation. The projector rejects a multi-partition topic before polling
or changing the Redis head. A failed
batch does not advance the committed Kafka offset. Close the failed instance
and start a new consumer/projector with the same group: already projected
snapshots are skipped and the failed snapshot is replayed before the batch is
committed.

`replayUntilIdle` is a finite catch-up helper, not a daemon. Its default of
three consecutive empty polls tolerates an initial empty assignment poll. An
application still owns its outer startup deadline, retry budget, and continuous
worker lifecycle.

## Bounded History Contract

`getHistory(orderId, limit)` accepts `1..100`; the one-argument JVM overload
uses 100. The service passes the limit to `QueryBuilder.limit` and does not sort
or truncate an already materialized result. Results follow the JaVers 2.0.0
consumer contract: newest-first. This intentionally changes the former
oldest-first workshop behavior, so callers that used `first()` as the initial
snapshot must migrate to `last()` or request an explicit presentation order.

For the filter-free exact-instance query, `BoundedRedissonCdoSnapshotRepository`
reads `range(-limit, -1)` from the existing snapshot list and decodes only that
range. Queries with skip, aggregate, author/date/version, commit, property, or
snapshot-type filters fall back to the upstream repository to preserve their
semantics.

An empty result can mean either an unknown order or an order with no audit
commit; determine existence from the materialized store. `CdoSnapshot` contains
domain fields, so an HTTP/API caller must enforce authorization and redaction
before exposing it.

## Order Schema

`OrderTable` stores the materialized current row. JaVers history is not modeled
as relational tables in this module.

| Column | Type | Notes |
|---|---|---|
| `id` | `varchar(64)` | Primary key and JaVers entity id |
| `customer_id` | `varchar(64)` | Customer reference used by the example aggregate |
| `status` | `varchar(16)` | `PLACED` or `PAID` lifecycle state |
| `total_amount` | `decimal(19,4)` | Decimal storage, no floating-point rounding |

## Usage

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

The service commits the JaVers snapshot before writing the current Exposed row.
If the Redis-backed audit sink fails, the exception is propagated and the
example does not accept an unaudited current-row write. This is a clear workshop
contract, not a replacement for a cross-store distributed transaction.

The Kafka projection uses Lettuce `MULTI`/`EXEC`. A failure before `EXEC` leaves
the target, head, and Kafka offset unchanged. Redis does not roll back an
individual command error inside `EXEC`; a command error or connection loss
after `EXEC` is therefore commit-unknown and may leave a partial projection.
This workshop does not promise automatic repair for that boundary. The direct
Redisson history path remains the bounded-decode query example; the Lettuce
projection path does not claim the same storage-decode bound.

Kafka and Redis transport security is caller-owned: configure TLS/SASL, ACLs,
credentials, topic authorization, snapshot redaction, and query authorization
before using this pattern outside the workshop.

`close()` is idempotent and attempts every owned resource once. If cleanup
fails, later failures are suppressed on the first exception; callers must not
assume that calling `close()` again retries a failed resource.

## Tests

```bash
./gradlew :exposed-javers-persistence-audit:test
```

The test suite covers Redis-backed history survival, bounded Redisson decoding,
Kafka projection and duplicate replay, restart rebuild, an initial empty poll,
single-partition rejection before mutation, same-group retry after a pre-EXEC
batch failure, lifecycle cleanup, newest-first ordering, and audit sink failure
propagation.

# Issue #81 Messaging/Redis Advanced

## Context

Issue #81 needed the workshop examples to actively use Bluetape4k Kafka, Lettuce, and Redisson modules, not only raw Spring Kafka, Lettuce, Redisson, or Testcontainers APIs.

## Decisions

- Use `bluetape4k-kafka4`, not `bluetape4k-kafka`, because this workshop is on Spring Kafka 4.
- Keep Kafka request/reply on Spring Kafka `ReplyingKafkaTemplate`; no verified `bluetape4k-kafka4` request/reply wrapper exists.
- Keep Redis Cluster behavior on Spring Data Redis cluster APIs, and add a scoped `bluetape4k-lettuce` executable path for low-level typed codec/coroutine usage.
- Use existing `bluetape4k-redisson` helpers in Redisson examples with low churn: `localCachedMap()` and existing `streamAddArgsOf()`.
- Treat `SuspendedJobTester.workers(N)` as concurrency only; total executions are `rounds * blockCount`.

## Outcome

- Kafka send path now uses `KafkaOperations.suspendSend()`.
- Kafka reply module declares `bluetape4k-kafka4` and documents the request/reply boundary.
- Redis cluster demo has a `Bluetape4kLettuceUsageTest` that exercises `LettuceClients`, `LettuceLongCodec`, and `awaitSuspending()`.
- Redisson examples explicitly depend on `bluetape4k-redisson` and use `localCachedMap()`.
- Distributed-lock tests use Bluetape4k `assertFailsWith`, and README/README.ko semantics now match `SuspendedJobTester`.

## Verification

- `./gradlew :messaging-kafka:compileKotlin :messaging-kafka:compileTestKotlin`
- `./gradlew :messaging-kafka-reply:compileKotlin :messaging-kafka-reply:compileTestKotlin`
- `./gradlew :redis-cluster-demo:test`
- `./gradlew :redis-distributed-lock:test`
- `./gradlew :redis-redisson-examples:test`
- `git diff --check`
- Step 2-R, Step 3-R, and Step 6-R Claude advisor gates all reached `P0=0 P1=0`.

## Future Guidance

- For Spring Kafka 4 examples, add `bluetape4k-kafka4` aliases/dependencies. `bluetape4k-kafka` is the Spring Kafka 3 line.
- Do not claim `bluetape4k-lettuce` is cluster-aware unless a `RedisClusterClient` helper is verified in the resolved artifact.
- Keep `MultithreadingTester` and `SuspendedJobTester` semantics separate in docs.
- `.codegraph/` is local generated index state and must not be committed.


# Issue #81 Messaging/Redis Advanced

## 배경

Issue #81은 workshop example이 raw Spring Kafka, Lettuce, Redisson, Testcontainers API만이 아니라 Bluetape4k Kafka, Lettuce, Redisson module을 능동적으로 사용해야 했다.

## 결정

- 이 workshop은 Spring Kafka 4 기반이므로 `bluetape4k-kafka`가 아니라 `bluetape4k-kafka4`를 사용한다.
- Kafka request/reply는 Spring Kafka `ReplyingKafkaTemplate`에 유지한다. 검증된 `bluetape4k-kafka4` request/reply wrapper는 없다.
- Redis Cluster behavior는 Spring Data Redis cluster API에 유지하고, low-level typed codec/coroutine usage를 위한 scoped `bluetape4k-lettuce` executable path를 추가한다.
- Redisson example에서는 churn을 낮게 유지하면서 기존 `bluetape4k-redisson` helper를 사용한다: `localCachedMap()`와 기존 `streamAddArgsOf()`.
- `SuspendedJobTester.workers(N)`는 concurrency로만 취급한다. total execution은 `rounds * blockCount`이다.

## 결과

- Kafka send path는 이제 `KafkaOperations.suspendSend()`를 사용한다.
- Kafka reply module은 `bluetape4k-kafka4`를 선언하고 request/reply boundary를 문서화한다.
- Redis cluster demo에는 `LettuceClients`, `LettuceLongCodec`, `awaitSuspending()`를 실행하는 `Bluetape4kLettuceUsageTest`가 있다.
- Redisson example은 명시적으로 `bluetape4k-redisson`에 의존하고 `localCachedMap()`를 사용한다.
- distributed-lock test는 Bluetape4k `assertFailsWith`를 사용하며, README/README.ko semantics는 이제 `SuspendedJobTester`와 일치한다.

## 검증

- `./gradlew :messaging-kafka:compileKotlin :messaging-kafka:compileTestKotlin`
- `./gradlew :messaging-kafka-reply:compileKotlin :messaging-kafka-reply:compileTestKotlin`
- `./gradlew :redis-cluster-demo:test`
- `./gradlew :redis-distributed-lock:test`
- `./gradlew :redis-redisson-examples:test`
- `git diff --check`
- Step 2-R, Step 3-R, Step 6-R Claude advisor gate가 모두 `P0=0 P1=0`에 도달했다.

## 향후 지침

- Spring Kafka 4 example에는 `bluetape4k-kafka4` alias/dependency를 추가한다. `bluetape4k-kafka`는 Spring Kafka 3 line이다.
- resolved artifact에서 `RedisClusterClient` helper가 검증되지 않았다면 `bluetape4k-lettuce`가 cluster-aware라고 주장하지 않는다.
- 문서에서 `MultithreadingTester`와 `SuspendedJobTester` semantics를 분리해서 유지한다.
- `.codegraph/`는 local generated index state이며 commit하면 안 된다.

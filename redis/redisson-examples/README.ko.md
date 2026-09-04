# Redisson 예제

[English](README.md) | 한국어

이 모듈은 [Redisson](https://redisson.org/) 기능을 테스트로 검증하는 catalog입니다. 하나의 공통 Redis fixture 위에서 distributed lock, semaphore, object, pub/sub, collection, Redis Stream, local cached map, read/write-through cache 예제를 다룹니다.

## 예제 카탈로그

![Redisson Examples catalog](../../docs/images/readme-diagrams/redis-redisson-examples-readme-architecture-01.png)

## 테스트 Runtime

![Redisson Examples runtime](../../docs/images/readme-diagrams/redis-redisson-examples-readme-runtime-01.png)

`AbstractRedissonTest`는 `RedisServer.Launcher.redis`로 Redis를 시작하고, `RedissonCodecs.LZ4FastForyComposite`와 `VirtualThreadExecutor`를 사용하는 `RedissonClient`를 만듭니다. 또한 raw Lettuce command로 Redis keyspace notification을 켜고, 각 예제에 재현 가능한 random name을 제공합니다.

## 숫자 `RLocalCachedMap` 원자 갱신

`RMap.addAndGetAsync`는 Redis `HINCRBYFLOAT`를 실행합니다. 따라서 숫자 값에는 Redis 숫자 payload를 바로 decode하면서 String key를 유지하는 map codec이 필요하며, 압축 `LZ4FastForyComposite` codec은 이 연산에 사용할 수 없습니다.

```kotlin
private val intCodec = CompositeCodec(
    RedissonCodecs.String,
    RedissonCodecs.Int,
    RedissonCodecs.Int,
)

val counter = localCachedMap<String, Int>("counter", redisson) {
    codec(intCodec)
}

counter.addAndGetAsync("count", 1).await() // HINCRBYFLOAT 기반 원자 갱신
```

소수 값에는 이에 맞는 `CompositeCodec(String, Double, Double)`을 사용합니다. `LocalCachedMapTest`는 독립적인 두 client를 미리 읽기 상태로 만든 뒤 Int/Double 동시 증가를 수행하고, bounded 시간 내 cross-client 무효화를 기다린 후 두 local view와 remote map을 함께 검증합니다. `awaitRedis`는 timeout 또는 호출자 취소를 대기 중인 `RFuture`에 전파합니다.

## 예제 범주

### Distributed Locks (`locks/`)

| 클래스 | 설명 |
|---|---|
| `LockExamples` | 기본 distributed lock(`RLock`) |
| `FairLockExamples` | 요청 순서를 보존하는 fair lock |
| `ReadWriteLockExamples` | read/write를 분리한 lock(`RReadWriteLock`) |
| `FencedLockExamples` | fencing token 기반 lock(split brain 방지) |
| `SpinLockExamples` | 짧은 critical section용 spin lock |
| `MultiLockExamples` | 여러 Redis node에 걸친 distributed lock |

### Distributed Semaphores (`locks/`)

| 클래스 | 설명 |
|---|---|
| `SemaphoreExamples` | distributed semaphore(`RSemaphore`) |
| `PermitExpirableSemaphoreExamples` | TTL 기반 자동 만료 semaphore |
| `CountDownLatchExamples` | Distributed `CountDownLatch` |

### Distributed Objects (`objects/`)

| 클래스 | 설명 |
|---|---|
| `BucketExamples` | `RBucket` — `AtomicReference`와 유사한 object holder |
| `AtomicLongExamples` | `RAtomicLong` — distributed atomic integer |
| `BloomFilterExamples` | `RBloomFilter` — probabilistic membership filter |
| `HyperLogLogExamples` | `RHyperLogLog` — 대규모 cardinality estimation |
| `GeoExamples` | `RGeo` — geospatial data |
| `RateLimiterExamples` | `RRateLimiter` — distributed rate limiting |
| `IdGeneratorExamples` | `RIdGenerator` — distributed ID generation |
| `BatchExamples` | 명령을 batch로 실행합니다 |

### Pub/Sub (`objects/`)

| 클래스 | 설명 |
|---|---|
| `TopicExamples` | `RTopic` channel을 통해 메시지를 발행하고 구독합니다 |
| `ReliableTopicExamples` | `RReliableTopic` — 메시지 손실 없는 reliable Pub/Sub |

### Distributed Collections (`collections/`)

| 클래스 | 설명 |
|---|---|
| `LocalCachedMapExamples` | `RLocalCachedMap` — local cache + Redis synchronization |
| `StreamExamples` | `RStream` — Redis Streams |
| `ScoredSortedSetExamples` | `RScoredSortedSet` — score 기반 sorted set |

### Read/Write Through

| 클래스 | 설명 |
|---|---|
| `ReadWriteThroughExamples` | Cache-as-SOR — cache가 DB 읽기와 쓰기를 proxy합니다 |

## 사용한 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `RedissonCodecs.LZ4FastForyComposite` | `bluetape4k-redisson` | `AbstractRedissonTest` | 휘발성/cache 예제에서 LZ4 압축 + FastFory serialization을 사용합니다 |
| `localCachedMap()` | `bluetape4k-redisson` | `LocalCachedMapExamples` | `LocalCachedMapOptions` DSL과 map 생성 호출을 결합해 Near Cache 설정 중복을 줄입니다 |
| `RedissonCodecs.Int` / `Double` + `CompositeCodec` | `bluetape4k-redisson` | `LocalCachedMapExamples`, `LocalCachedMapTest` | String map key를 유지하면서 `addAndGetAsync`가 Redis hash 숫자 payload를 처리하도록 합니다 |
| `awaitRedis` | workshop test fixture | `AbstractRedissonTest` | 비동기 Redis 대기를 제한하고 timeout/취소를 underlying future에 전파합니다 |
| `streamAddArgsOf()` | `bluetape4k-redisson` | `StreamExamples` | Kotlin 친화적인 helper로 Redis Stream append arguments를 만듭니다 |
| `VirtualThreadExecutor` | `bluetape4k-coroutines` | `AbstractRedissonTest` | Virtual Threads로 Redisson I/O를 처리합니다 |
| `RedisServer.Launcher.redis` | `bluetape4k-testcontainers` | `AbstractRedissonTest` | 자동으로 시작하고 중지하는 Testcontainers Redis singleton입니다 |
| `ShutdownQueue.register` | `bluetape4k-core` | `AbstractRedissonTest` | JVM shutdown 시 `RedissonClient`를 자동으로 닫습니다 |
| `Base58.randomString` | `bluetape4k-io` | `AbstractRedissonTest` | URL-safe random key를 생성합니다 |
| `MultithreadingTester` | `bluetape4k-junit5` | `FencedLockExamples` | 고정 thread pool로 concurrency를 검증합니다 |
| `StructuredTaskScopeTester` | `bluetape4k-junit5` | `FencedLockExamples` | Virtual Thread concurrency를 검증합니다 |
| `SuspendedJobTester` | `bluetape4k-junit5` | `FencedLockExamples` | coroutine race condition을 검증합니다 |
| `getLockId()` | `bluetape4k-redis` | `FencedLockExamples` | coroutine-safe `RFencedLock` ID를 가져옵니다 |

`FastFory`를 사용하는 클래스는 `SCHEMA_CONSISTENT`를 유지해야 하며 기본
Fory codec과 wire 호환되지 않습니다. 따라서 이 codec은 폐기 가능한 cache
데이터에만 사용하고 durable 또는 버전 간 공유 데이터에는 사용하지 않습니다.

## bluetape4k Before / After

### `RedissonCodecs.LZ4FastForyComposite`와 기본 Codec 비교

```kotlin
// Before — default JSON serialization (text-based, larger payloads)
val config = Config().apply {
    useSingleServer().setAddress(redisUrl)
    codec = JsonJacksonCodec()  // text-based serialization
}

// After — bluetape4k LZ4FastForyComposite (binary compressed serialization)
val config = Config().apply {
    useSingleServer()
        .setAddress(redis.url)
        .setConnectionPoolSize(128)
        .setConnectionMinimumIdleSize(32)
    executor = VirtualThreadExecutor          // Virtual Thread I/O
    threads = 256
    nettyThreads = 128
    codec = RedissonCodecs.LZ4FastForyComposite   // LZ4 + FastFory binary compression
}
```

### Concurrency Testing — BT Test Helpers

```kotlin
// Before — manual Thread creation (non-deterministic, hard to verify race conditions)
val threads = (1..8).map { Thread { lock.tryLock() } }
threads.forEach { it.start() }
threads.forEach { it.join() }

// After — bluetape4k MultithreadingTester (reproducible concurrency verification)
MultithreadingTester()
    .workers(8)
    .rounds(2)
    .add {
        val token = lock.tryLockAndGetTokenAsync(5, 10, TimeUnit.SECONDS).get()
        if (token != null) {
            // Critical section work
            lock.unlock()
        }
    }
    .run()
```

### `SuspendedJobTester` — Coroutine FencedLock

```kotlin
// After — verifies FencedLock concurrency in a coroutine environment
SuspendedJobTester()
    .workers(8)
    .rounds(16)
    .add {
        val mlockId = redisson.getLockId("fencedLock")  // BT coroutine-safe getLockId
        val locked = lock.tryLockAsync(5, 10, TimeUnit.SECONDS, mlockId).await()
        if (locked) {
            // Critical section
            lock.unlockAsync(mlockId).await()
        }
    }
    .run()
```

## 실행

```bash
# Automatically starts the Redis container, then runs tests
./gradlew :redis-redisson-examples:test

# Run only a specific example
./gradlew :redis-redisson-examples:test --tests "*.FencedLockExamples"
```

## 참고 자료

- [Redisson official docs](https://redisson.org/docs/)
- [Redisson GitHub](https://github.com/redisson/redisson)
- [bluetape4k-redis](https://github.com/bluetape4k/bluetape4k-projects)
- [bluetape4k-redisson](https://github.com/bluetape4k/bluetape4k-projects)
- Spring Data Redis 기반 예제는 [`spring-data/redis-examples`](../../spring-data/redis-examples)를 참고하세요

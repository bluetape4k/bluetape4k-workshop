# cache-benchmark

[한국어](README.ko.md) | English

This module benchmarks cache strategy contracts behind one `ProductCacheService`
shape. Issue #585 corrected the read/write-through and write-behind ownership
contracts so Redisson's loader/writer paths, rather than application-managed
repository/cache writes, are measured. The results below were regenerated from
the corrected implementation.

- `kotlinx-benchmark` provides JMH-style warmups and iterations.
- H2 keeps the persistence layer local and repeatable.
- Redis is started through bluetape4k Testcontainers for Redis-backed profiles.
- Each benchmark Spring context gets a unique H2 URL and Redis cache namespace.

## Architecture

![cache-benchmark architecture](../../docs/images/readme-diagrams/spring-boot-cache-benchmark-readme-architecture-01.png)

Each benchmark profile starts its own Spring context with:

- a fresh H2 database URL;
- Redis connection properties from `RedisServer.Launcher.redis`;
- a unique `cache.benchmark.namespace` so stale Redis state does not leak across
  profiles or runs.

## Strategy contracts

| # | Profile | Actual strategy | DB ownership |
|---|---|---|---|
| 1 | **No Cache** | Direct repository access | Service |
| 2 | **Caffeine** | Spring `@Cacheable` local cache | Service method invoked on miss/write |
| 3 | **Redis Cache** | Spring Cache Redis remote cache | Service method invoked on miss/write |
| 4 | **Near Cache** | Redisson `RLocalCachedMap` used as a two-tier cache | Service loads/writes repository explicitly |
| 5 | **Read-Through** | Redisson `RMap` with `MapLoader` | `ProductMapLoader` loads DB misses |
| 6 | **Write-Through** | Redisson `RMap` with `MapLoader` + `MapWriter` in `WRITE_THROUGH` mode | `ProductMapWriter` persists before `save` returns |
| 7 | **Write-Behind** | Redisson `RMap` with `MapLoader` + `MapWriter` in `WRITE_BEHIND` mode | Redisson queues and batches `ProductMapWriter` persistence |

The canonical Redisson profiles use existing products with stable IDs. Generated
ID inserts are intentionally not part of write-through/write-behind benchmark
operations because the map key is the write contract.

## Benchmark results

Measured on 2026-07-27 with Apple M4 Pro, Java 21.0.12, Gradle 9.6.0,
Kotlin Gradle plugin 2.4.0, Spring Boot 4.1.0, Docker 29.2.1, and `redis:8`.
JMH used
2 × 1-second warmup iterations and 5 × 1-second measurement iterations.
All values are operations per second; higher is better only when the operation
and completion contract are the same.

For benchmarks parameterized with `productId=1,100,500`, the table reports the
arithmetic mean of the three JMH scores. The complete 23 measurements, errors,
and parameters are preserved in the
[raw JMH JSON](benchmark-results/2026-07-27-all-profiles.json), with the
[derived summary](benchmark-results/2026-07-27-summary.json) and
[run notes](benchmark-results/2026-07-27.md).

### Read operations

| Operation | Throughput (ops/s) | Aggregation |
|---|---:|---|
| Near Cache warmed hit | 3,287,505.143 | Mean of IDs 1, 100, 500 |
| Caffeine warmed hit | 3,058,547.710 | Mean of IDs 1, 100, 500 |
| No Cache repository read | 320,764.485 | Mean of IDs 1, 100, 500 |
| Write-Behind warmed hit | 4,194.350 | Single benchmark score |
| Write-Through warmed hit | 4,099.116 | Single benchmark score |
| Read-Through warmed hit | 4,090.132 | Mean of IDs 1, 100, 500 |
| Redis Cache warmed hit | 4,030.965 | Mean of IDs 1, 100, 500 |
| Read-Through forced miss | 718.246 | Mean of IDs 1, 100, 500 |

![cache read throughput](../../docs/images/readme-charts/cache-benchmark-read-throughput-chart-01.png)

### Write operations

| Operation | Completion boundary | Throughput (ops/s) |
|---|---|---:|
| Write-Behind existing-ID update | Cache accepted; DB write remains queued | 3,551.936 |
| Write-Through existing-ID update | Database persistence completed before return | 3,034.323 |
| Write-Behind update and wait for drain | Database value observed after queued write | 0.988 |

![cache write throughput](../../docs/images/readme-charts/cache-benchmark-write-throughput-chart-01.png)

The write-behind rows intentionally expose two different completion boundaries.
The enqueue score must not be presented as completed database throughput, and
the drain score includes the configured one-second write-behind delay. These
local single-host measurements explain this example's behavior; they are not
production capacity claims.

## Write-behind operational contract

The write-behind profile uses Redisson's delayed/batched writer queue. It is not
a crash-durable outbox.

| Setting | Value |
|---|---:|
| Retry count | `3` |
| Retry interval | `1s` |
| Batch size | `50` |
| Delay | `1s` |

Operational caveats:

- `save` returning from `WriteBehindService` means the cache accepted the update;
  it does not mean the database already reflects it.
- Tests and completed-persistence benchmarks must wait until the repository
  reflects the queued write.
- Production-style use would need queue depth, failed-write, retry, and drain
  observability.
- Process termination before Redisson drains queued writes can lose updates.
- Use a transactional outbox or Redis Stream design when crash-durable write
  recovery is required; this example does not imply that architecture.

## Running Benchmarks

Benchmarks are not part of the default build (`./gradlew test`). Run them
explicitly:

```bash
# Profile 1 — baseline no-cache
./gradlew :spring-boot-cache-benchmark:noCacheBenchmark

# Profile 2 — Caffeine
./gradlew :spring-boot-cache-benchmark:caffeineBenchmark

# Profile 3 — Redis
./gradlew :spring-boot-cache-benchmark:redisBenchmark

# Profile 4 — Near Cache
./gradlew :spring-boot-cache-benchmark:nearCacheBenchmark

# Profile 5 — Read-Through
./gradlew :spring-boot-cache-benchmark:readThroughBenchmark

# Profile 6 — Write-Through
./gradlew :spring-boot-cache-benchmark:writeThroughBenchmark

# Profile 7 — Write-Behind
./gradlew :spring-boot-cache-benchmark:writeBehindBenchmark

# All profiles
./gradlew :spring-boot-cache-benchmark:allProfilesBenchmark
```

## Running Tests

```bash
./gradlew :spring-boot-cache-benchmark:test
```

Tests verify the corrected ownership contracts and functional behavior.
Redis-backed tests start Redis automatically through Testcontainers.

## Configuration

| Property | Default | Description |
|---|---|---|
| `cache.benchmark.namespace` | `cache-benchmark` | Redis namespace prefix for benchmark/test isolation |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `spring.datasource.url` | H2 in-memory | Database URL |

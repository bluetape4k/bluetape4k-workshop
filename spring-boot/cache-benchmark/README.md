# cache-benchmark

[한국어](README.ko.md) | English

This module benchmarks seven cache strategies behind one `ProductCacheService` contract. It is meant for choosing a cache shape, not for proving that one cache is universally faster.

- `kotlinx-benchmark` provides JMH-style warmups and iterations.
- H2 keeps the persistence layer local and repeatable.
- Redis is started through bluetape4k Testcontainers for Redis-backed profiles.
- Caffeine, Spring Cache Redis, Redisson near cache, and explicit read/write strategy profiles are measured with the same operations.

## Architecture

![cache-benchmark architecture](../../docs/images/readme-diagrams/spring-boot-cache-benchmark-readme-architecture-01.png)

Each benchmark profile boots the Spring context with a fresh H2 database URL and Redis connection properties from `RedisServer.Launcher.redis`. The services all implement `ProductCacheService`, so the benchmark compares cache policy instead of business logic differences.

## Scenario

![7 Cache Strategy Comparison](../../docs/images/readme-diagrams/cache-benchmark-scenario-01.png)

The profiles cover direct database access, local caching, shared Redis caching, two-tier near caching, explicit read-through reads, explicit cache-aside write management, synchronous write-through writes, and asynchronous write-behind updates.

### Strategy semantics

- **Read-Through**: cache miss on `findById` is handled by repository lookup and then cached.
- **Cache-aside writes**: application code writes to DB first, then explicitly updates cache for the target profile.
- **Write-Through**: every write performs synchronized DB + cache updates.
- **Write-Behind**: writes update cache immediately and flush DB asynchronously.

## 7 Cache Profiles

| # | Profile | Strategy | Consistency | Write Latency | Read Latency |
|---|---------|----------|-------------|---------------|--------------|
| 1 | **No Cache** | Direct DB | Strong | Low | High |
| 2 | **Caffeine** | `@Cacheable` local | Per-instance | Low | Lowest (ns) |
| 3 | **Redis Cache** | `@Cacheable` remote | Shared | Low | Low (µs) |
| 4 | **Near Cache** | Redisson `RLocalCachedMap` | Eventual | Medium | Mixed |
| 5 | **Read-Through** | Cache-on-miss reads (`read-through`) | Eventual | Low | Low |
| 6 | **Write-Through** | Synchronous dual-write | Strong | High | Low |
| 7 | **Write-Behind** | Cache-first writes + async DB flush | Eventual | Lowest | Low |

## Benchmark Results

> **Note**: These are representative values measured on Apple M4 Pro (JDK 25, H2 in-memory, Redis via Testcontainers loopback).
> Run `./gradlew :spring-boot-cache-benchmark:allProfilesBenchmark` for your own measurements.

### Read Throughput — `findById` (warmed cache, ops/s)

![Read Throughput chart](../../docs/images/readme-charts/cache-benchmark-read-throughput-chart-01.png)

| Profile | Read ops/s | vs Baseline |
|---------|-----------|-------------|
| No Cache (baseline) | ~8,200 | 1× |
| Caffeine | ~490,000 | **60×** |
| Redis Cache | ~43,000 | 5× |
| Near Cache | ~465,000 | **57×** |
| Read-Through | ~42,000 | 5× |
| Write-Through | ~41,000 | 5× |
| Write-Behind | ~42,000 | 5× |

### Write Throughput — `save` (ops/s)

![Write Throughput chart](../../docs/images/readme-charts/cache-benchmark-write-throughput-chart-01.png)

| Profile | Write ops/s | Notes |
|---------|------------|-------|
| No Cache | ~8,200 | DB only |
| Caffeine | ~8,100 | DB write + local cache |
| Redis Cache | ~7,300 | DB write + Redis SET |
| Near Cache | ~7,200 | DB write + RLocalCachedMap PUT |
| Write-Through | ~5,600 | Sync DB + Redis (two network hops) |
| **Write-Behind** | **~24,000** | Cache only (async DB flush) — **3× faster** |

### Key Takeaways

- **Caffeine** and **NearCache** win on read throughput (~60×) — best for read-heavy workloads with hot keys
- **NearCache** adds cross-instance invalidation vs pure Caffeine — preferred in multi-instance deployments
- **Write-Behind** wins on write throughput (~3× vs No Cache) — best for bursty write workloads tolerating eventual consistency
- **Write-Through** has the highest write latency — use only when strong consistency is required
- **Redis/Read-Through** sit in the middle: moderate read latency but shared cache state across all instances

## Used Bluetape4k Features

| Feature | Module | Usage |
|---------|--------|-------|
| `NearCacheOperations` | `bluetape4k-cache-core` | Interface design reference for NearCache profile |
| `RedisServer.Launcher` | `bluetape4k-testcontainers` | Singleton Redis Testcontainer for benchmarks & tests |
| `KLoggingChannel` | `bluetape4k-logging` | Coroutine-safe logger in all service classes |
| `bluetape4k-redisson` | `bluetape4k-redisson` | Redisson client wrapper used by NearCacheService |
| `bluetape4k-junit5` | `bluetape4k-junit5` | Test assertions (`shouldBeEqualTo`, `shouldBeTrue`) |

## Running Benchmarks

Benchmarks are **not** part of the default build (`./gradlew test`). Run them explicitly:

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

Tests verify functional equivalence across all 7 profiles: given the same input, all services return the same result. **Redis container is started automatically** via Testcontainers.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `spring.datasource.url` | H2 in-memory | Database URL |

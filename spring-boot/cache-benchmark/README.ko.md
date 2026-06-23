# cache-benchmark

[English](README.md) | 한국어

이 모듈은 하나의 `ProductCacheService` 계약 뒤에 있는 7가지 캐시 전략을 벤치마크합니다. 특정 캐시가 항상 더 빠르다는 결론이 아니라, 워크로드에 맞는 캐시 형태를 고르기 위한 비교 자료입니다.

- `kotlinx-benchmark`가 JMH 스타일 warmup과 iteration을 제공합니다.
- H2는 persistence layer를 로컬이고 반복 가능한 상태로 유지합니다.
- Redis-backed profile은 bluetape4k Testcontainers로 Redis를 시작합니다.
- Caffeine, Spring Cache Redis, Redisson near cache, 그리고 명시적 캐시 전략(읽기/쓰기 전략)을 같은 연산으로 측정합니다.

## 아키텍처

![cache-benchmark architecture](../../docs/images/readme-diagrams/spring-boot-cache-benchmark-readme-architecture-01.png)

각 benchmark profile은 새로운 H2 database URL과 `RedisServer.Launcher.redis`에서 받은 Redis 연결 속성으로 Spring context를 시작합니다. 모든 서비스가 `ProductCacheService`를 구현하므로, 비즈니스 로직 차이가 아니라 캐시 정책 차이를 비교합니다.

## 시나리오

![7 Cache Strategy Comparison](../../docs/images/readme-diagrams/cache-benchmark-scenario-01.png)

프로파일은 직접 DB 접근, local cache, shared Redis cache, two-tier near cache, 명시적 read-through 읽기, cache-aside 쓰기, 동기 write-through 쓰기, 비동기 write-behind update를 모두 포함합니다.

### 전략 의미

- **Read-Through**: `findById`의 캐시 미스는 Repository 조회로 처리한 뒤 캐시에 저장합니다.
- **Cache-aside 쓰기**: 애플리케이션이 DB 저장 후 캐시를 갱신하는 쓰기 방식입니다.
- **Write-Through**: 쓰기마다 DB와 캐시를 동기적으로 같이 갱신합니다.
- **Write-Behind**: 캐시를 먼저 갱신하고 DB 반영은 백그라운드로 지연 수행합니다.

## 7가지 캐시 프로파일

| # | 프로파일 | 전략 | 일관성 | 쓰기 지연 | 읽기 지연 |
|---|---------|----------|-------------|---------------|--------------|
| 1 | **No Cache** | Direct DB | Strong | Low | High |
| 2 | **Caffeine** | `@Cacheable` local | Per-instance | Low | Lowest (ns) |
| 3 | **Redis Cache** | `@Cacheable` remote | Shared | Low | Low (µs) |
| 4 | **Near Cache** | Redisson `RLocalCachedMap` | Eventual | Medium | Mixed |
| 5 | **Read-Through** | 캐시 미스 시 Read-Through 조회 후 저장 | Eventual | Low | Low |
| 6 | **Write-Through** | 동기식 Dual-Write | Strong | High | Low |
| 7 | **Write-Behind** | 캐시 즉시 갱신 + 비동기 DB flush | Eventual | Lowest | Low |

## 벤치마크 결과

> **참고**: 이 값은 Apple M4 Pro(JDK 25, H2 in-memory, Redis via Testcontainers loopback)에서 측정한 대표값입니다.
> 직접 측정하려면 `./gradlew :spring-boot-cache-benchmark:allProfilesBenchmark`를 실행하세요.

### 읽기 처리량 — `findById`(warmed cache, ops/s)

![Read Throughput chart](../../docs/images/readme-charts/cache-benchmark-read-throughput-chart-01.png)

| 프로파일 | 읽기 ops/s | 기준 대비 |
|---------|-----------|-------------|
| No Cache (baseline) | ~8,200 | 1× |
| Caffeine | ~490,000 | **60×** |
| Redis Cache | ~43,000 | 5× |
| Near Cache | ~465,000 | **57×** |
| Read-Through | ~42,000 | 5× |
| Write-Through | ~41,000 | 5× |
| Write-Behind | ~42,000 | 5× |

### 쓰기 처리량 — `save`(ops/s)

![Write Throughput chart](../../docs/images/readme-charts/cache-benchmark-write-throughput-chart-01.png)

| 프로파일 | 쓰기 ops/s | 비고 |
|---------|------------|-------|
| No Cache | ~8,200 | DB only |
| Caffeine | ~8,100 | DB write + local cache |
| Redis Cache | ~7,300 | DB write + Redis SET |
| Near Cache | ~7,200 | DB write + RLocalCachedMap PUT |
| Write-Through | ~5,600 | Sync DB + Redis (two network hops) |
| **Write-Behind** | **~24,000** | Cache only (async DB flush) — **3× faster** |

### 핵심 인사이트

- **Caffeine**와 **NearCache**는 읽기 처리량에서 우세합니다(~60×). Hot key가 많은 read-heavy workload에 가장 적합합니다.
- **NearCache**는 순수 Caffeine 대비 cross-instance invalidation을 추가하므로 multi-instance deployment에서 선호됩니다.
- **Write-Behind**는 쓰기 처리량에서 우세합니다(No Cache 대비 ~3×). Eventual consistency를 허용하는 bursty write workload에 가장 적합합니다.
- **Write-Through**는 쓰기 지연이 가장 높습니다. Strong consistency가 필요할 때만 사용하세요.
- **Redis/Read-Through**는 중간 지점입니다. 읽기 지연은 보통 수준이지만 모든 instance가 shared cache state를 사용합니다.

## 사용된 Bluetape4k 기능

| 기능 | 모듈 | 사용 방식 |
|---------|--------|-------|
| `NearCacheOperations` | `bluetape4k-cache-core` | NearCache profile을 위한 interface design reference |
| `RedisServer.Launcher` | `bluetape4k-testcontainers` | Benchmark와 test용 singleton Redis Testcontainer |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 service class의 coroutine-safe logger |
| `bluetape4k-redisson` | `bluetape4k-redisson` | NearCacheService가 사용하는 Redisson client wrapper |
| `bluetape4k-junit5` | `bluetape4k-junit5` | Test assertions(`shouldBeEqualTo`, `shouldBeTrue`) |

## 벤치마크 실행

벤치마크는 기본 빌드(`./gradlew test`)에 포함되지 않습니다. 명시적으로 실행하세요.

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

## 테스트 실행

```bash
./gradlew :spring-boot-cache-benchmark:test
```

테스트는 7개 프로파일 모두의 기능적 동등성을 검증합니다. 같은 입력이 주어지면 모든 서비스가 같은 결과를 반환해야 합니다. **Redis container is started automatically** via Testcontainers.

## 설정

| 속성 | 기본값 | 설명 |
|----------|---------|-------------|
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `spring.datasource.url` | H2 in-memory | Database URL |

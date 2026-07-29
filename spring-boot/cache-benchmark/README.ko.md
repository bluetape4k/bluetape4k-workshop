# cache-benchmark

[English](README.md) | 한국어

이 모듈은 하나의 `ProductCacheService` 형태 뒤에 있는 캐시 전략 계약을
벤치마크합니다. Issue #585에서 read/write-through와 write-behind의 소유권
계약을 바로잡아 애플리케이션이 직접 repository/cache에 쓰는 경로가 아니라
Redisson loader/writer 경로를 측정합니다. 아래 결과는 수정된 구현으로 다시
측정했습니다.

- `kotlinx-benchmark`가 JMH 스타일 warmup과 iteration을 제공합니다.
- H2는 persistence layer를 로컬이고 반복 가능한 상태로 유지합니다.
- Redis-backed profile은 bluetape4k Testcontainers로 Redis를 시작합니다.
- 각 benchmark Spring context는 고유한 H2 URL과 Redis cache namespace를
  사용합니다.

## 아키텍처

![cache-benchmark architecture](../../docs/images/readme-diagrams/spring-boot-cache-benchmark-readme-architecture-01.png)

각 benchmark profile은 다음 조건으로 자체 Spring context를 시작합니다.

- 새로운 H2 database URL;
- `RedisServer.Launcher.redis`에서 받은 Redis 연결 속성;
- profile이나 실행 사이에 오래된 Redis state가 섞이지 않도록 고유한
  `cache.benchmark.namespace`.

## 전략 계약

| # | Profile | 실제 전략 | DB 소유권 |
|---|---|---|---|
| 1 | **No Cache** | 직접 repository 접근 | Service |
| 2 | **Caffeine** | Spring `@Cacheable` local cache | miss/write 때 service method 실행 |
| 3 | **Redis Cache** | Spring Cache Redis remote cache | miss/write 때 service method 실행 |
| 4 | **Near Cache** | Redisson `RLocalCachedMap` 기반 two-tier cache | Service가 repository를 명시적으로 load/write |
| 5 | **Read-Through** | `MapLoader`를 붙인 Redisson `RMap` | `ProductMapLoader`가 cache miss DB load 수행 |
| 6 | **Write-Through** | `MapLoader` + `MapWriter`, `WRITE_THROUGH` mode의 Redisson `RMap` | `save`가 반환되기 전에 `ProductMapWriter`가 DB 반영 |
| 7 | **Write-Behind** | `MapLoader` + `MapWriter`, `WRITE_BEHIND` mode의 Redisson `RMap` | Redisson이 `ProductMapWriter` DB 반영을 지연·배치 처리 |

canonical Redisson profile은 안정적인 ID가 있는 기존 상품을 사용합니다.
Generated ID insert는 write-through/write-behind benchmark 연산에 포함하지
않습니다. 이 전략에서는 map key가 쓰기 계약이기 때문입니다.

## 벤치마크 결과

2026-07-27에 Apple M4 Pro, Java 21.0.12, Gradle 9.6.0,
Kotlin Gradle plugin 2.4.0, Spring Boot 4.1.0, Docker 29.2.1, `redis:8`
환경에서 측정했습니다. JMH는
1초 warmup 2회와 1초 measurement 5회를 사용했습니다. 모든 값의 단위는
초당 연산 수(ops/s)입니다. 연산과 완료 계약이 같을 때만 높은 값이 더
좋다는 의미가 있습니다.

`productId=1,100,500` parameter를 사용하는 benchmark는 세 JMH score의
산술 평균을 표에 기록했습니다. 23개 전체 측정값, 오차, parameter는
[JMH 원본 JSON](benchmark-results/2026-07-27-all-profiles.json)에 보존했고,
[파생 summary](benchmark-results/2026-07-27-summary.json)와
[실행 기록](benchmark-results/2026-07-27.md)도 함께 제공합니다.

### 읽기 연산

| 연산 | 처리량 (ops/s) | 집계 방식 |
|---|---:|---|
| Near Cache warmed hit | 3,287,505.143 | ID 1, 100, 500 평균 |
| Caffeine warmed hit | 3,058,547.710 | ID 1, 100, 500 평균 |
| No Cache repository read | 320,764.485 | ID 1, 100, 500 평균 |
| Write-Behind warmed hit | 4,194.350 | 단일 benchmark score |
| Write-Through warmed hit | 4,099.116 | 단일 benchmark score |
| Read-Through warmed hit | 4,090.132 | ID 1, 100, 500 평균 |
| Redis Cache warmed hit | 4,030.965 | ID 1, 100, 500 평균 |
| Read-Through forced miss | 718.246 | ID 1, 100, 500 평균 |

![cache read throughput](../../docs/images/readme-charts/cache-benchmark-read-throughput-chart-01.png)

### 쓰기 연산

| 연산 | 완료 경계 | 처리량 (ops/s) |
|---|---|---:|
| Write-Behind existing-ID update | Cache 수락 완료, DB 쓰기는 queue 대기 | 3,551.936 |
| Write-Through existing-ID update | 반환 전에 database 반영 완료 | 3,034.323 |
| Write-Behind update and wait for drain | Queue 쓰기 후 database 값 관측 | 0.988 |

![cache write throughput](../../docs/images/readme-charts/cache-benchmark-write-throughput-chart-01.png)

write-behind 두 행은 의도적으로 서로 다른 완료 경계를 보여 줍니다. enqueue
score를 완료된 database 처리량으로 표현하면 안 됩니다. drain score에는
설정된 1초 write-behind delay가 포함됩니다. 이 단일 호스트 로컬 측정은
예제 동작을 설명하기 위한 것이며 production capacity를 의미하지 않습니다.

## Write-behind 운영 계약

write-behind profile은 Redisson의 delayed/batched writer queue를 사용합니다.
이 큐는 crash-durable outbox가 아닙니다.

| 설정 | 값 |
|---|---:|
| Retry count | `3` |
| Retry interval | `1s` |
| Batch size | `50` |
| Delay | `1s` |

운영 caveat는 다음과 같습니다.

- `WriteBehindService.save`가 반환됐다는 것은 cache가 update를 받아들였다는
  뜻입니다. database 반영 완료를 뜻하지 않습니다.
- 테스트와 completed-persistence benchmark는 repository가 queued write를
  반영할 때까지 기다려야 합니다.
- production-style 사용에는 queue depth, failed write, retry, drain 관측이
  필요합니다.
- process가 queued write를 drain하기 전에 종료되면 update가 유실될 수
  있습니다.
- crash-durable write recovery가 필요하면 transactional outbox나 Redis Stream
  설계를 사용해야 합니다. 이 예제는 그런 아키텍처를 암시하지 않습니다.

## 벤치마크 실행

벤치마크는 기본 빌드(`./gradlew test`)에 포함되지 않습니다. 명시적으로
실행하세요.

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

테스트는 수정된 ownership contract와 기능 동작을 검증합니다.
Redis-backed test는 Testcontainers로 Redis를 자동 시작합니다.

## 설정

| 속성 | 기본값 | 설명 |
|---|---|---|
| `cache.benchmark.namespace` | `cache-benchmark` | benchmark/test 격리를 위한 Redis namespace prefix |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `spring.datasource.url` | H2 in-memory | Database URL |

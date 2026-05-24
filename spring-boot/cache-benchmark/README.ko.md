# cache-benchmark

Spring Boot 서비스에서 7가지 캐시 전략을 비교하는 성능 벤치마크입니다. H2 인메모리 DB와 Redis를 사용합니다.

**kotlinx-benchmark** (JMH 기반)을 사용하여 실제 JVM steady-state 처리량을 측정합니다.

## 7가지 캐시 프로파일

| # | 프로파일 | 전략 | 일관성 | 쓰기 지연 | 읽기 지연 |
|---|---------|------|--------|-----------|-----------|
| 1 | **No Cache** | DB 직접 접근 | 강함 | 낮음 | 높음 |
| 2 | **Caffeine** | `@Cacheable` 로컬 | 인스턴스별 | 낮음 | 최저 (ns) |
| 3 | **Redis Cache** | `@Cacheable` 원격 | 공유 | 낮음 | 낮음 (µs) |
| 4 | **Near Cache** | Redisson `RLocalCachedMap` | 최종 | 중간 | 혼합 |
| 5 | **Read-Through** | 수동 Redis RT | 최종 | 낮음 | 낮음 |
| 6 | **Write-Through** | 동기 Redis + DB | 강함 | 높음 | 낮음 |
| 7 | **Write-Behind** | 비동기 DB 플러시 | 최종 | 최저 | 낮음 |

## 사용된 Bluetape4k 기능

| 기능 | 모듈 | 사용처 |
|------|------|--------|
| `NearCacheOperations` | `bluetape4k-cache-core` | NearCache 인터페이스 설계 참조 |
| `RedisServer.Launcher` | `bluetape4k-testcontainers` | 싱글턴 Redis Testcontainer (벤치마크/테스트) |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 서비스 클래스의 코루틴 안전 로거 |
| `bluetape4k-redisson` | `bluetape4k-redisson` | NearCacheService용 Redisson 클라이언트 |
| `bluetape4k-junit5` | `bluetape4k-junit5` | 테스트 assertion |

## 벤치마크 실행

```bash
# 특정 프로파일
./gradlew :spring-boot-cache-benchmark:noCacheBenchmark
./gradlew :spring-boot-cache-benchmark:caffeineBenchmark

# 전체 프로파일
./gradlew :spring-boot-cache-benchmark:allProfilesBenchmark
```

## 테스트 실행

```bash
./gradlew :spring-boot-cache-benchmark:test
```

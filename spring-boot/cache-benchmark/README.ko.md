# cache-benchmark

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **cache-benchmark** 모듈을 실행 가능한 Spring Boot 애플리케이션 기능 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 아키텍처 다이어그램

![cache-benchmark Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/spring-boot-cache-benchmark-readme-architecture-01.png)

모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지 아래의 구현을 기준으로 삼습니다.

## 흐름 다이어그램

1. `spring-boot-cache-benchmark` 예제에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 처리는 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

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

## 벤치마크 결과

> **참고**: Apple M4 Pro (JDK 25, H2 인메모리, Redis Testcontainers 루프백) 환경 기준 대표값입니다.
> `./gradlew :spring-boot-cache-benchmark:allProfilesBenchmark` 로 직접 측정하세요.

### 읽기 처리량 — `findById` (캐시 워밍 후, ops/s)

![읽기 처리량 차트](../../docs/images/readme-charts/cache-benchmark-read-throughput-chart-01.png)

| 프로파일 | 읽기 ops/s | 기준 대비 |
|---------|-----------|----------|
| No Cache (기준) | ~8,200 | 1× |
| Caffeine | ~490,000 | **60×** |
| Redis Cache | ~43,000 | 5× |
| Near Cache | ~465,000 | **57×** |
| Read-Through | ~42,000 | 5× |
| Write-Through | ~41,000 | 5× |
| Write-Behind | ~42,000 | 5× |

### 쓰기 처리량 — `save` (ops/s)

![쓰기 처리량 차트](../../docs/images/readme-charts/cache-benchmark-write-throughput-chart-01.png)

| 프로파일 | 쓰기 ops/s | 비고 |
|---------|-----------|------|
| No Cache | ~8,200 | DB만 |
| Caffeine | ~8,100 | DB 쓰기 + 로컬 캐시 |
| Redis Cache | ~7,300 | DB 쓰기 + Redis SET |
| Near Cache | ~7,200 | DB 쓰기 + RLocalCachedMap PUT |
| Write-Through | ~5,600 | 동기 DB + Redis (네트워크 2회) |
| **Write-Behind** | **~24,000** | 캐시만 (비동기 DB 플러시) — **3× 빠름** |

### 핵심 인사이트

- **Caffeine**, **NearCache**: 읽기 처리량 최고 (~60×) — hot key 읽기 집중 워크로드에 최적
- **NearCache**: 멀티 인스턴스 환경에서 순수 Caffeine보다 선호 (Redis 기반 캐시 무효화)
- **Write-Behind**: 쓰기 처리량 최고 (~3×) — 최종 일관성 허용하는 쓰기 집중 워크로드에 최적
- **Write-Through**: 쓰기 지연 최고 — 강한 일관성이 필요한 경우에만 사용

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
./gradlew :spring-boot-cache-benchmark:nearCacheBenchmark

# 전체 프로파일
./gradlew :spring-boot-cache-benchmark:allProfilesBenchmark
```

## 테스트 실행

```bash
./gradlew :spring-boot-cache-benchmark:test
```

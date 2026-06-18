# Spring Boot Cache Resilience

[English](README.md) | 한국어

이 모듈은 장애를 견디는 cache read path를 보여 줍니다.

- Redis가 primary cache입니다.
- Caffeine이 local fallback cache입니다.
- Resilience4j CircuitBreaker가 Redis read를 보호합니다.
- Toxiproxy가 integration test에서 실제 Redis network failure를 주입합니다.

## 아키텍처

![Spring Boot Cache Resilience architecture](../../docs/images/readme-diagrams/spring-boot-cache-resilience-readme-architecture-01.png)

`ResilientProductService`는 `SuspendDecorators`와 `redis-cache` CircuitBreaker로 Redis read를 감쌉니다. Write는 Caffeine을 먼저 갱신한 뒤 Redis를 시도하므로, Redis가 불안정해도 process-local fallback 값을 유지합니다.

## CircuitBreaker 흐름

![Spring Boot Cache Resilience state flow](../../docs/images/readme-diagrams/spring-boot-cache-resilience-readme-state-flow-01.png)

Integration test는 전체 상태 머신을 직접 구동합니다. Redis가 정상일 때는 breaker가 `CLOSED`이고, timeout 주입 후 `OPEN`이 되며, 서비스는 Caffeine으로 fallback합니다. 대기 시간이 지난 뒤 probe가 성공하면 breaker가 다시 닫힙니다.

## 이 모듈에서 확인할 내용

- `ResilientProductService`: `SuspendDecorators.ofSupplier { }.withCircuitBreaker(cb).withFallback { ... }`로 Redis 읽기를 래핑.
- CB 전체 상태 머신: CLOSED → OPEN (Redis 장애) → HALF-OPEN (프로브) → CLOSED (복구).
- `bluetape4k-testcontainers`의 `ToxiproxyServer`: `timeout(1ms)` toxic으로 Redis 연결을 빠르게 차단 주입.
- OPEN 상태에서 폴백 저장소로 사용하는 Caffeine 로컬 캐시.
- 복구 테스트: toxic 제거 → CB가 CLOSED로 전환 → Redis 재개.

## 실행

```bash
./gradlew :spring-boot-cache-resilience:bootRun
```

실행 후 다음 주소를 확인합니다.

- Swagger UI: `http://localhost:8090/swagger-ui.html`
- Actuator health (CB 상태 포함): `http://localhost:8090/actuator/health`
- CB 이벤트: `http://localhost:8090/actuator/circuitbreakerevents`

## 테스트 실행

```bash
./gradlew :spring-boot-cache-resilience:test
```

테스트는 Docker 컨테이너(Redis + Toxiproxy)를 시작하고, 장애를 주입하며, 모킹 없이 CB 상태 머신 전체를 검증합니다.

## 사용된 Bluetape4k 기능

| 모듈 | 기능 | 사용 위치 |
|------|------|---------|
| `bluetape4k-logging` | `KLoggingChannel()` | 서비스와 테스트의 코루틴 안전 구조화 로깅 |
| `bluetape4k-resilience4j` | `SuspendDecorators` | suspend 함수를 위한 프로그래매틱 CB + 폴백 체인 |
| `bluetape4k-testcontainers` | `ToxiproxyServer`, `RedisServer` | 통합 테스트에서 실제 네트워크 장애 주입 |
| `bluetape4k-junit5` | `runSuspendIO { }` | suspend 기반 통합 테스트 실행기 |

## 소스 맵

- `CacheResilienceApplication.kt`는 Spring Boot 애플리케이션을 시작합니다.
- `config/ResilientCacheConfig.kt`는 Lettuce, Caffeine 캐시, CircuitBreaker 빈을 설정합니다.
- `service/ResilientProductService.kt`는 `SuspendDecorators`를 사용한 Redis → CB → Caffeine 폴백 패턴을 구현합니다.
- `application.yml`은 `8090` 포트, actuator CB health, Resilience4j 인스턴스 설정을 구성합니다.
- `ResilientCacheServiceTest.kt`는 `ToxiproxyServer` + `RedisServer`로 CLOSED→OPEN→HALF-OPEN→CLOSED 전환을 검증합니다.

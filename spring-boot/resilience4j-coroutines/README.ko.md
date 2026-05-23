# Spring Boot 4 + Resilience4j + Coroutines 워크샵

원본: [resilience4j-spring-boot3-demo](https://github.com/resilience4j/resilience4j-spring-boot3-demo)

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│  HTTP 클라이언트 (WebTestClient / curl)                          │
└──────────────────────┬──────────────────────────────────────────┘
                       │
          ┌────────────▼────────────┐
          │    REST 컨트롤러         │
          │  BackendAController     │  ← 동기 / Mono / Flux / Future
          │  BackendACoController   │  ← suspend + Flow
          │  BackendBController     │  ← 동기 (RateLimiter)
          │  BackendBCoController   │  ← suspend (Bulkhead + Retry)
          └────────────┬────────────┘
                       │ Resilience4j AOP 프록시
          ┌────────────▼────────────┐
          │       서비스             │
          │  BackendAService        │  ← Mono / Flux / Future
          │  BackendACoService      │  ← suspend + Flow
          │  BackendBService        │  ← 동기
          │  BackendBCoService      │  ← suspend
          └─────────────────────────┘
```

### Circuit Breaker 상태 전이

![Circuit Breaker 다이어그램](../../docs/images/readme-diagrams/spring-boot-resilience4j-coroutines-diagram-01.png)

```
  ┌────────┐  실패율 > 임계값  ┌────────┐
  │ CLOSED │ ───────────────▶│  OPEN  │
  │        │◀───────────────│        │
  └────────┘  모든 프로브 성공  └───┬────┘
                                   │ 대기 시간
                              ┌────▼────┐
                              │HALF-OPEN│
                              │ (프로브) │
                              └─────────┘
```

## 개요

Resilience4j 2.4.0을 Spring Boot 4 환경에서 Kotlin 코루틴과 함께 사용하는 예제입니다.
CircuitBreaker, Bulkhead, Retry, TimeLimiter, RateLimiter를 블로킹 및 논블로킹
(suspend / Flow / Mono / Flux / CompletableFuture) 코드 경로에 적용하는 방법을 다룹니다.

## 사용된 Bluetape4k 기능

| 모듈 | 기능 | 사용 위치 |
|---|---|---|
| `bluetape4k-logging` | `KLogging()` / `KLoggingChannel()` | 서비스 및 테스트의 구조화된 로깅 |
| `bluetape4k-junit5` | `runSuspendIO { }` | 실제 I/O를 포함한 suspend 테스트 블록 실행 |
| `bluetape4k-coroutines` | `CoDecorators` | suspend 함수를 위한 프로그래밍 방식의 내결함성 데코레이터 |
| `bluetape4k-assertions` | `shouldBeEqualTo`, `shouldBeTrue` 등 | 타입 안전한 테스트 단언문 |

## Resilience4j 패턴 설명

### Circuit Breaker (서킷 브레이커)

실패율이 설정된 임계값을 초과하면 OPEN 상태로 전환하여 장애 백엔드 호출을 차단합니다.
HALF-OPEN 상태에서 프로브 호출이 성공하면 CLOSED로 복귀합니다.

```kotlin
@CircuitBreaker(name = "backendA", fallbackMethod = "fallback")
fun failureWithFallback(): String { throw IOException("BAM!") }

// 예외 타입별 fallback 오버로드
private fun fallback(ex: HttpServerErrorException): String = "Recovered: ${ex.message}"

// suspend 함수 — 동일한 어노테이션, fallback은 suspend여서는 안 됨
@CircuitBreaker(name = "backendA", fallbackMethod = "suspendFallback")
override suspend fun suspendFailureWithFallback(): String = suspendFailure()

private fun suspendFallback(ex: Throwable): String = "Recovered: ${ex.message}"
```

### Retry (재시도)

실패한 호출을 설정된 최대 횟수까지 재시도합니다. `BusinessException` 등 특정 예외는 재시도에서 제외할 수 있습니다.

```kotlin
@CircuitBreaker(name = "backendA")
@Bulkhead(name = "backendA")
@Retry(name = "backendA")
override suspend fun suspendFailure(): String { throw IOException("BAM!") }
```

> **Flow 반환 시 주의:** `@Retry` 어노테이션은 `Flow`를 반환하는 함수에 적용되지 않습니다.
> `Flow.retry(retry)` 확장 함수를 사용하세요.

### Bulkhead (격벽)

동시 실행 수를 제한하여 스레드 고갈을 방지합니다. 코루틴용 세마포어 방식과
`CompletableFuture` 기반 코드용 스레드 풀 방식을 모두 지원합니다.

```kotlin
@CircuitBreaker(name = "backendA")
@Bulkhead(name = "backendA")
override suspend fun suspendSuccess(): String = "Hello World"
```

### TimeLimiter (시간 제한)

호출에 타임아웃을 적용합니다. `Mono`, `Flux`, `CompletableFuture`와 함께 작동합니다.

> **⚠️ 경고:** `@TimeLimiter`는 Resilience4j 2.4.x에서 Kotlin `suspend` 함수에
> **호환되지 않습니다**. 설정된 시간 후 실제 `TimeoutException`을 발생시킵니다 (무시되지 않음).
> 코루틴 타임아웃에는 `kotlinx.coroutines.withTimeout`을 사용하세요:
>
> ```kotlin
> withTimeout(2_000L) { slowSuspendOperation() }
> ```

### Rate Limiter (속도 제한)

시간 윈도우 내의 호출 수를 제한합니다. Backend B는 IP 기반 속도 제한을 시연합니다.

## 코루틴 통합 핵심 발견 사항

| 패턴 | 지원 여부 | 비고 |
|---|---|---|
| `@CircuitBreaker` + `suspend` | ✅ | AOP 프록시가 suspend 함수를 올바르게 래핑 |
| `@Bulkhead` + `suspend` | ✅ | 세마포어 bulkhead가 suspend와 함께 작동 |
| `@Retry` + `suspend` | ⚠️ | 어노테이션 기반 재시도는 알려진 버그 있음; `CoDecorators` 사용 권장 |
| `@TimeLimiter` + `suspend` | ❌ | 실제 `TimeoutException` 발생; `withTimeout {}` 사용 권장 |
| `@Retry` + `Flow` | ❌ | 적용 안 됨; `Flow.retry(retry)` 확장 함수 사용 |
| suspend용 CircuitBreaker fallback | ⚠️ | fallback 메서드는 suspend여서는 안 됨 |
| suspend/리액티브 메트릭 업데이트 | ⚠️ | 비동기 경로는 레지스트리를 동기적으로 업데이트하지 않음 |

## 설정

전체 설정은 `src/main/resources/application.yml` 참조. 핵심 항목:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      backendA:
        slidingWindowSize: 10
        permittedNumberOfCallsInHalfOpenState: 3
        waitDurationInOpenState: 1s
        failureRateThreshold: 50
        ignoreExceptions:
          - io.bluetape4k.workshop.resilience.exception.BusinessException

  retry:
    instances:
      backendA:
        maxAttempts: 3
        waitDuration: 500ms

  bulkhead:
    instances:
      backendA:
        maxConcurrentCalls: 10

  timelimiter:
    instances:
      backendA:
        timeoutDuration: 2s
```

## 실행

```bash
# 애플리케이션 시작
./gradlew :spring-boot-resilience4j-coroutines:bootRun

# Actuator 엔드포인트
curl http://localhost:8080/actuator/circuitbreakers
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.calls

# API 호출 예시
curl http://localhost:8080/backendA/suspendFailureWithFallback
curl http://localhost:8080/coroutines/backendA/suspendSuccess
```

## 테스트

```bash
./gradlew :spring-boot-resilience4j-coroutines:test
```

테스트: 73개 통과, 6개 스킵 (`@Disabled` — Resilience4j + 코루틴 알려진 제한사항).

## 참고 자료

- [Resilience4j Spring Boot 3 Demo](https://github.com/resilience4j/resilience4j-spring-boot3-demo)
- [Resilience4j Kotlin Coroutines 지원](https://resilience4j.readme.io/docs/getting-started-3)
- [bluetape4k-leader](https://github.com/bluetape4k/bluetape4k-leader) — 분산 리더 선출

[English](README.md)

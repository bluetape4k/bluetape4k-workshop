# Spring Boot 4 + Resilience4j + Coroutines Workshop

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Boot 4 + Resilience4j + Coroutines Workshop**을 실행 가능한 Spring Boot 애플리케이션 기능 워크숍 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 흐름 다이어그램

1. `spring-boot-resilience4j-coroutines`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크숍 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여 줍니다. 없으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

기반 예제: [resilience4j-spring-boot3-demo](https://github.com/resilience4j/resilience4j-spring-boot3-demo)

## 아키텍처

![Spring Boot 4 + Resilience4j + Coroutines Workshop Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-resilience4j-coroutines-readme-architecture-01.png)

![Resilience4j Coroutines Architecture](../../docs/images/readme-diagrams/spring-boot-resilience4j-coroutines-diagram-02.png)

### Circuit Breaker 상태 머신

![Circuit Breaker State Machine diagram](../../docs/images/readme-diagrams/spring-boot-resilience4j-coroutines-diagram-01.png)

## 개요

Spring Boot 4 애플리케이션에서 Kotlin 코루틴과 함께 Resilience4j 2.4.0 장애 허용 패턴을 적용하는 방법을 보여 줍니다.
CircuitBreaker, Bulkhead, Retry, TimeLimiter, RateLimiter를 blocking 경로와 non-blocking(suspend / Flow / Mono / Flux / CompletableFuture) 코드 경로 모두에서 다룹니다.

## 사용한 Bluetape4k 기능

| 모듈 | 기능 | 사용 방식 |
|---|---|---|
| `bluetape4k-logging` | `KLogging()` / `KLoggingChannel()` | 서비스와 테스트의 구조적 로깅 |
| `bluetape4k-junit5` | `runSuspendIO { }` | 실제 I/O를 사용하는 suspend 테스트 블록 실행 |
| `bluetape4k-resilience4j` | `SuspendDecorators` | suspend 함수용 프로그래밍 방식 resilience decorator chain |
| `bluetape4k-testcontainers` | 테스트 인프라 | 이 모듈에는 외부 인프라가 필요하지 않음 |
| `bluetape4k-assertions` | `shouldBeEqualTo`, `shouldBeTrue` 등 | 타입 안전 테스트 assertion |

## Resilience4j 패턴

### Circuit Breaker

실패율이 설정된 임계값을 초과하면 열려서 성능이 저하된 백엔드 호출을 막습니다.
HALF-OPEN 상태에서 probe 호출이 성공하면 CLOSED 상태로 돌아갑니다.

```kotlin
@CircuitBreaker(name = "backendA", fallbackMethod = "fallback")
fun failureWithFallback(): String { throw IOException("BAM!") }

// Fallback overloaded per exception type
private fun fallback(ex: HttpServerErrorException): String = "Recovered: ${ex.message}"

// suspend function — same annotation, fallback must NOT be suspend
@CircuitBreaker(name = "backendA", fallbackMethod = "suspendFallback")
override suspend fun suspendFailureWithFallback(): String = suspendFailure()

private fun suspendFallback(ex: Throwable): String = "Recovered: ${ex.message}"
```

### Retry

실패한 호출을 설정된 최대 횟수까지 재시도합니다. `BusinessException` 같은 특정 예외는 재시도에서 제외할 수 있습니다.

```kotlin
@CircuitBreaker(name = "backendA")
@Bulkhead(name = "backendA")
@Retry(name = "backendA")
override suspend fun suspendFailure(): String { throw IOException("BAM!") }
```

> **Flow 참고:** `@Retry` 애노테이션은 `Flow`를 반환하는 함수에 적용되지 않습니다.
> 대신 `Flow.retry(retry)` 확장을 사용합니다.

### Bulkhead

동시 실행 수를 제한해 스레드 고갈을 방지합니다. 코루틴용 semaphore bulkhead와 `CompletableFuture` 기반 코드용 thread-pool bulkhead를 모두 지원합니다.

```kotlin
@CircuitBreaker(name = "backendA")
@Bulkhead(name = "backendA")
override suspend fun suspendSuccess(): String = "Hello World"
```

### TimeLimiter

호출에 마감 시간을 강제합니다. `Mono`, `Flux`, `CompletableFuture`와 함께 동작합니다.

> **⚠️ 경고:** Resilience4j 2.4.x에서 `@TimeLimiter`는 Kotlin `suspend` 함수와 **호환되지 않습니다**.
> 설정된 시간 이후 실제 `TimeoutException`을 발생시키며, 조용히 무시되지 않습니다.
> 코루틴 timeout 강제에는 `kotlinx.coroutines.withTimeout`을 사용합니다.
>
> ```kotlin
> withTimeout(2_000L) { slowSuspendOperation() }
> ```

### Rate Limiter

시간 창 안의 호출 수를 제한합니다. Backend B는 IP 기반 rate limiting을 보여 줍니다.

## 주요 코루틴 통합 결과

| 패턴 | 지원 | 참고 |
|---|---|---|
| `@CircuitBreaker` + `suspend` | ✅ | AOP proxy가 suspend 함수를 올바르게 감쌉니다. |
| `@Bulkhead` + `suspend` | ✅ | Semaphore bulkhead가 suspend와 동작합니다. |
| `@Retry` + `suspend` | ⚠️ | suspend에 대한 애노테이션 기반 retry에는 알려진 버그가 있습니다. `CoDecorators`를 사용합니다. |
| `@TimeLimiter` + `suspend` | ❌ | 실제 `TimeoutException`을 발생시킵니다. 대신 `withTimeout {}`을 사용합니다. |
| `@Retry` + `Flow` | ❌ | 적용되지 않습니다. `Flow.retry(retry)` 확장을 사용합니다. |
| suspend용 CircuitBreaker fallback | ⚠️ | fallback 메서드는 non-suspend여야 합니다. |
| suspend/reactive metric 업데이트 | ⚠️ | async 경로는 registry를 동기적으로 갱신하지 않습니다. |

## 설정

전체 설정은 `src/main/resources/application.yml`을 참고합니다. 주요 섹션은 다음과 같습니다.

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
# Start the application
./gradlew :spring-boot-resilience4j-coroutines:bootRun

# Actuator endpoints
curl http://localhost:8080/actuator/circuitbreakers
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.calls

# Example API calls
curl http://localhost:8080/backendA/suspendFailureWithFallback
curl http://localhost:8080/coroutines/backendA/suspendSuccess
```

## 테스트

```bash
./gradlew :spring-boot-resilience4j-coroutines:test
```

테스트 범위: 73 tests, 6 skipped(알려진 Resilience4j + coroutine 제한 때문에 `@Disabled` 처리).

## 참고

- [Resilience4j Spring Boot 3 Demo](https://github.com/resilience4j/resilience4j-spring-boot3-demo)
- [Resilience4j Kotlin Coroutines support](https://resilience4j.readme.io/docs/getting-started-3)
- [bluetape4k-leader](https://github.com/bluetape4k/bluetape4k-leader) — distributed leader election

# Spring Boot 4 + Resilience4j + Coroutines Demo

원본: [resilience4j-spring-boot3-demo](https://github.com/resilience4j/resilience4j-spring-boot3-demo)

## Circuit Breaker 상태 전이

```mermaid
flowchart LR
    클라이언트 -->|요청| 컨트롤러

    subgraph 컨트롤러 레이어
        컨트롤러 --> BAC[BackendAController\n@CircuitBreaker]
        컨트롤러 --> BBC[BackendBController\n@RateLimiter]
        컨트롤러 --> BCC[BackendCController\n@Retry]
        컨트롤러 --> SAC[SuspendBackendAController\n코루틴 + @CircuitBreaker]
        컨트롤러 --> SBC[SuspendBackendBController\n코루틴 + @Retry]
    end

    subgraph 서비스 레이어
        BAC --> BAS[BackendAService]
        BBC --> BBS[BackendBService]
        BCC --> BCS[BackendCService]
        SAC --> BACS[BackendACoService]
        SBC --> BBCS[BackendBCoService]
    end

    subgraph Circuit Breaker 상태
        CLOSED[CLOSED\n정상 처리] -->|실패율 초과| OPEN[OPEN\n즉시 거부]
        OPEN -->|대기 시간 경과| HALF_OPEN[HALF-OPEN\n일부 허용]
        HALF_OPEN -->|성공| CLOSED
        HALF_OPEN -->|실패| OPEN
    end

    BAS --> CLOSED
    BACS --> CLOSED
```

## 개요

Resilience4j 2.4.0 을 Spring Boot 4 환경에서 Coroutines를 사용하는 Service에 적용하는 예제입니다.

## 주요 컴포넌트

| 클래스 | 패턴 | 설명 |
|---|---|---|
| `BackendAController` | CircuitBreaker + Bulkhead + Retry | 일반 동기/Mono/Flux/Future 방식 |
| `BackendBController` | RateLimiter | IP 기반 요청 제한 |
| `BackendCController` | Retry | 재시도 전략 적용 |
| `SuspendBackendAController` | CircuitBreaker + 코루틴 | `suspend` 함수에 CircuitBreaker 적용 |
| `SuspendBackendBController` | Retry + 코루틴 | `suspend` 함수에 Retry 적용 |
| `BackendAService` | 일반 서비스 | Mono/Flux/Future 모두 지원, fallback 메서드 포함 |
| `BackendACoService` | 코루틴 서비스 | `suspend` 함수 + `Flow` 반환 지원 |
| `CircuitBreakerRegistryEventConsumer` | 이벤트 처리 | Circuit Breaker 상태 변경 이벤트 수신 |
| `RetryRegistryEventConsumer` | 이벤트 처리 | Retry 이벤트 수신 |

## Resilience4j 패턴별 설명

### Circuit Breaker

호출 실패율이 임계값을 초과하면 OPEN 상태로 전환하여 즉시 요청을 거부합니다. 설정된 대기 시간 후 HALF-OPEN으로 전환하여 일부 요청을 허용하고, 성공 시 CLOSED로 복귀합니다.

```kotlin
@CircuitBreaker(name = "backendA", fallbackMethod = "fallback")
fun failureWithFallback(): String { ... }

// fallback 메서드는 예외 타입별로 오버로드 가능
private fun fallback(ex: HttpServerErrorException): String = "Recovered: ${ex.message}"
```

### Retry

지정된 횟수만큼 실패한 호출을 재시도합니다. `BusinessException` 같이 특정 예외는 무시하도록 설정할 수 있습니다.

### Bulkhead

동시 실행 수를 제한하여 스레드 고갈을 방지합니다. `Bulkhead.Type.THREADPOOL` 방식으로 Future 기반 비동기에도 적용 가능합니다.

### TimeLimiter

지정된 시간 내에 완료되지 않으면 `TimeoutException`을 발생시킵니다. `Mono`/`Flux`/`CompletableFuture`에 적용 가능하며, **`suspend` 함수에는 적용되지 않습니다.**

## 코루틴 통합 시 주의사항

| 항목 | 내용 |
|---|---|
| `@CircuitBreaker` + `suspend` | 지원 (AOP 프록시로 래핑) |
| `@Retry` + `suspend` | 지원 |
| `@Bulkhead` + `suspend` | 지원 |
| `@TimeLimiter` + `suspend` | **미지원** — `withTimeout {}` 사용 권장 |
| `@Retry` + `Flow` 반환 | **미지원** — `Flow.retry(retry)` 확장 함수 사용 권장 |

## 실행 방법

```bash
./gradlew :resilience4j-coroutines:bootRun
# Actuator: http://localhost:8080/actuator/circuitbreakers
# Health:   http://localhost:8080/actuator/health
```

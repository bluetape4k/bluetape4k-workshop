# Lessons: Issue #102 — Resilience4j + Spring Boot 4 + Coroutines Workshop

Date: 2026-05-23
Module: `spring-boot/resilience4j-coroutines`
Branch: `feat/issue-102-resilience4j`

---

## 1. @TimeLimiter는 suspend 함수에서 조용히 무시되지 않는다

### 근본 원인

"Resilience4j가 Kotlin suspend 함수의 `@TimeLimiter`를 조용히 무시한다"는
가정은 **틀렸다**.

Resilience4j 2.4.x에서 `suspend` 함수에 `@TimeLimiter`를 적용하면 설정된
시간(기본 2초) 이후 실제 `TimeoutException`이 발생한다. 이 동작은 두 가지
후속 효과를 깨뜨린다.

1. endpoint가 HTTP 200이 아니라 HTTP 500을 반환한다.
2. suspend 함수에서는 CB proxy가 가로채기 전에 `TimeoutException`이
   전파되므로 CircuitBreaker fallback이 호출되지 않는다.

### 증거

- 이전에 통과하던 `CoroutineCircuitBreakerTest.SuspendMethod.Timeout`도
  `BackendACoService.suspendTimeout()`에 `@TimeLimiter`를 추가하자 실패했다.
- `@TimeLimiter`를 제거하자 두 테스트가 다시 통과했다.

### 결정

어떤 `suspend` 함수에도 `@TimeLimiter`를 적용하지 않는다. KDoc에는 다음
내용을 명시한다.
> "@TimeLimiter must NOT be applied to Kotlin suspend functions. It causes an actual
> TimeoutException after the configured duration."

coroutine timeout 강제에는 `kotlinx.coroutines.withTimeout`을 사용한다.
```kotlin
withTimeout(2_000L) { slowSuspendOperation() }
```

---

## 2. BulkheadTest permit leak가 연쇄 실패를 만든다

### 근본 원인

`BulkheadRegistry.tryAcquirePermission()`은 전역 "acquired" counter를
증가시킨다. 대응되는 `releasePermission()` 호출 전에 테스트 assertion이
실패하면 permit이 누수된다.

`BulkheadTest.backendA`에서 누수된 permit 때문에 이후 HTTP 기반 테스트
(`CircuitBreakerTest.BackendB`)가 기대한 service exception 대신
`BulkheadFullException`을 받았다. 그 결과 circuit breaker가 열리지 못하고
테스트가 실패했다.

### 수정

항상 try-finally와 `acquired` counter를 함께 사용한다.

```kotlin
var acquired = 0
try {
    repeat(maxCalls) {
        bulkhead.tryAcquirePermission().shouldBeTrue()
        acquired++
    }
    bulkhead.tryAcquirePermission().shouldBeFalse()
} finally {
    repeat(acquired) { bulkhead.releasePermission() }
}
```

또한 `maxConcurrentCalls`를 repeat count로 사용하지 않는다. 이전 테스트가
일부 slot을 소비했을 수 있으므로 `bulkhead.metrics.availableConcurrentCalls`를
snapshot으로 사용한다.

---

## 3. Reactive/Future/Coroutine metric은 Resilience4j registry를 동기 갱신하지 않는다

### 근본 원인

blocking(`suspend` via thread dispatch, `CompletableFuture`, reactive `Mono`/`Flux`)
경로에서는 Resilience4j가 in-memory registry를 비동기로 갱신한다. 따라서
이 경로에서 delta assertion(`currentCount + 1`)은 비결정적이다.

### 수정

base test class에 `metricsAssertionEnabled()` hook을 추가한다. 다음 클래스에서는
`false`로 override한다.
- `ReactiveRetryTest`
- `FutureRetryTest`
- `CoroutineRetryTest`

blocking, synchronous path인 `RetryTest`만 `metricsAssertionEnabled() = true`를 유지한다.

---

## 4. suspend 함수의 CircuitBreaker fallback method는 suspend이면 안 된다

### 근본 원인

Resilience4j AOP proxy는 reflection으로 fallback method에 dispatch한다. Kotlin
`suspend` 함수에서는 proxy의 fallback dispatch가 continuation parameter를
올바르게 호출하지 못하므로, `suspend` fallback이 조용히 실패하거나 예외를
던진다.

### 결정

suspend 함수의 `@CircuitBreaker(fallbackMethod = "...")`에 연결되는 fallback
method는 반드시 **일반(non-suspend) 함수**여야 한다.

```kotlin
@CircuitBreaker(name = BACKEND_A, fallbackMethod = "suspendFallback")
override suspend fun suspendFailureWithFallback(): String = suspendFailure()

// suspend이면 안 됨
private fun suspendFallback(ex: Throwable): String = "Recovered: ${ex.message}"
```

---

## 5. Flow 반환 함수에는 @Retry annotation이 적용되지 않는다

### 근본 원인

Resilience4j AOP는 함수 호출 시점에 intercept한다. `Flow` 반환 함수의 실제
실행은 lazy, 즉 collector-driven이므로 retry interceptor는 emission 이전의
호출 시점에 실행되고 개별 emission을 감싸지 못한다.

### 결정

Flow retry에는 `Flow.retry(retry)` extension을 사용한다.

```kotlin
override fun flowFailure(): Flow<String> {
    return flowOf("Hello", "World")
        .onStart { throw IOException("BAM!") }
        // .retry(retryRegistry.retry("backendA")) -- Flow retry에는 이것을 사용
}
```

---

## 6. Spring Boot 4에서 Prometheus metric name format이 변경되었다

표준 Resilience4j Prometheus metric name
(`resilience4j_circuitbreaker_calls_total`)은 Spring Boot 4 + Micrometer에서
실제로 방출되는 metric과 일치하지 않는다.

우회책으로 test suite에서 Prometheus endpoint assertion을 제거했다.
추적 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/153

---

## Review 누락

| Finding | Impact | How caught |
|---|---|---|
| Code reviewer suggested adding `@TimeLimiter` to `suspendTimeout()` | Broke 2 tests | Test run after applying suggestion |
| `BulkheadTest` without try-finally | Cascade circuit breaker failures | Test isolation debugging |

---

## 향후 지침

1. **code reviewer 제안을 적용한 뒤에는 항상 테스트를 실행한다**. 제안은
   blocking code에는 맞아도 coroutine에는 틀릴 수 있다.
2. **BulkheadTest isolation**: 항상 try-finally와 `acquired` counter를 사용한다.
3. **Reactor/coroutine metrics**: 조건부 assertion에는 `metricsAssertionEnabled()`
   pattern을 사용한다.
4. **@TimeLimiter + suspend**: KDoc과 README에 두드러지게 문서화한다. 이는
   매우 위험한 footgun이다.

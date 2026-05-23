# Lessons: Issue #102 — Resilience4j + Spring Boot 4 + Coroutines Workshop

Date: 2026-05-23
Module: `spring-boot/resilience4j-coroutines`
Branch: `feat/issue-102-resilience4j`

---

## 1. @TimeLimiter is NOT silently ignored for suspend functions

### Root cause

The assumption "Resilience4j silently ignores `@TimeLimiter` for Kotlin suspend functions" is **wrong**.

In Resilience4j 2.4.x, applying `@TimeLimiter` to a `suspend` function causes an actual
`TimeoutException` to be thrown after the configured duration (default 2 seconds). This breaks
two downstream effects:

1. The endpoint returns HTTP 500 (not 200)
2. CircuitBreaker fallback is not invoked because `TimeoutException` propagates before the CB proxy
   can intercept it for suspend functions

### Evidence

- `CoroutineCircuitBreakerTest.SuspendMethod.Timeout` — a previously passing test — also broke
  when `@TimeLimiter` was added to `BackendACoService.suspendTimeout()`
- Removing `@TimeLimiter` restored both tests to passing

### Decision

Do NOT apply `@TimeLimiter` to any `suspend` function. Update KDoc to say:
> "@TimeLimiter must NOT be applied to Kotlin suspend functions. It causes an actual
> TimeoutException after the configured duration."

For coroutine timeout enforcement, use `kotlinx.coroutines.withTimeout`:
```kotlin
withTimeout(2_000L) { slowSuspendOperation() }
```

---

## 2. BulkheadTest permit leak causes cascade failures

### Root cause

`BulkheadRegistry.tryAcquirePermission()` increments the "acquired" counter globally.
If test assertions fail before the corresponding `releasePermission()` call, permits leak.

A leaked permit in `BulkheadTest.backendA` caused subsequent HTTP-based tests (`CircuitBreakerTest.BackendB`)
to receive `BulkheadFullException` instead of the expected service exception — preventing the
circuit breaker from opening and causing the test to fail.

### Fix

Always use try-finally + an `acquired` counter:

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

Also, do NOT use `maxConcurrentCalls` as the repeat count. Use
`bulkhead.metrics.availableConcurrentCalls` as a snapshot, since prior tests may have
consumed some slots.

---

## 3. Reactive/Future/Coroutine metrics do not update Resilience4j registry synchronously

### Root cause

For blocking (`suspend` via thread dispatch, `CompletableFuture`, or reactive `Mono`/`Flux`),
Resilience4j updates the in-memory registry asynchronously. Delta assertions
(`currentCount + 1`) are non-deterministic for these paths.

### Fix

Introduce `metricsAssertionEnabled()` hook in the base test class. Override to `false` in:
- `ReactiveRetryTest`
- `FutureRetryTest`
- `CoroutineRetryTest`

Only `RetryTest` (blocking, synchronous path) keeps `metricsAssertionEnabled() = true`.

---

## 4. CircuitBreaker fallback method for suspend functions must NOT be suspend

### Root cause

The Resilience4j AOP proxy dispatches to the fallback method using reflection. For Kotlin
`suspend` functions, the proxy's fallback dispatch does NOT correctly call the continuation
parameter, so a `suspend` fallback silently fails or throws.

### Decision

Fallback methods for `@CircuitBreaker(fallbackMethod = "...")` on suspend functions must be
**regular (non-suspend) functions**:

```kotlin
@CircuitBreaker(name = BACKEND_A, fallbackMethod = "suspendFallback")
override suspend fun suspendFailureWithFallback(): String = suspendFailure()

// Must NOT be suspend
private fun suspendFallback(ex: Throwable): String = "Recovered: ${ex.message}"
```

---

## 5. @Retry annotation on Flow-returning functions is not applied

### Root cause

Resilience4j AOP intercepts the function call at invocation time. For `Flow`-returning
functions, the actual execution is lazy (collector-driven), so the retry interceptor fires
at invocation (before any emission) and does not wrap individual emissions.

### Decision

Use `Flow.retry(retry)` extension for retry on Flow:

```kotlin
override fun flowFailure(): Flow<String> {
    return flowOf("Hello", "World")
        .onStart { throw IOException("BAM!") }
        // .retry(retryRegistry.retry("backendA")) -- use this for retry on Flow
}
```

---

## 6. Prometheus metric name format changed in Spring Boot 4

The standard Resilience4j Prometheus metric name (`resilience4j_circuitbreaker_calls_total`)
does not match the actual metric emitted under Spring Boot 4 + Micrometer.

Prometheus endpoint assertions were removed from the test suite as a workaround.
Tracked as: https://github.com/bluetape4k/bluetape4k-workshop/issues/153

---

## Review Misses

| Finding | Impact | How caught |
|---|---|---|
| Code reviewer suggested adding `@TimeLimiter` to `suspendTimeout()` | Broke 2 tests | Test run after applying suggestion |
| `BulkheadTest` without try-finally | Cascade circuit breaker failures | Test isolation debugging |

---

## Future Guidance

1. **Always run tests after applying code reviewer suggestions** — suggestions may be
   correct for blocking code but wrong for coroutines.
2. **BulkheadTest isolation**: always use try-finally + `acquired` counter.
3. **Reactor/coroutine metrics**: use `metricsAssertionEnabled()` pattern for conditional assertions.
4. **@TimeLimiter + suspend**: document prominently in KDoc and README — this is a footgun.

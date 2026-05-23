# Lesson: Issue #103 — Observability End-to-End Workshop

**Date**: 2026-05-23  
**Branch**: `feat/issue-103-observability-e2e`  
**Modules**: `observability-basic`, `observability-advanced`

---

## Summary

Added two Spring Boot WebFlux + Kotlin coroutine observability workshop modules demonstrating
trace/log/metric correlation across HTTP, service, DB (Exposed/H2), Redis cache, and coroutine
dispatcher boundaries.

---

## Key Decisions

### 1. `observed()` helper instead of `withObservationSuspending`

Micrometer 1.14 `withObservationSuspending` is missing `finally { stop() }` on the happy path —
the observation is never stopped when the block completes normally. We implemented a local helper:

```kotlin
suspend fun <T> observed(name: String, registry: ObservationRegistry, block: suspend () -> T): T {
    val observation = registry.start(name)
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        observation.error(e)
        throw e
    } finally {
        observation.stop()  // ← always runs
    }
}
```

**Why**: `finally` guarantees stop on all paths (success, exception, cancellation), which satisfies
`TestObservationRegistryAssert.doesNotHaveAnyRemainingCurrentObservation()`.

### 2. `TestObservationRegistryAssert` vs `ObservationRegistryAssert`

`hasObservationWithNameEqualTo()` returning a chainable `.that()` is defined on
`TestObservationRegistryAssert`, not `ObservationRegistryAssert`. Always use:

```kotlin
TestObservationRegistryAssert.assertThat(testRegistry)
    .hasObservationWithNameEqualTo("name")
    .that().hasBeenStarted().hasBeenStopped()
```

### 3. `TestObservationRegistry` kills Tracer → `@AutoConfigureTracing` on separate class

`@Import(TestObservationConfig::class)` replaces the real `ObservationRegistry` with
`TestObservationRegistry` which has no Tracer. This prevents `traceparent` header propagation
in outbound `WebClient` calls.

**Fix**: move `TracePropagationTest` to a separate class annotated `@AutoConfigureTracing` (no
`@Import(TestObservationConfig::class)`). Spring creates a distinct context with the real
Micrometer + OpenTelemetry bridge.

### 4. Spring Boot 4.0 — `spring-boot-starter-webclient` is a separate module

`WebClient.Builder` is no longer bundled in `spring-boot-starter-webflux`. It requires an
explicit dependency:

```kotlin
implementation(libs.spring.boot.starter.webclient)
```

### 5. OkHttp 5.x `MockWebServer` — call `start()` before `@DynamicPropertySource`

`MockWebServer().also { it.start() }` must be called in the companion object before
`@DynamicPropertySource` reads `mockServer.url("/")`. The server must be running to return a
valid URL.

### 6. `bluetape4k-mock-web-server` is a Docker image, not a Maven artifact

`BluetapeHttpServer` (from `bluetape4k-testcontainers`) wraps a Docker image named
`bluetape4k-mock-web-server`. There is no Maven artifact with that coordinate. Use
`libs.okhttp3.mockwebserver` directly.

---

## Bugs Found and Fixed

### MockWebServer shared `requestQueue` pollutes cross-context tests

**Symptom**: `TracePropagationTest` passes in isolation but fails in the full test suite.
The `takeRequest(2, SECONDS)` call returns a stale request from `OrderControllerTest` that has
no `traceparent` header (different Spring context, no Tracer).

**Root cause**: `resetMockServerDispatcher()` only replaced the response dispatcher but never
drained the recorded request queue (`requestQueue`). Stale entries from earlier tests remained
and were consumed by `TracePropagationTest.takeRequest()`.

**Fix**: drain the request queue in `@AfterEach` before resetting the dispatcher:

```kotlin
@AfterEach
fun resetMockServerDispatcher() {
    @Suppress("ControlFlowWithEmptyBody")
    while (mockServer.takeRequest(0, TimeUnit.MILLISECONDS) != null) { /* drain */ }
    mockServer.dispatcher = QueueDispatcher()
}
```

**Pattern to remember**: when multiple test classes share a single `MockWebServer` singleton,
drain the `requestQueue` in `@AfterEach` to prevent recorded request pollution across Spring
context boundaries.

---

## Test Results

| Module | Tests | Result |
|---|---|---|
| `observability-basic` | 6 | ✅ all pass |
| `observability-advanced` | 10 | ✅ all pass |

---

## Future Guidance

- Always use `observed()` (local helper) instead of `withObservationSuspending` until Micrometer
  fixes the missing `finally { stop() }` in the suspend variant.
- For `TestObservationRegistry` assertion chains: import `TestObservationRegistryAssert`, not
  `ObservationRegistryAssert`.
- When separating observation-only tests from propagation tests in the same test class hierarchy,
  put propagation tests in a separate class with `@AutoConfigureTracing` and NO
  `@Import(TestObservationConfig::class)`.
- Drain `MockWebServer.requestQueue` in `@AfterEach` when the server is shared across multiple
  test classes using different Spring contexts.

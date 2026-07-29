# Lesson: Issue #103 — Observability End-to-End Workshop

**Date**: 2026-05-23  
**Branch**: `feat/issue-103-observability-e2e`  
**Modules**: `observability-basic`, `observability-advanced`

---

## 요약

HTTP, service, DB(Exposed/H2), Redis cache, coroutine dispatcher 경계를
가로지르는 trace/log/metric correlation을 보여주기 위해 Spring Boot WebFlux +
Kotlin coroutine observability workshop 모듈 2개를 추가했다.

---

## 주요 결정

### 1. `withObservationSuspending` 대신 `observed()` helper 사용

Micrometer 1.14의 `withObservationSuspending`은 happy path에서
`finally { stop() }`가 빠져 있다. 블록이 정상 완료될 때 observation이
중지되지 않는다. 이를 보완하기 위해 local helper를 구현했다.

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
        observation.stop()  // 항상 실행됨
    }
}
```

**이유**: `finally`는 success, exception, cancellation 모든 경로에서 stop을
보장하므로 `TestObservationRegistryAssert.doesNotHaveAnyRemainingCurrentObservation()`
조건을 만족한다.

### 2. `TestObservationRegistryAssert` vs `ObservationRegistryAssert`

chainable `.that()`을 반환하는 `hasObservationWithNameEqualTo()`는
`ObservationRegistryAssert`가 아니라 `TestObservationRegistryAssert`에 정의되어
있다. 항상 다음 형태를 사용한다.

```kotlin
TestObservationRegistryAssert.assertThat(testRegistry)
    .hasObservationWithNameEqualTo("name")
    .that().hasBeenStarted().hasBeenStopped()
```

### 3. `TestObservationRegistry`가 Tracer를 제거하므로 별도 class에 `@AutoConfigureTracing` 적용

`@Import(TestObservationConfig::class)`는 실제 `ObservationRegistry`를 Tracer가
없는 `TestObservationRegistry`로 교체한다. 이 때문에 outbound `WebClient`
호출에서 `traceparent` header propagation이 막힌다.

**수정**: `TracePropagationTest`를 `@AutoConfigureTracing`이 붙은 별도 class로
옮기고 `@Import(TestObservationConfig::class)`는 사용하지 않는다. Spring은 실제
Micrometer + OpenTelemetry bridge를 포함한 별도 context를 만든다.

### 4. Spring Boot 4.0에서 `spring-boot-starter-webclient`는 별도 module이다

`WebClient.Builder`는 더 이상 `spring-boot-starter-webflux`에 함께 들어 있지
않다. 명시적 dependency가 필요하다.

```kotlin
implementation(libs.spring.boot.starter.webclient)
```

### 5. OkHttp 5.x `MockWebServer`는 `@DynamicPropertySource` 전에 `start()`해야 한다

`@DynamicPropertySource`가 `mockServer.url("/")`을 읽기 전에 companion object에서
`MockWebServer().also { it.start() }`를 호출해야 한다. 유효한 URL을 반환하려면
server가 실행 중이어야 한다.

### 6. `bluetape4k-mock-web-server`는 Maven artifact가 아니라 Docker image다

`bluetape4k-testcontainers`의 `BluetapeHttpServer`는
`bluetape4k-mock-web-server`라는 Docker image를 감싼다. 해당 coordinate를 가진
Maven artifact는 없다. `libs.okhttp3.mockwebserver`를 직접 사용한다.

---

## 발견하고 수정한 버그

### MockWebServer shared `requestQueue` pollutes cross-context tests

**증상**: `TracePropagationTest`는 단독 실행 시 통과하지만 전체 test suite에서는
실패한다. `takeRequest(2, SECONDS)` 호출이 `OrderControllerTest`에서 남은 stale
request를 반환하며, 그 request에는 `traceparent` header가 없다. 서로 다른 Spring
context이고 Tracer도 없기 때문이다.

**근본 원인**: `resetMockServerDispatcher()`는 response dispatcher만 교체했고
recorded request queue(`requestQueue`)를 비우지 않았다. 이전 테스트에서 남은 stale
entry가 `TracePropagationTest.takeRequest()`에 의해 소비되었다.

**수정**: dispatcher를 재설정하기 전에 `@AfterEach`에서 request queue를 비운다.

```kotlin
@AfterEach
fun resetMockServerDispatcher() {
    @Suppress("ControlFlowWithEmptyBody")
    while (mockServer.takeRequest(0, TimeUnit.MILLISECONDS) != null) { /* 비우기 */ }
    mockServer.dispatcher = QueueDispatcher()
}
```

**기억할 pattern**: 여러 test class가 단일 `MockWebServer` singleton을 공유하면,
Spring context 경계를 넘는 recorded request 오염을 막기 위해 `@AfterEach`에서
`requestQueue`를 비운다.

---

## 테스트 결과

| Module | Tests | Result |
|---|---|---|
| `observability-basic` | 6 | ✅ all pass |
| `observability-advanced` | 10 | ✅ all pass |

---

## 향후 지침

- Micrometer가 suspend variant에서 누락된 `finally { stop() }`를 수정할 때까지
  `withObservationSuspending` 대신 local helper인 `observed()`를 항상 사용한다.
- `TestObservationRegistry` assertion chain에서는 `ObservationRegistryAssert`가
  아니라 `TestObservationRegistryAssert`를 import한다.
- 같은 test class hierarchy에서 observation-only 테스트와 propagation 테스트를
  분리할 때는 propagation 테스트를 `@AutoConfigureTracing`이 붙은 별도 class에
  두고 `@Import(TestObservationConfig::class)`는 사용하지 않는다.
- 서로 다른 Spring context를 사용하는 여러 test class가 server를 공유하면
  `@AfterEach`에서 `MockWebServer.requestQueue`를 비운다.

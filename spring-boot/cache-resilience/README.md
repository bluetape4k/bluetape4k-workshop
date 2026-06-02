# Spring Boot Cache Resilience

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Boot Cache Resilience** as a runnable Spring Boot application feature workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `spring-boot-cache-resilience`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Redis primary cache with Caffeine local fallback using Resilience4j CircuitBreaker.
Demonstrates the full circuit breaker state machine (`CLOSED → OPEN → HALF-OPEN → CLOSED`)
driven by real network failures injected via Toxiproxy.

## Architecture

![Spring Boot Cache Resilience Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-cache-resilience-readme-architecture-01.png)

The diagram shows the request flow, CircuitBreaker state machine
(CLOSED → OPEN → HALF-OPEN → CLOSED), and the ToxiproxyServer chaos injection
used in integration tests.

![Cache Resilience architecture](../../docs/images/readme-diagrams/spring-boot-cache-resilience-diagram-01.png)

## What This Module Shows

- `ResilientProductService`: Redis read wrapped in `SuspendDecorators.ofSupplier { }.withCircuitBreaker(cb).withFallback { ... }`.
- Full CB state machine: CLOSED → OPEN (Redis failure) → HALF-OPEN (probe) → CLOSED (recovered).
- `ToxiproxyServer` from `bluetape4k-testcontainers`: inject `timeout(1ms)` toxic to drop Redis connections fast.
- Caffeine local cache as fallback store during OPEN state.
- Recovery test: remove toxic → CB transitions to CLOSED → Redis resumes.

## Running

```bash
./gradlew :spring-boot-cache-resilience:bootRun
```

Then open:

- Swagger UI: `http://localhost:8090/swagger-ui.html`
- Actuator health (shows CB state): `http://localhost:8090/actuator/health`
- Actuator CircuitBreaker events: `http://localhost:8090/actuator/circuitbreakerevents`

## Running Tests

```bash
./gradlew :spring-boot-cache-resilience:test
```

Tests start Docker containers (Redis + Toxiproxy), inject failures, and verify the full
circuit breaker state machine without mocking.

## Used Bluetape4k Features

| Module | Feature | Usage |
|--------|---------|-------|
| `bluetape4k-logging` | `KLoggingChannel()` | Coroutine-aware structured logging in service and test |
| `bluetape4k-resilience4j` | `SuspendDecorators` | Programmatic CB + fallback chain for suspend functions |
| `bluetape4k-testcontainers` | `ToxiproxyServer`, `RedisServer` | Real network failure injection in integration tests |
| `bluetape4k-junit5` | `runSuspendIO { }` | Suspend-based integration test runner |

## Source Map

- `CacheResilienceApplication.kt` starts the Spring Boot application.
- `config/ResilientCacheConfig.kt` configures Lettuce, Caffeine cache, and CircuitBreaker bean.
- `service/ResilientProductService.kt` implements Redis → CB → Caffeine fallback pattern with `SuspendDecorators`.
- `application.yml` sets port `8090`, actuator CB health, and Resilience4j instance config.
- `ResilientCacheServiceTest.kt` uses `ToxiproxyServer` + `RedisServer` to verify CLOSED→OPEN→HALF-OPEN→CLOSED transitions.

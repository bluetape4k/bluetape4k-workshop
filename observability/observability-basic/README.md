# observability-basic

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **observability-basic** as a runnable metrics, tracing, and observation workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `observability-observability-basic`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Minimal observability workshop demonstrating Micrometer Observation + W3C trace propagation
across a WebFlux HTTP endpoint, a coroutine service, and an outbound WebClient call.

No infrastructure (DB, Redis, Kafka) required. MockWebServer is used for downstream simulation.

## Architecture

![observability-basic Graphviz architecture diagram](../../docs/images/readme-diagrams/observability-observability-basic-readme-architecture-01.png)

![observability basic Architecture diagram](../../docs/images/readme-diagrams/observability-observability-basic-architecture-01.png)

## Span Tree

```
http.server.requests            (auto — Spring Boot)
  └─ order.service.fetch        (manual — observed())
       └─ http.client.requests  (auto — Micrometer WebClient)
            └─ downstream inventory service
```

## Key Concepts

| Concept | Implementation |
|---------|---------------|
| Manual span | `observed("order.service.fetch", registry) { }` |
| W3C traceparent propagation | Auto via Spring Boot's `WebClient.Builder` bean |
| Test assertions | `TestObservationRegistry` (no Zipkin required) |
| 4xx handling | `onStatus(4xx) { Mono.empty() }` → returns null |
| 5xx handling | `onStatus(5xx) { resp.createException() }` → throws |

## Test Coverage

- `OrderServiceTest`: span lifecycle (started/stopped), error recording, cancellation safety
- `OrderControllerTest`: HTTP 200 integration, W3C traceparent header propagation

## Configuration

```yaml
workshop:
  observability:
    inventory:
      base-url: http://localhost:8080

management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      export:
        enabled: false  # set true to export to OTel collector
```

## Dependencies

- `bluetape4k-micrometer` — `observed()` coroutine helper (stop-safe wrapper; see ObservationSupport.kt)
- `micrometer-tracing-bridge-otel` — OTel bridge for W3C propagation
- `micrometer-context-propagation` — reactor ↔ coroutine context bridging
- `spring-boot-starter-opentelemetry` — auto-configures WebClient instrumentation

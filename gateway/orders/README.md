# Gateway Orders Service

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Gateway Orders Service** as a runnable gateway and downstream service coordination workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `gateway-orders`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Order service example for the Gateway workshop. It exposes product and order APIs, redirects the root path to Swagger UI, and runs as the order backend on port `8082`.

## Architecture

![Gateway Orders architecture](../../docs/images/readme-diagrams/gateway-orders-diagram-01.png)

## What This Module Shows

- WebFlux controller endpoint at `GET /api/v1/products`.
- WebFlux controller endpoint at `GET /api/v1/orders`.
- UUID v7 IDs generated for sample products and orders.
- Root-path redirect from `/` to `/swagger-ui.html`.
- Actuator endpoint exposure for local workshop observability.

## Running

```bash
./gradlew :orders:bootRun
```

Then open:

- Swagger UI: `http://localhost:8082/swagger-ui.html`
- Products API: `http://localhost:8082/api/v1/products`
- Orders API: `http://localhost:8082/api/v1/orders`
- Actuator endpoints: `http://localhost:8082/actuator`

## Used Bluetape4k Features

| Module | Feature | Usage |
|--------|---------|-------|
| `bluetape4k-logging` | `KLoggingChannel()`, `KLogging()` | Coroutine-aware structured logging in controllers and config |
| `bluetape4k-idgenerators` | `Uuid` (UUID v7) | Type-safe UUID v7 generation for product and order IDs |
| `bluetape4k-support` | `uninitialized()`, `unsafeLazy` | Deferred field initialization for injected beans |
| `bluetape4k-assertions` | `shouldBeFalse` | Concise test assertions |
| `bluetape4k-junit5` | `runSuspendIO { }` | Suspend-based integration test runner |

## Source Map

- `OrderApplication.kt` starts the Spring Boot application.
- `ProductController.kt` returns sample products.
- `OrderController.kt` returns sample orders.
- `OrderConfig.kt`, `SwaggerConfig.kt`, and `ObservationConfig.kt` provide service metadata, OpenAPI, and observation support.
- `application.yml` sets `spring.application.name=Orders`, AOT, port `8082`, and management endpoint exposure.

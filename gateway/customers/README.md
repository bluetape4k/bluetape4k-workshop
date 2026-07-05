# Gateway Customers Service

[한국어](README.ko.md) | English

## What This Module Shows

`customers` is the customer backend used by the gateway workshop. It runs as a
Spring Boot WebFlux application on `8081` and exposes a small suspend controller
at `GET /api/v1/customers`.

## Architecture

![Gateway Customers Service architecture](../../docs/images/readme-diagrams/gateway-customers-readme-architecture-01.png)

The service has no database dependency. `CustomerService` returns two sample
`Customer` values, `Winter` and `Spring`, while `CustomerController` exposes the
HTTP contract so the gateway example can focus on routing instead of
persistence.

## Runtime Contract

| Concern | Source-backed behavior |
|---|---|
| HTTP API | `GET /api/v1/customers` returns a JSON array of customers |
| Swagger landing page | `/` is rewritten to `/swagger-ui.html` by `RedirectWebFilter` |
| Observability | Actuator exposes `health,info`; Micrometer URI filter excludes management and API-doc paths |
| AOT | `spring.aot.enabled=true` in `application.yml` |

## Run

```bash
./gradlew :customers:bootRun
```

Open:

```bash
http :8081/api/v1/customers
http :8081/swagger-ui.html
http :8081/actuator
```

## bluetape4k Usage

| Library | Usage |
|---|---|
| `bluetape4k-logging` | `KLoggingChannel()` in the application, config, filter, and controller |
| `bluetape4k-support` | `requireNotBlank` validation in the `Customer` model |
| `bluetape4k-coroutines` | Suspend WebFlux controller endpoint |
| `bluetape4k-assertions` | WebFlux endpoint tests use bluetape4k assertions |

## Source References

- `src/main/kotlin/io/bluetape4k/workshop/gateway/customer/controller/CustomerController.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/customer/service/CustomerService.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/customer/filters/RedirectWebFilter.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/customer/config/managements/ObservationConfig.kt`
- `src/main/resources/application.yml`

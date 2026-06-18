# Spring Cloud API Gateway Demo

[한국어](README.ko.md) | English

## What This Module Shows

`api-gateway` is the public Spring Cloud Gateway application for the gateway
workshop. It listens on `8080`, rewrites public service prefixes, exposes
Swagger UI, and applies a Redis-backed Bucket4j WebFlux rate-limit filter.

## Architecture

![API Gateway architecture](../../docs/images/readme-diagrams/gateway-api-gateway-readme-architecture-01.png)

The route table lives in `application.yml`:

| Route | Public path | Target |
|---|---|---|
| `customers` | `/customer-service/**` | `http://localhost:8081` with `RewritePath` |
| `orders` | `/order-service/**` | `http://localhost:8082` with `RewritePath` |
| `openapi` | `/v3/api-docs/**` | local gateway OpenAPI aggregation |
| `websocket-example` | `/echo` | `ws://localhost:9000` |

`RedirectWebFilter` rewrites `/` to `/swagger-ui.html`. The default gateway
filter adds `X-BLUETAPE4K-API: BLUETAPE4K.IO` to responses.

## Request Flow

![API Gateway request flow](../../docs/images/readme-diagrams/gateway-api-gateway-readme-sequence-01.png)

The normal service path is:

1. Client calls a public gateway prefix.
2. Bucket4j checks the Redis-backed bucket.
3. Spring Cloud Gateway matches the route and rewrites the path.
4. The downstream service receives its own `/api/v1/...` path.

## bluetape4k Usage

| Library | Usage |
|---|---|
| `bluetape4k-logging` | `KLoggingChannel()` for coroutine-aware component logging |
| `bluetape4k-bucket4j` | Bucket4j WebFlux rate-limit integration |
| `bluetape4k-cache-core` | Redis cache support used by the rate-limit configuration |
| `bluetape4k-resilience4j` | Available gateway resilience integration dependency |
| `bluetape4k-junit5` | `runSuspendIO { }` for suspend WebTestClient assertions |
| `bluetape4k-support` | `uninitialized()` and `unsafeLazy` in Spring beans |

## Run

Start the downstream services first, then start the gateway.

```bash
./gradlew :customers:bootRun
./gradlew :orders:bootRun
./gradlew :api-gateway:bootRun
```

Call the gateway:

```bash
http :8080/customer-service/api/v1/customers
http :8080/order-service/api/v1/orders
http :8080/order-service/api/v1/products
http :8080/swagger-ui.html
```

## Test

```bash
./gradlew :api-gateway:test
```

The current test scope verifies context loading and `/hello` responses through
`WebTestClient`.

## Source References

- `src/main/resources/application.yml`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/filter/RedirectWebFilter.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/config/managements/SwaggerConfig.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/controller/IndexController.kt`
- `ApiGateway.http`

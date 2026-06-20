# Spring Cloud API Gateway Demo

[English](README.md) | 한국어

## 이 모듈이 보여주는 것

`api-gateway`는 gateway workshop의 public Spring Cloud Gateway
애플리케이션입니다. `8080`에서 요청을 받고, public service prefix를
rewrite하며, Swagger UI를 노출하고, Redis 기반 Bucket4j WebFlux rate-limit
필터를 적용합니다.

## 아키텍처

![API Gateway architecture](../../docs/images/readme-diagrams/gateway-api-gateway-readme-architecture-01.png)

Route table은 `application.yml`에 있습니다.

| Route | Public path | Target |
|---|---|---|
| `customers` | `/customer-service/**` | `http://localhost:8081`, `RewritePath` 적용 |
| `orders` | `/order-service/**` | `http://localhost:8082`, `RewritePath` 적용 |
| `openapi` | `/v3/api-docs/**` | local gateway OpenAPI aggregation |
| `websocket-example` | `/echo` | `ws://localhost:9000` |

`RedirectWebFilter`는 `/`를 `/swagger-ui.html`로 rewrite합니다. 기본 Gateway
filter는 응답에 `X-BLUETAPE4K-API: BLUETAPE4K.IO` 헤더를 추가합니다.

## 요청 흐름

![API Gateway request sequence](../../docs/images/readme-diagrams/gateway-api-gateway-readme-sequence-01.png)

일반 service path는 다음 순서로 처리됩니다.

1. Client가 gateway의 public prefix를 호출합니다.
2. Bucket4j가 Redis-backed bucket을 확인합니다.
3. Spring Cloud Gateway가 route를 매칭하고 path를 rewrite합니다.
4. Downstream service는 자신의 `/api/v1/...` path로 요청을 받습니다.

## bluetape4k 사용 지점

| Library | Usage |
|---|---|
| `bluetape4k-logging` | `KLoggingChannel()` 기반 coroutine-aware component logging |
| `bluetape4k-bucket4j` | Bucket4j WebFlux rate-limit integration |
| `bluetape4k-cache-core` | Rate-limit 설정에서 사용하는 Redis cache support |
| `bluetape4k-resilience4j` | Gateway resilience integration 의존성 |
| `bluetape4k-junit5` | Suspend WebTestClient assertion을 위한 `runSuspendIO { }` |
| `bluetape4k-support` | Spring bean에서 사용하는 `uninitialized()`, `unsafeLazy` |

## 실행

Downstream 서비스를 먼저 실행한 뒤 gateway를 실행합니다.

```bash
./gradlew :customers:bootRun
./gradlew :orders:bootRun
./gradlew :api-gateway:bootRun
```

Gateway로 호출합니다.

```bash
http :8080/customer-service/api/v1/customers
http :8080/order-service/api/v1/orders
http :8080/order-service/api/v1/products
http :8080/swagger-ui.html
```

## 테스트

```bash
./gradlew :api-gateway:test
```

현재 테스트 범위는 `WebTestClient`를 통한 context loading과 `/hello` 응답
검증입니다.

## 소스 기준점

- `src/main/resources/application.yml`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/filter/RedirectWebFilter.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/config/managements/SwaggerConfig.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/controller/IndexController.kt`
- `ApiGateway.http`

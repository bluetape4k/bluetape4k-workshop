# Gateway Orders Service

[English](README.md) | 한국어

## 이 모듈이 보여주는 것

`orders`는 gateway workshop에서 사용하는 order backend입니다. Spring Boot
WebFlux 애플리케이션으로 `8082`에서 실행되며, 두 개의 suspend controller를
제공합니다.

- `GET /api/v1/orders`
- `GET /api/v1/products`

두 controller는 `Uuid.V7`로 샘플 UUID v7 식별자를 생성합니다.

## 아키텍처

![Gateway Orders Service architecture](../../docs/images/readme-diagrams/gateway-orders-readme-architecture-01.png)

이 서비스에는 database 의존성이 없습니다. In-memory 샘플 product와 order를
반환하므로, gateway 예제는 route forwarding과 path rewrite 동작에 집중할 수
있습니다.

## 런타임 계약

| Concern | Source-backed behavior |
|---|---|
| Orders API | `GET /api/v1/orders`가 Winter, Spring 샘플 주문 두 개를 반환 |
| Products API | `GET /api/v1/products`가 샘플 상품 두 개를 반환 |
| IDs | `Uuid.V7.nextIdAsString()`으로 샘플 order/product identifier 생성 |
| Swagger landing page | `RedirectWebFilter`가 `/`를 `/swagger-ui.html`로 rewrite |
| Observability | Actuator endpoint를 노출하고, Micrometer URI filter가 management/API-doc path를 제외 |
| AOT | `application.yml`에서 `spring.aot.enabled=true` |

## 실행

```bash
./gradlew :orders:bootRun
```

확인할 endpoint:

```bash
http :8082/api/v1/orders
http :8082/api/v1/products
http :8082/swagger-ui.html
http :8082/actuator
```

## bluetape4k 사용 지점

| Library | Usage |
|---|---|
| `bluetape4k-logging` | application, config, filter, controllers의 `KLoggingChannel()` |
| `bluetape4k-idgenerators` | Order와 product 샘플 ID를 위한 UUID v7 |
| `bluetape4k-support` | Swagger 설정의 `uninitialized()`, `unsafeLazy` |
| `bluetape4k-coroutines` | Suspend WebFlux controller endpoints |

## 소스 기준점

- `src/main/kotlin/io/bluetape4k/workshop/gateway/orders/controller/OrderController.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/orders/controller/ProductController.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/orders/filters/RedirectWebFilter.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/orders/config/managements/ObservationConfig.kt`
- `src/main/resources/application.yml`

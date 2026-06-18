# Gateway Demo

[English](README.md) | 한국어

## 이 예제가 보여주는 것

`gateway` 예제는 세 개의 Spring Boot 애플리케이션으로 작은 Spring Cloud
Gateway 시스템을 실행합니다.

- `api-gateway`는 `8080`에서 public path를 받아 로컬 서비스로 라우팅합니다.
- `customers`는 `8081`에서 `/api/v1/customers`를 제공합니다.
- `orders`는 `8082`에서 `/api/v1/orders`, `/api/v1/products`를 제공합니다.

Gateway는 Swagger UI도 노출하고, Redis bucket cache를 사용하는 Bucket4j
rate limit 필터를 적용합니다.

## 런타임 개요

![Gateway Demo architecture](../docs/images/readme-diagrams/gateway-readme-architecture-01.png)

요청은 downstream service port로 직접 들어가지 않고 gateway를 통해
들어갑니다. `application.yml`은 `/customer-service/**`를 customer service로,
`/order-service/**`를 order service로 매핑하고 `RewritePath`로 내부
controller의 일반 `/api/v1/...` path에 맞춥니다.

## 모듈

| Module | Port | Responsibility |
|---|---:|---|
| [`api-gateway`](api-gateway/README.ko.md) | `8080` | Spring Cloud Gateway routes, Swagger aggregation, Redis-backed Bucket4j filter |
| [`customers`](customers/README.ko.md) | `8081` | Customer WebFlux API |
| [`orders`](orders/README.ko.md) | `8082` | Order and product WebFlux APIs |

## 예제 실행

customer와 order 서비스를 먼저 실행한 뒤 gateway를 실행합니다.

```bash
./gradlew :customers:bootRun
./gradlew :orders:bootRun
./gradlew :api-gateway:bootRun
```

API는 gateway를 통해 호출합니다.

```bash
http :8080/customer-service/api/v1/customers
http :8080/order-service/api/v1/orders
http :8080/order-service/api/v1/products
http :8080/swagger-ui.html
```

## 소스 기준점

- `gateway/api-gateway/src/main/resources/application.yml`
- `gateway/customers/src/main/resources/application.yml`
- `gateway/orders/src/main/resources/application.yml`
- `gateway/api-gateway/ApiGateway.http`

## 참고 자료

- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway)
- [Spring Cloud Gateway: Implementing Routes in Microservices](https://medium.com/@AlexanderObregon/spring-cloud-gateway-implementing-routes-in-microservices-29094a0f8845)
- [Swagger Integration with Spring Cloud Gateway - Part 2](https://medium.com/@pubuduc.14/swagger-openapi-specification-3-integration-with-spring-cloud-gateway-part-2-1d670d4ab69a)

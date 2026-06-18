# Gateway Demo

[한국어](README.ko.md) | English

## What This Example Shows

The `gateway` examples run a small Spring Cloud Gateway system with three
Spring Boot applications:

- `api-gateway` listens on `8080` and routes public paths to local services.
- `customers` listens on `8081` and serves `/api/v1/customers`.
- `orders` listens on `8082` and serves `/api/v1/orders` and `/api/v1/products`.

The gateway also exposes Swagger UI and applies Bucket4j rate limiting with a
Redis-backed bucket cache.

## Runtime Overview

![Gateway Demo architecture](../docs/images/readme-diagrams/gateway-readme-architecture-01.png)

Requests enter through the gateway, not directly through the downstream service
ports. `application.yml` maps `/customer-service/**` to the customer service and
`/order-service/**` to the order service with `RewritePath`, so the downstream
controllers keep their normal `/api/v1/...` paths.

## Modules

| Module | Port | Responsibility |
|---|---:|---|
| [`api-gateway`](api-gateway/README.md) | `8080` | Spring Cloud Gateway routes, Swagger aggregation, Redis-backed Bucket4j filter |
| [`customers`](customers/README.md) | `8081` | Customer WebFlux API |
| [`orders`](orders/README.md) | `8082` | Order and product WebFlux APIs |

## Run The Example

Start the customer and order services first, then start the gateway.

```bash
./gradlew :customers:bootRun
./gradlew :orders:bootRun
./gradlew :api-gateway:bootRun
```

Call the APIs through the gateway:

```bash
http :8080/customer-service/api/v1/customers
http :8080/order-service/api/v1/orders
http :8080/order-service/api/v1/products
http :8080/swagger-ui.html
```

## Source References

- `gateway/api-gateway/src/main/resources/application.yml`
- `gateway/customers/src/main/resources/application.yml`
- `gateway/orders/src/main/resources/application.yml`
- `gateway/api-gateway/ApiGateway.http`

## References

- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway)
- [Spring Cloud Gateway: Implementing Routes in Microservices](https://medium.com/@AlexanderObregon/spring-cloud-gateway-implementing-routes-in-microservices-29094a0f8845)
- [Swagger Integration with Spring Cloud Gateway - Part 2](https://medium.com/@pubuduc.14/swagger-openapi-specification-3-integration-with-spring-cloud-gateway-part-2-1d670d4ab69a)

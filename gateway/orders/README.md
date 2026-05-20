# Gateway Orders Service

[English](README.md) | [한국어](README.ko.md)

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

## Source Map

- `OrderApplication.kt` starts the Spring Boot application.
- `ProductController.kt` returns sample products.
- `OrderController.kt` returns sample orders.
- `OrderConfig.kt`, `SwaggerConfig.kt`, and `ObservationConfig.kt` provide service metadata, OpenAPI, and observation support.
- `application.yml` sets `spring.application.name=Orders`, AOT, port `8082`, and management endpoint exposure.

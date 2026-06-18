# Gateway Customers Service

[English](README.md) | 한국어

## 이 모듈이 보여주는 것

`customers`는 gateway workshop에서 사용하는 customer backend입니다.
Spring Boot WebFlux 애플리케이션으로 `8081`에서 실행되며,
`GET /api/v1/customers` suspend controller를 제공합니다.

## 아키텍처

![Gateway Customers Service architecture](../../docs/images/readme-diagrams/gateway-customers-readme-architecture-01.png)

이 서비스에는 database 의존성이 없습니다. `CustomerContoller`는 `Winter`,
`Spring` 두 개의 샘플 `Customer` 값을 반환하므로, gateway 예제는 persistence
대신 routing 동작에 집중할 수 있습니다.

## 런타임 계약

| Concern | Source-backed behavior |
|---|---|
| HTTP API | `GET /api/v1/customers`가 customer JSON 배열을 반환 |
| Swagger landing page | `RedirectWebFilter`가 `/`를 `/swagger-ui.html`로 rewrite |
| Observability | Actuator endpoint를 노출하고, Micrometer URI filter가 management/API-doc path를 제외 |
| AOT | `application.yml`에서 `spring.aot.enabled=true` |

## 실행

```bash
./gradlew :customers:bootRun
```

확인할 endpoint:

```bash
http :8081/api/v1/customers
http :8081/swagger-ui.html
http :8081/actuator
```

## bluetape4k 사용 지점

| Library | Usage |
|---|---|
| `bluetape4k-logging` | application, config, filter, controller의 `KLoggingChannel()` |
| `bluetape4k-support` | Swagger 설정의 `uninitialized()`, `unsafeLazy` |
| `bluetape4k-coroutines` | Suspend WebFlux controller endpoint |

## 소스 기준점

- `src/main/kotlin/io/bluetape4k/workshop/gateway/customer/controller/CustomerContoller.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/customer/filters/RedirectWebFilter.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gateway/customer/config/managements/ObservationConfig.kt`
- `src/main/resources/application.yml`

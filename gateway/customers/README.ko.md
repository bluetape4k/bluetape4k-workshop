# Gateway Customers Service

[English](README.md) | [한국어](README.ko.md)

Gateway 워크숍의 고객 서비스 예제입니다. 작은 WebFlux API를 제공하고, 루트 경로를 Swagger UI로 리다이렉트하며, customer 백엔드로 `8081` 포트에서 실행됩니다.

## 아키텍처

![Gateway Customers architecture](../../docs/images/readme-diagrams/gateway-customers-diagram-01.png)

## 이 모듈에서 확인할 내용

- `GET /api/v1/customers` WebFlux 컨트롤러 엔드포인트.
- `CustomerContoller`가 반환하는 샘플 고객 데이터.
- `RedirectWebFilter`를 통한 `/`에서 `/swagger-ui.html`로의 리다이렉트.
- 로컬 워크숍 관찰성을 위한 Actuator 엔드포인트 노출.

## 실행

```bash
./gradlew :customers:bootRun
```

실행 후 다음 주소를 확인합니다.

- Swagger UI: `http://localhost:8081/swagger-ui.html`
- Customer API: `http://localhost:8081/api/v1/customers`
- Actuator endpoints: `http://localhost:8081/actuator`

## 사용된 Bluetape4k 기능

| 모듈 | 기능 | 사용 위치 |
|------|------|---------|
| `bluetape4k-logging` | `KLoggingChannel()` | 컨트롤러와 서비스의 코루틴 안전 구조화 로깅 |
| `bluetape4k-support` | `uninitialized()`, `unsafeLazy` | 주입 빈의 지연 필드 초기화 |
| `bluetape4k-junit5` | `runSuspendIO { }` | suspend 기반 통합 테스트 실행기 |

## 소스 맵

- `CustomerApplication.kt`는 Spring Boot 애플리케이션을 시작합니다.
- `CustomerContoller.kt`는 customer API를 정의합니다.
- `CustomerConfig.kt`, `SwaggerConfig.kt`, `ObservationConfig.kt`는 서비스 메타데이터, OpenAPI, 관찰성 설정을 제공합니다.
- `application.yml`은 `spring.application.name=Customers`, AOT, `8081` 포트, management endpoint 노출을 설정합니다.

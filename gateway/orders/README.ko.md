# Gateway Orders Service

[English](README.md) | [한국어](README.ko.md)

Gateway 워크숍의 주문 서비스 예제입니다. 상품과 주문 API를 제공하고, 루트 경로를 Swagger UI로 리다이렉트하며, order 백엔드로 `8082` 포트에서 실행됩니다.

## 아키텍처

![Gateway Orders architecture](../../docs/images/readme-diagrams/gateway-orders-diagram-01.png)

## 이 모듈에서 확인할 내용

- `GET /api/v1/products` WebFlux 컨트롤러 엔드포인트.
- `GET /api/v1/orders` WebFlux 컨트롤러 엔드포인트.
- 샘플 상품과 주문에 사용하는 UUID v7 ID 생성.
- `/`에서 `/swagger-ui.html`로의 루트 경로 리다이렉트.
- 로컬 워크숍 관찰성을 위한 Actuator 엔드포인트 노출.

## 실행

```bash
./gradlew :orders:bootRun
```

실행 후 다음 주소를 확인합니다.

- Swagger UI: `http://localhost:8082/swagger-ui.html`
- Products API: `http://localhost:8082/api/v1/products`
- Orders API: `http://localhost:8082/api/v1/orders`
- Actuator endpoints: `http://localhost:8082/actuator`

## 사용된 Bluetape4k 기능

| 모듈 | 기능 | 사용 위치 |
|------|------|---------|
| `bluetape4k-logging` | `KLoggingChannel()`, `KLogging()` | 컨트롤러와 설정의 코루틴 안전 구조화 로깅 |
| `bluetape4k-idgenerators` | `Uuid` (UUID v7) | 상품과 주문 ID 생성 (타입 안전 UUID v7) |
| `bluetape4k-support` | `uninitialized()`, `unsafeLazy` | 주입 빈의 지연 필드 초기화 |
| `bluetape4k-assertions` | `shouldBeFalse` | 간결한 테스트 단언문 |
| `bluetape4k-junit5` | `runSuspendIO { }` | suspend 기반 통합 테스트 실행기 |

## 소스 맵

- `OrderApplication.kt`는 Spring Boot 애플리케이션을 시작합니다.
- `ProductController.kt`는 샘플 상품을 반환합니다.
- `OrderController.kt`는 샘플 주문을 반환합니다.
- `OrderConfig.kt`, `SwaggerConfig.kt`, `ObservationConfig.kt`는 서비스 메타데이터, OpenAPI, 관찰성 설정을 제공합니다.
- `application.yml`은 `spring.application.name=Orders`, AOT, `8082` 포트, management endpoint 노출을 설정합니다.

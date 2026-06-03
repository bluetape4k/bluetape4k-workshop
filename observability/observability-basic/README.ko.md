# observability-basic

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **observability-basic** 모듈을 실행 가능한 메트릭, 트레이싱, 관측 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 시퀀스 다이어그램

WebFlux HTTP 엔드포인트, 코루틴 서비스, 아웃바운드 WebClient 호출에 걸쳐
Micrometer Observation + W3C 트레이스 전파를 보여주는 최소 Observability 워크샵 예제.

인프라(DB, Redis, Kafka) 불필요. 다운스트림 시뮬레이션에 MockWebServer 사용.

## 아키텍처

![observability-basic Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/observability-observability-basic-readme-architecture-01.png)

## 스팬 트리

```
http.server.requests            (auto — Spring Boot)
  └─ order.service.fetch        (manual — observed())
       └─ http.client.requests  (auto — Micrometer WebClient)
            └─ downstream inventory service
```

## 핵심 개념

| 개념 | 구현 |
|------|------|
| 수동 스팬 | `observed("order.service.fetch", registry) { }` |
| W3C traceparent 전파 | Spring Boot `WebClient.Builder` 빈을 통해 자동 전파 |
| 테스트 어설션 | `TestObservationRegistry` (Zipkin 불필요) |
| 4xx 처리 | `onStatus(4xx) { Mono.empty() }` → null 반환 |
| 5xx 처리 | `onStatus(5xx) { resp.createException() }` → 예외 전파 |

## 테스트 커버리지

- `OrderServiceTest`: 스팬 생명주기(시작/중지), 에러 기록, 취소 안전성
- `OrderControllerTest`: HTTP 200 통합 테스트, W3C traceparent 헤더 전파

## 설정

```yaml
workshop:
  observability:
    inventory:
      base-url: http://localhost:8080

management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      export:
        enabled: false  # set true to export to OTel collector
```

## 의존성

- `bluetape4k-micrometer` — `observed()` 코루틴 헬퍼 (stop-safe wrapper; ObservationSupport.kt 참조)
- `micrometer-tracing-bridge-otel` — W3C 전파를 위한 OTel 브리지
- `micrometer-context-propagation` — reactor ↔ 코루틴 컨텍스트 브리징
- `spring-boot-starter-opentelemetry` — WebClient 계측 자동 구성

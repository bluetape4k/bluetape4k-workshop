# Spring Cloud API Gateway Demo

Spring Webflux 기반의 API Gateway 에 대한 예제입니다.

## 아키텍처 다이어그램

```mermaid
flowchart LR
    subgraph 클라이언트
        C[HTTP 클라이언트]
    end

    subgraph API Gateway :8080
        GW[ApiGatewayDemoApplication\nSpring WebFlux]
        RF[RedirectWebFilter]
        SW[Swagger UI 통합\n/swagger-ui.html]
        RL[Rate Limiter\nBucket4j 토큰 기반]
    end

    subgraph 백엔드 서비스
        CS[CustomerService\n:8081\n/customers]
        OS[OrderService\n:8082\n/orders\n/products]
    end

    C -->|HTTP 요청| RF
    RF --> GW
    GW --> RL
    RL -->|라우팅| CS
    RL -->|라우팅| OS
    GW --> SW
    SW -->|API 문서 집계| CS
    SW -->|API 문서 집계| OS
```

API Gateway 가 Customer Service, Order Service 에 대한
다음과 같은 기능을 제공합니다.

1. Routing 기능
2. Swagger 통합 (API Gateway 에서 Customer, Order API에 대한 Swagger UI 를 통합 제공)
3. Bucket4j 이용한 Token 기반의 Rate limiter 를 적용하는 예를 제공합니다.

## 실행

## 설정

## 참고

- [Rate Limiting a Spring API Using Bucket4j](https://www.baeldung.com/spring-bucket4j)
- 

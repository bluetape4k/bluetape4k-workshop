# Gateway Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Gateway Demo**를 실행 가능한 게이트웨이와 다운스트림 서비스 조정 워크샵 조각으로 다룹니다. 개발자가 가장 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리/프레임워크 API 관찰에 초점을 둡니다.

![Gateway Demo scenario diagram](../docs/images/readme-diagrams/gateway-api-gateway-scenario-01.png)

## 아키텍처 다이어그램

![Gateway Demo architecture diagram](../docs/images/readme-diagrams/gateway-api-gateway-diagram-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README를 코드와 비교할 때는 `io.bluetape4k.workshop.gateway` 패키지를 기준으로 삼습니다.

![Gateway Demo Graphviz architecture diagram](../docs/images/readme-diagrams/gateway-readme-architecture-01.png)

## 흐름 다이어그램

1. `Gateway Demo`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, 트레이스 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여 줍니다. 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

Spring Cloud API Gateway를 사용해 내부 서비스인 Customer API와 Order API를 API Gateway를 통해 서비스하는 예제입니다.

## 아키텍처 다이어그램

![gateway Architecture diagram](../docs/images/readme-diagrams/gateway-diagram-01.png)

-. API Gateway : http://localhost:8080
-. Customer API: http://localhost:8081/customers
-. Order API : http://localhost:8082/orders

## 사용 방법

customer(8081)와 order(8082) 서비스를 먼저 실행한 뒤 gateway-demo(8080)를 실행합니다.

`httpie`를 사용해 API Gateway를 통해 Customer API와 Order API를 호출합니다.

### 1. Customer API 사용

```bash
$ http://localhost:8080/customer-api/customers
```

### 2. Order, Product API 실행

```bash
$ http localhost:8080/order-api/orders
```

```bash
$ http localhost:8080/product-api/products
```

## 참고 자료

- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway)
- [Spring Cloud Gateway: Implementing Routes in Microservices](https://medium.com/@AlexanderObregon/spring-cloud-gateway-implementing-routes-in-microservices-29094a0f8845)
- [Swagger Integration with Spring Cloud Gateway - Part 2] (https://medium.com/@pubuduc.14/swagger-openapi-specification-3-integration-with-spring-cloud-gateway-part-2-1d670d4ab69a)
- [API Gateway Service - Spring Cloud Gateway - Add Filter](https://kingchan223.tistory.com/398)

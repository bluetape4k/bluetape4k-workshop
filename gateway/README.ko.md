# Gateway Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Gateway Demo**를 실행 가능한 게이트웨이와 다운스트림 서비스 조정 워크샵 조각으로 다룹니다. 개발자가 가장 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리/프레임워크 API 관찰에 초점을 둡니다.

![Gateway Demo scenario diagram](../docs/images/readme-diagrams/gateway-api-gateway-scenario-01.png)

## 아키텍처 다이어그램

![Gateway Demo Graphviz 아키텍처 다이어그램](../docs/images/readme-diagrams/gateway-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README를 코드와 비교할 때는 `io.bluetape4k.workshop.gateway` 패키지를 기준으로 삼습니다.

## 시퀀스 다이어그램

Spring Cloud API Gateway를 사용해 내부 서비스인 Customer API와 Order API를 API Gateway를 통해 서비스하는 예제입니다.

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

# Gateway Demo

Spring Cloud API Gateway 를 사용하여 내부 서비스인 Customer API, Order API 를 API Gateway 를 통해 서비스하는 예제입니다.

-. API Gateway : http://localhost:8080
-. Customer API: http://localhost:8081/customers
-. Order API : http://localhost:8082/orders

## 사용법

customer (8081), order (8082) 서비스를 먼저 실행하고, gateway-demo (8080) 를 실행합니다.

`httpie` 를 사용하여 API Gateway 를 통해 Customer API, Order API 를 호출합니다.

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

## 참고

- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway)
- [Spring Cloud Gateway: Implementing Routes in Microservices](https://medium.com/@AlexanderObregon/spring-cloud-gateway-implementing-routes-in-microservices-29094a0f8845)
- [Swagger Integration with Spring Cloud Gateway - Part 2] (https://medium.com/@pubuduc.14/swagger-openapi-specification-3-integration-with-spring-cloud-gateway-part-2-1d670d4ab69a)
- [API Gateway Service - Spring Cloud Gateway - Filter추가하기](https://kingchan223.tistory.com/398)

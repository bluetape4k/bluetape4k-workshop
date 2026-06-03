# Exposed Examples

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Exposed Examples** 모듈을 실행 가능한 Exposed 데이터 접근 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 아키텍처 다이어그램

![Exposed Examples Graphviz 아키텍처 다이어그램](../docs/images/readme-diagrams/exposed-readme-architecture-01.png)

모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.exposed` 패키지 아래의 구현을 기준으로 삼습니다.

## 시퀀스 다이어그램

![Exposed Examples 시퀀스 다이어그램](../docs/images/readme-diagrams/exposed-dao-web-transaction-sequence-01.png)

Spring Boot와 [JetBrains Exposed](https://github.com/JetBrains/Exposed) ORM을 사용하는 운영 스타일 예제입니다.

## 하위 모듈

| 모듈 | 스택 | 트랜잭션 전략 |
|--------|-------|-------------|
| [mvc-jdbc](./mvc-jdbc/) | Spring MVC + Exposed JDBC | `@Transactional` (Spring declarative) |
| [mvc-virtualthread](./mvc-virtualthread/) | Spring MVC + Virtual Threads + Exposed JDBC | `virtualFuture(executor){ transaction(db){} }` |
| [webflux-r2dbc](./webflux-r2dbc/) | WebFlux + Coroutines + Exposed R2DBC | `suspendTransaction(db=db){ }` |

## 도메인

세 모듈은 모두 같은 Author/Book/Product/Order 도메인을 전체 CRUD와 함께 구현합니다. 또한 lock ordering과 재고 차감을 보여주는 동시 `placeOrder` use case를 포함합니다.

```
Author ──< Book
Product
Order ──< OrderLine ──> Product
```

## 보여주는 핵심 패턴

- **mvc-jdbc**: Spring declarative `@Transactional`, SELECT FOR UPDATE, rollback verification
- **mvc-virtualthread**: `virtualFuture` VT executor pattern, `ExecutionException` unwrapping, no `@Transactional`
- **webflux-r2dbc**: `Flow<T>` repositories, `suspendTransaction{}`, concurrent order test with coroutines

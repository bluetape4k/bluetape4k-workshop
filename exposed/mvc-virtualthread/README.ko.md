# exposed/mvc-virtualthread

[English](README.md) | 한국어

`exposed/mvc-virtualthread`는 Spring MVC와 Exposed JDBC를 조합하고, 블로킹 데이터베이스 작업을 Java virtual thread에서 실행하는 예제입니다.

이 모듈의 핵심은 Spring `@Transactional`을 사용하지 않는다는 점입니다. Tomcat, service, repository가 `Executors.newVirtualThreadPerTaskExecutor()`로 만든 `ExecutorService`를 공유하고, 데이터베이스 작업은 `virtualFuture(executor) { transaction(db) { ... } }`로 명시적으로 감쌉니다.

## 아키텍처

![exposed/mvc-virtualthread architecture](../../docs/images/readme-diagrams/exposed-mvc-virtualthread-readme-architecture-01.png)

| 영역 | 구현 | 독자가 확인할 계약 |
|---|---|---|
| MVC request 실행 | `TomcatConfig`가 공유 executor를 Tomcat protocol handler에 지정합니다. | 블로킹 MVC handler가 platform thread에 고정되지 않고 실행됩니다. |
| Executor lifecycle | `virtualThreadExecutor()`가 per-task virtual-thread executor를 만들고 `ShutdownQueue`에 등록합니다. | 모듈은 하나의 공유 VT executor를 소유하고 일관되게 종료합니다. |
| Repository 호출 | `AuthorRepository`, `BookRepository`, `ProductRepository`, `OrderRepository`, `OrderLineRepository`가 `VirtualFuture<T>`를 반환합니다. | 단순 CRUD 메서드는 virtual-thread task 안에서 각자 `transaction(db)`를 엽니다. |
| Order placement | `OrderService.placeOrder()`가 주문 전체 transaction을 소유합니다. | stock lock, order-line write, stock decrement가 하나의 명시적 transaction에서 수행됩니다. |
| Error handling | `GlobalExceptionHandler`가 `ExecutionException`, `CompletionException`을 unwrap합니다. | `Future.get()` 내부 실패도 의미 있는 HTTP 응답으로 변환됩니다. |

## 주문 처리 흐름

![exposed/mvc-virtualthread order placement sequence](../../docs/images/readme-diagrams/exposed-mvc-virtualthread-readme-sequence-01.png)

`OrderService.placeOrder()`는 하나의 `virtualFuture` task를 제출하고 그 안에서 하나의 Exposed transaction을 엽니다. service는 요청 라인을 `productId` 기준으로 정렬하고, 각 product row를 `SELECT ... FOR UPDATE`로 잠근 뒤 order line을 쓰고 `products.stock`을 감소시킵니다.

재고가 부족하면 service는 transaction 내부에서 `InsufficientStockException`을 던집니다. `Future.get()`은 이 실패를 감싸지만, `GlobalExceptionHandler`가 다시 unwrap하므로 MVC 계층은 generic execution error가 아니라 stock-conflict 응답을 반환합니다.

## 스키마

![exposed/mvc-virtualthread schema ERD](../../docs/images/readme-diagrams/exposed-mvc-virtualthread-readme-erd-01.png)

| 테이블 | 역할 |
|---|---|
| `authors`, `books` | Author/book CRUD는 plain Exposed `Table` 정의와 repository-level transaction을 사용합니다. |
| `products` | Product stock은 row lock으로 보호되는 concurrency-sensitive 값입니다. |
| `orders`, `order_lines` | 주문 처리는 header와 line row를 쓰고 같은 transaction에서 stock을 감소시킵니다. |

## 주요 코드 경로

| 파일 | 확인할 내용 |
|---|---|
| `config/TomcatConfig.kt` | 공유 virtual-thread executor와 Tomcat protocol-handler customization. |
| `config/DatabaseInitializer.kt` | VT executor를 통한 schema creation과 seed data. |
| `repository/*Repository.kt` | 명시적 `transaction(db)`를 사용하는 `VirtualFuture<T>` repository method. |
| `service/OrderService.kt` | Stock locking, rollback behavior, `Future.get()` boundary. |
| `config/GlobalExceptionHandler.kt` | MVC response를 위한 virtual-future failure unwrapping. |
| `domain/*Table.kt` | ERD에 표시한 plain Exposed table definitions. |

## 실행

애플리케이션은 `jdbc:postgresql://localhost:5432/exposedmvcvt`의 PostgreSQL과 `postgres/postgres` 계정을 기대합니다.

```bash
./gradlew :exposed-mvc-virtualthread:bootRun
# http://localhost:8081/swagger-ui/index.html
```

## 테스트

```bash
./gradlew :exposed-mvc-virtualthread:test
```

| 테스트 클래스 | 범위 |
|---|---|
| `AuthorControllerTest` | Author, book CRUD endpoint. |
| `ProductControllerTest` | Product CRUD endpoint. |
| `OrderControllerTest` | Order placement, cancellation, 404, stock-conflict case. |
| `PlaceOrderRollbackTest` | Stock 부족 시 rollback. |
| `ConcurrentPlaceOrderTest` | 마지막 stock item을 여러 virtual-thread request가 동시에 소비하려 할 때 하나만 성공하는지 확인합니다. |

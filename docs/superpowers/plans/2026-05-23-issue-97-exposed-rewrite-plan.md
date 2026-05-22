# Issue #97 — Exposed 예제 전면 재작성 구현 플랜

**Date**: 2026-05-23
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/97
**Spec**: `docs/superpowers/specs/2026-05-23-issue-97-exposed-rewrite-design.md`
**Branch**: `feat/issue-97-exposed-rewrite`

---

## 전제 조건

- Worktree: `.worktrees/feat/issue-97-exposed-rewrite/`
- 스펙 리뷰 완료 (Step 2-R): P0=0, P1=0
- PostgreSQL Testcontainers 싱글턴 패턴 사용 (`PostgreSQLServer.Launcher.postgres`)
- `includeModules("exposed", false, true)` — settings.gradle.kts 변경 불필요

---

## 페이즈 0 — 기존 모듈 삭제 및 프로젝트 정리

### T0-1. 5개 기존 모듈 삭제 (complexity: low)

삭제 대상:
- `exposed/domain/`
- `exposed/dao-web-transaction/`
- `exposed/spring-transaction/`
- `exposed/sql-web-virtualthread/`
- `exposed/sql-webflux-coroutines/`

- [ ] `rm -rf exposed/domain exposed/dao-web-transaction exposed/spring-transaction exposed/sql-web-virtualthread exposed/sql-webflux-coroutines`
- [ ] `./gradlew projects` 실행 → 삭제된 모듈이 없는지 확인 (includeModules 자동 등록 해제 확인)

### T0-2. `exposed/README.md` 골격 업데이트 (complexity: low)

- [ ] 5개 구 모듈 표 삭제
- [ ] 3개 신규 모듈 소개 표 추가 (module selection guide 포함)
- [ ] 학습자 결정 트리 (§4-0) 반영

### T0-3. smoke-validate.sh 모듈명 교체 (complexity: low)

- [ ] `scripts/smoke-validate.sh` — `data-access-full` 그룹: 5개 구 모듈명 → `exposed-mvc-jdbc`, `exposed-mvc-virtualthread`, `exposed-webflux-r2dbc` 교체
- [ ] `docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md` T3 Full 표 업데이트

---

## 페이즈 1 — `exposed/mvc-jdbc` 구현

> 스택: Spring MVC + Exposed JDBC + `@Transactional` 선언적 TX
> 패키지: `io.bluetape4k.workshop.exposed.mvc.jdbc`

### T1-1. `build.gradle.kts` 생성 (complexity: medium)

- [ ] 스펙 §6 `exposed/mvc-jdbc/build.gradle.kts` 그대로 작성
  - `alias(libs.plugins.kotlin.spring)` + `alias(libs.plugins.spring.boot)`
  - `springBoot { mainClass.set("...ExposedMvcJdbcAppKt") }`
  - `testImplementation.extendsFrom(compileOnly, runtimeOnly)`
  - `springdoc-openapi-starter-webmvc-ui` (not webflux)
  - `developmentOnly(libs.spring.boot.devtools)` (not runtimeOnly)
  - `spring.boot.starter.validation` 포함

### T1-2. 스키마 정의 (complexity: medium)

파일: `author/schema/AuthorTable.kt`, `BookTable.kt`
파일: `order/schema/OrderTable.kt`, `OrderLineTable.kt`, `ProductTable.kt`, `OrderStatus.kt`

- [ ] `AuthorTable`: id(Long autoIncrement), firstName, lastName, email(unique)
- [ ] `BookTable`: id, title, publishDate, authorId(FK → AuthorTable)
- [ ] `ProductTable`: id, name, price(Decimal(12,2)), stock(Int)
- [ ] `OrderTable`: id, customerId(Long), orderDate, status(Enum — `OrderStatus`)
- [ ] `OrderLineTable`: id, orderId(FK), productId(FK), quantity, unitPrice(Decimal — snapshot)
- [ ] `OrderStatus` enum: `PENDING`, `PAID`, `CANCELLED`

### T1-3. DTO + Mapper 정의 (complexity: medium)

Author 도메인:
- [ ] `AuthorDTO`, `BookDTO`, `AuthorWithBooksDTO`
- [ ] `AuthorMappers.kt` (ResultRow → DTO extension functions)

Order 도메인:
- [ ] `ProductDTO`, `OrderDTO`, `OrderLineDTO`, `OrderWithLinesDTO`
- [ ] `PlaceOrderRequest(customerId: Long, lines: List<OrderLineRequest>) : Serializable` — `@field:Positive`, `@field:Valid @field:NotEmpty`
- [ ] `OrderLineRequest(productId: Long, quantity: Int) : Serializable` — `@field:Positive`, `@field:Min(1)`
- [ ] `OrderMappers.kt`

### T1-4. Repository 계층 (complexity: medium)

Author:
- [ ] `AuthorRepository`: `findAll()`, `findById()`, `insert()`, `deleteById()`
- [ ] `BookRepository`: `findAll()`, `findByAuthorId()`, `findById()`, `insert()`

Order:
- [ ] `ProductRepository`: `findAll()`, `findById()`, `findByIdForUpdate()` (`.forUpdate()`), `insert()`, `decrementStock()`
- [ ] `OrderRepository`: `findAll()`, `findById()`, `insert()`
- [ ] `OrderLineRepository`: `findByOrderId()`, `insert()`

**Repository에는 `@Transactional` 없음** — TX는 Service 계층에서만.

### T1-5. Service 계층 (complexity: high)

- [ ] `AuthorService`: `@Transactional(readOnly=true)` findAll/findById, `@Transactional` insert/delete
- [ ] `OrderService`:
  - `@Transactional fun placeOrder(req: PlaceOrderRequest): OrderDTO`
    - `orderRepo.insert(req)` → orderId 획득
    - 각 line: `productRepo.findByIdForUpdate(productId)` — **SELECT FOR UPDATE** (TOCTOU 방지)
    - `require(stock >= quantity)` 위반 시 `InsufficientStockException(productId)` (→ HTTP 409)
    - `orderLineRepo.insert(orderId, line, product.price)` (unitPrice snapshot)
    - `productRepo.decrementStock(productId, quantity)`
  - `@Transactional fun cancelOrder(id: Long): OrderDTO`
  - `@Transactional(readOnly=true) fun findAll()`, `findById()`

### T1-6. Controller 계층 (complexity: medium)

- [ ] `AuthorController`: GET `/api/v1/authors`, POST `/api/v1/authors`, GET `/api/v1/authors/{id}/books`
- [ ] `BookController`: GET `/api/v1/books`, POST `/api/v1/books`
- [ ] `ProductController`: GET `/api/v1/products`, POST `/api/v1/products`
- [ ] `OrderController`:
  - `POST /api/v1/orders` — **`@Valid @RequestBody PlaceOrderRequest` 필수** (없으면 DTO validation 미작동)
  - `GET /api/v1/orders/{id}`, `GET /api/v1/orders/{id}/lines`, `GET /api/v1/orders/{id}/total`
  - `PATCH /api/v1/orders/{id}/cancel`

### T1-7. Support 인프라 (complexity: medium)

- [ ] `DatabaseConfig.kt`: `DataSource` 빈 (HikariCP), `Database.connect(dataSource)` Exposed 등록
- [ ] `DatabaseInitializer.kt`: `ApplicationRunner` — `SchemaUtils.create(*tables)` + 초기 seed 데이터 (Products 5개, Authors 3명, Books 5권)
- [ ] `GlobalExceptionHandler.kt`:
  ```
  NoSuchElementException → 404
  IllegalArgumentException → 400
  InsufficientStockException → 409
  ConstraintViolationException → 400
  MethodArgumentNotValidException → 400  (P1-12: @Valid @RequestBody 에러)
  Exception → 500
  ```
  `ErrorResponse(status: Int, code: String, message: String)` envelope
- [ ] `SwaggerConfig.kt`: springdoc OpenAPI 설정
- [ ] `application.yml`: Testcontainers PostgreSQL TC 주소 또는 `@DynamicPropertySource`
- [ ] `ExposedMvcJdbcApp.kt`

### T1-8. 테스트 (complexity: high)

- [ ] `AbstractMvcJdbcTest.kt`: `@SpringBootTest(webEnvironment=RANDOM_PORT)`, `WebTestClient`, `@Transactional` rollback per test, `PostgreSQLServer.Launcher.postgres` 싱글턴
- [ ] `AuthorControllerTest.kt`: CRUD 검증
- [ ] `BookControllerTest.kt`: CRUD + 저자별 조회 검증
- [ ] `AuthorRepositoryTest.kt`: Repository 단위 검증
- [ ] `ProductControllerTest.kt`: CRUD 검증
- [ ] `OrderControllerTest.kt`: placeOrder 성공/실패 검증
- [ ] `OrderRepositoryTest.kt`: Repository 단위 검증
- [ ] `PlaceOrderRollbackTest.kt`: 중간 실패(존재하지 않는 productId) → 전체 롤백 검증 (주문 레코드 없음 확인)
- [ ] `ConcurrentPlaceOrderTest.kt`: **TOCTOU 검증** — stock=1 상품, N개 병렬 주문 → 정확히 1건 성공 + 나머지 409 Conflict
- [ ] `test/resources/junit-platform.properties`: `junit.jupiter.execution.parallel.enabled=false` (TestMutexService)
- [ ] `test/resources/logback-test.xml`

---

## 페이즈 2 — `exposed/mvc-virtualthread` 구현

> 스택: Spring MVC + Virtual Threads + Exposed JDBC (manual TX)
> 패키지: `io.bluetape4k.workshop.exposed.mvc.vt`
> **`@Transactional` 금지** — `virtualFuture(executor) { transaction(db) {} }` 패턴만 사용

### T2-1. `build.gradle.kts` 생성 (complexity: medium)

- [ ] `mvc-jdbc` 기반 + 차이점만 반영:
  - `implementation(libs.bluetape4k.virtualthread.jdk21)` 추가
  - `jetbrains-exposed-spring-boot4-starter` 제거 (수동 DB 연결)
  - `jetbrains-exposed-spring7-transaction` 제거
  - `spring-boot.starter.validation` 포함
  - `springdoc-openapi-starter-webmvc-ui` 유지

### T2-2. 스키마 정의 (complexity: low)

- [ ] `mvc-jdbc`와 동일 테이블 정의 복제 (D1: self-contained)

### T2-3. DTO + Mapper (complexity: low)

- [ ] `mvc-jdbc`와 동일 DTO 복제 (패키지만 `mvc.vt`로 변경)

### T2-4. Repository 계층 (complexity: high)

**핵심**: 각 Repository 메서드가 `virtualFuture(executor) { transaction(db) {} }` 로 래핑.

- [ ] `companion object : KLogging()` — **KLogging 사용** (비코루틴 컨텍스트, P1-3 fix)
- [ ] `private val executor = Executors.newVirtualThreadPerTaskExecutor().apply { ShutdownQueue.register(this) }`
- [ ] `AuthorRepository`, `BookRepository`, `ProductRepository`, `OrderRepository`, `OrderLineRepository`
  - 단순 단일 테이블 CRUD만 (`placeOrder` 비즈니스 로직 없음 — P1-2 fix: Service로)
  - `fun findById(id: Long): VirtualFuture<T?> = virtualFuture(executor) { transaction(db) { ... } }`
  - `ProductRepository.findByIdForUpdate()`: `.forUpdate()` 포함

### T2-5. Service 계층 (complexity: high)

- [ ] `AuthorService`: VirtualFuture + transaction(db) 패턴
- [ ] `OrderService`:
  - `companion object : KLogging()` + `private val executor`
  - `fun placeOrder(req: PlaceOrderRequest): VirtualFuture<OrderDTO> = virtualFuture(executor) { transaction(db) { ... } }`
    - `OrderTable.insertAndGetId {}` → orderId
    - 각 line: `ProductTable.selectAll().where { id eq productId }.forUpdate().single()` — SELECT FOR UPDATE
    - stock 검증 → `InsufficientStockException` (→ 409)
    - `OrderLineTable.insert { ... }`
    - `ProductTable.update { ... }` (SqlExpressionBuilder: `it[stock] = stock - quantity`)
    - 결과 `OrderTable.selectAll().where { id eq orderId }.single().toOrderDTO()`

### T2-6. Controller 계층 (complexity: medium)

- [ ] `.await()` 패턴: `val result = service.foo(...).await()`
- [ ] **POST handler에 `@Valid @RequestBody` 필수** (P1-A fix)
- [ ] `AuthorController`, `BookController`, `ProductController`, `OrderController` — 동일 엔드포인트

### T2-7. Support 인프라 (complexity: medium)

- [ ] `DatabaseConfig.kt`: `Database.connect(dataSource)` (HikariCP, spring 자동설정 활용)
- [ ] `TomcatConfig.kt`: `TomcatProtocolHandlerCustomizer<*>` — Tomcat VT executor 설정
- [ ] `DatabaseInitializer.kt`: `ApplicationRunner` + `virtualFuture(executor) { transaction(db) { SchemaUtils.create(...) + seed } }.await()`
- [ ] `GlobalExceptionHandler.kt`: `mvc-jdbc`와 동일 (MVC 스택)
- [ ] `SwaggerConfig.kt`
- [ ] `application.yml`
- [ ] `ExposedMvcVirtualThreadApp.kt`

### T2-8. 테스트 (complexity: high)

- [ ] `AbstractMvcVirtualThreadTest.kt`: `@BeforeEach` — `truncateAll()` + re-seed (VT 환경에서 `@Transactional` rollback 불가)
- [ ] `AuthorControllerTest.kt`, `BookControllerTest.kt`
- [ ] `ProductControllerTest.kt`, `OrderControllerTest.kt`
- [ ] `AuthorRepositoryTest.kt`, `OrderRepositoryTest.kt`
- [ ] `PlaceOrderRollbackTest.kt`: 실패 시 주문 레코드 부재 확인
- [ ] `ConcurrentPlaceOrderTest.kt`: **TOCTOU 검증** (같은 시나리오: stock=1, N병렬 → 1 success + N-1 409)
- [ ] `test/resources/junit-platform.properties`, `logback-test.xml`

---

## 페이즈 3 — `exposed/webflux-r2dbc` 구현

> 스택: WebFlux + Coroutines + Exposed R2DBC
> 패키지: `io.bluetape4k.workshop.exposed.webflux.r2dbc`
> TX 모델: `suspendTransaction(db = db) {}`

### T3-1. `build.gradle.kts` 생성 (complexity: medium)

- [ ] 스펙 §6 `exposed/webflux-r2dbc/build.gradle.kts` 반영:
  - `exposed.r2dbc` (bluetape4k), `jetbrains.exposed.r2dbc`
  - `r2dbc.pool`, `r2dbc.postgresql`
  - `bluetape4k.coroutines`, `kotlinx.coroutines.core.lib`, `kotlinx.coroutines.reactor`
  - `reactor.netty`, `reactor.kotlin.extensions`
  - `spring.boot.starter.webflux.lib` (not webmvc)
  - `springdoc-openapi-starter-webflux-ui`
  - `spring.boot.starter.validation`
  - webmvc + Exposed JDBC starter + spring7-transaction 제거

### T3-2. 스키마 정의 (complexity: low)

- [ ] 동일 테이블 정의 (R2DBC 임포트: `org.jetbrains.exposed.v1.r2dbc.*`)
- [ ] R2DBC 전용: `import org.jetbrains.exposed.v1.r2dbc.*` (JDBC `import` 사용 금지)

### T3-3. DTO + Mapper (complexity: low)

- [ ] `mvc-jdbc`와 동일 DTO 복제 (패키지만 `webflux.r2dbc`로)

### T3-4. Repository 계층 (complexity: high)

**핵심**: `suspend fun` for scalars, `fun findAll(): Flow<T>` for lists — **TX 없음** (service에서 `suspendTransaction` 안에서 collect).

- [ ] `AuthorRepository`:
  - `suspend fun findAll(): Flow<AuthorDTO>`
  - `suspend fun findById(id: Long): AuthorDTO?`
  - `suspend fun insert(dto: ...): AuthorDTO`
- [ ] `BookRepository` 동일 패턴
- [ ] `ProductRepository`:
  - `fun findAll(): Flow<ProductDTO>`, `suspend fun findById(id: Long): ProductDTO?`
  - `suspend fun findByIdForUpdate(id: Long): ProductDTO?` — `.forUpdate()` (R2DBC)
  - `suspend fun decrementStock(id: Long, quantity: Int)`
  - `suspend fun insert(...): ProductDTO`
- [ ] `OrderRepository`:
  - `fun findAll(): Flow<OrderDTO>`, `suspend fun findById(id: Long): OrderDTO?`
  - `suspend fun insert(req: PlaceOrderRequest): OrderDTO`
- [ ] `OrderLineRepository`:
  - `fun findByOrderId(orderId: Long): Flow<OrderLineDTO>`
  - `suspend fun insert(orderId: Long, line: OrderLineRequest, unitPrice: BigDecimal)`

### T3-5. Service 계층 (complexity: high)

**D11 핵심**: `findAll()` 반환 타입은 `List<T>` (not `Flow<T>`) — `suspendTransaction {}` 안에서 `toList()` collect.

- [ ] `AuthorService`:
  - `suspend fun findAll(): List<AuthorDTO> = suspendTransaction(db = db) { authorRepo.findAll().toList() }`
  - `suspend fun findById(id: Long): AuthorDTO? = suspendTransaction(db = db) { authorRepo.findById(id) }`
  - `suspend fun insert(...): AuthorDTO = suspendTransaction(db = db) { authorRepo.insert(...) }`
- [ ] `OrderService`:
  - `suspend fun findAll(): List<OrderDTO> = suspendTransaction(db = db) { orderRepo.findAll().toList() }`
  - `suspend fun findById(id: Long): OrderWithLinesDTO? = suspendTransaction(db = db) { ... orderLineRepo.findByOrderId(id).toList() ... }`
  - `suspend fun placeOrder(req: PlaceOrderRequest): OrderDTO = suspendTransaction(db = db) { ... productRepo.findByIdForUpdate(...) ... InsufficientStockException ... }`
  - `suspend fun cancelOrder(id: Long): OrderDTO = suspendTransaction(db = db) { ... }`

### T3-6. Controller 계층 (complexity: medium)

- [ ] `suspend fun` handler — WebFlux 코루틴 통합
- [ ] **`@Valid @RequestBody` 필수** (POST handler)
- [ ] List 반환 = Service에서 받은 `List<T>` 그대로 (`Flow<T>` 사용 안 함 — D11)
- [ ] `AuthorController`, `BookController`, `ProductController`, `OrderController`

### T3-7. Support 인프라 (complexity: high)

- [ ] `ExposedR2dbcConfig.kt`:
  - `ConnectionFactory` 빈 (`r2dbc.pool` + PostgreSQL)
  - `R2dbcDatabase.connect(connectionPool, config)` 빈 등록
  - R2DBC pool 설정 (스펙 §6 권장값: initial=5, max=20, max-idle=30m, max-acquire=3s, validation-query="SELECT 1")
- [ ] `DatabaseInitializer.kt` (R2DBC 전용):
  ```kotlin
  class DatabaseInitializer(private val connectionFactoryOptions: ConnectionFactoryOptions) : ApplicationRunner {
      override fun run(args: ApplicationArguments) {
          val host = connectionFactoryOptions.getValue(HOST)?.toString() ?: "localhost"
          val port = connectionFactoryOptions.getValue(PORT) as Int
          val database = connectionFactoryOptions.getValue(DATABASE)?.toString() ?: error("DB name required")
          val user = connectionFactoryOptions.getValue(USER)?.toString() ?: "postgres"
          val password = connectionFactoryOptions.getValue(PASSWORD)?.toString() ?: ""
          // P2-A fix: CharSequence option → toString()
          val jdbcDb = Database.connect("jdbc:postgresql://$host:$port/$database", "org.postgresql.Driver", user, password)
          transaction(jdbcDb) { SchemaUtils.create(AuthorTable, BookTable, ProductTable, OrderTable, OrderLineTable) }
      }
  }
  ```
  - JDBC one-shot으로 스키마 생성 + seed → R2DBC 런타임 사용
- [ ] `GlobalExceptionHandler.kt`:
  - WebFlux 전용: `WebExchangeBindException` (not `MethodArgumentNotValidException`) → 400
  - `@ExceptionHandler(WebExchangeBindException::class)` → 400 with field errors
  - 나머지 예외 동일
- [ ] `SwaggerConfig.kt`
- [ ] `application.yml`: R2DBC datasource + pool 설정
- [ ] `ExposedWebfluxR2dbcApp.kt`

### T3-8. 테스트 (complexity: high)

- [ ] `AbstractWebfluxR2dbcTest.kt`: `@BeforeEach truncateAll()` via `suspendTransaction`, `PostgreSQLServer.Launcher.postgres` 싱글턴, `runTest {}`
- [ ] `AuthorControllerTest.kt`, `BookControllerTest.kt`
- [ ] `ProductControllerTest.kt`, `OrderControllerTest.kt`
- [ ] `AuthorRepositoryTest.kt`, `OrderRepositoryTest.kt`
- [ ] `PlaceOrderRollbackTest.kt`
- [ ] `ConcurrentPlaceOrderTest.kt`: `runTest { }` + `List<Deferred<HttpStatusCode>>` 병렬 실행 → 1 success + N-1 409
- [ ] `test/resources/junit-platform.properties`, `logback-test.xml`

---

## 페이즈 4 — README 및 문서 정리

### T4-1. `exposed/README.md` 완성 (complexity: low)

- [ ] Architecture diagram (Mermaid): 3개 모듈 비교
- [ ] 모듈 선택 가이드 (학습자 결정 트리) 반영
- [ ] 각 모듈 "Used Bluetape4k Features" 표 포함
- [ ] 빌드/테스트 명령 예시

### T4-2. 각 모듈 `README.md` 작성 (complexity: low)

모듈별 구조:
1. 아키텍처 + Mermaid 다이어그램
2. 주요 기능 (TX 모델, 스레드 모델)
3. REST API 엔드포인트 목록
4. 실행 방법
5. Used Bluetape4k Features 표

대상: `exposed/mvc-jdbc/README.md`, `exposed/mvc-virtualthread/README.md`, `exposed/webflux-r2dbc/README.md`

---

## 페이즈 5 — 빌드 검증 및 테스트

### T5-1. 전체 빌드 검증 (complexity: medium)

- [ ] `./gradlew :exposed-mvc-jdbc:build` 오류 없음
- [ ] `./gradlew :exposed-mvc-virtualthread:build` 오류 없음
- [ ] `./gradlew :exposed-webflux-r2dbc:build` 오류 없음
- [ ] `./gradlew detekt` — 신규 모듈 코드 정적 분석 통과

### T5-2. 테스트 실행 (complexity: medium)

- [ ] `./gradlew :exposed-mvc-jdbc:test` — 모든 테스트 통과 (ConcurrentPlaceOrderTest 포함)
- [ ] `./gradlew :exposed-mvc-virtualthread:test` — 모든 테스트 통과
- [ ] `./gradlew :exposed-webflux-r2dbc:test` — 모든 테스트 통과
- [ ] 테스트 결과 기록: pass count + elapsed time

---

## 의존성 체크리스트

구현 전 `buildSrc/src/main/kotlin/Libs.kt` 확인:

| 의존성 | 심볼 | 확인 |
|---|---|---|
| `bluetape4k-virtualthread-jdk21` | `libs.bluetape4k.virtualthread.jdk21` | [ ] |
| `jetbrains-exposed-spring-boot4-starter` | `libs.jetbrains.exposed.spring.boot4.starter` | [ ] |
| `jetbrains-exposed-spring7-transaction` | `libs.jetbrains.exposed.spring7.transaction` | [ ] |
| `exposed-r2dbc` (bluetape4k) | `libs.exposed.r2dbc` | [ ] |
| `jetbrains-exposed-r2dbc` | `libs.jetbrains.exposed.r2dbc` | [ ] |
| `r2dbc-pool` | `libs.r2dbc.pool` | [ ] |
| `r2dbc-postgresql` | `libs.r2dbc.postgresql` | [ ] |
| `springdoc-openapi-starter-webmvc-ui` | `libs.springdoc.openapi.starter.webmvc.ui` | [ ] |
| `springdoc-openapi-starter-webflux-ui` | `libs.springdoc.openapi.starter.webflux.ui` | [ ] |
| `spring-boot-starter-validation` | `libs.spring.boot.starter.validation` | [ ] |
| `spring-boot-devtools` | `libs.spring.boot.devtools` | [ ] |

누락 심볼 발견 시 → `buildSrc/src/main/kotlin/Libs.kt` 에 추가 후 진행.

---

## 핵심 위험 및 대응

| 위험 | 대응 |
|---|---|
| TOCTOU 재고 경쟁 (race condition) | 모든 모듈에서 `ProductTable.forUpdate()` 적용; `ConcurrentPlaceOrderTest`로 검증 |
| R2DBC Flow TX 밖 소비 | Service `findAll()`에서 `suspendTransaction { repo.findAll().toList() }` — List 반환 |
| VT 모듈 `@Transactional` 사용 오류 | 코드 리뷰 시 `@Transactional` 어노테이션 grep — 없어야 함 |
| R2DBC 스키마 bootstrap 실패 | `DatabaseInitializer` one-shot JDBC 방식; CharSequence cast는 `.toString()` 사용 |
| DTO validation 미작동 | POST handler: `@Valid @RequestBody` 필수 — 없으면 constraint 미실행 |
| 테스트 병렬 DB 충돌 | `TestMutexService` (maxParallelUsages=1) + `junit-platform.properties` 직렬 설정 |

---

## 구현 순서 요약

```
T0 → T1 (mvc-jdbc 전체) → T1-빌드/테스트 → T2 (mvc-virtualthread 전체) → T2-빌드/테스트
  → T3 (webflux-r2dbc 전체) → T3-빌드/테스트 → T4 (README) → T5 (최종 검증)
```

각 모듈을 순차적으로 완성하고 빌드/테스트 통과 후 다음 모듈로 진행.

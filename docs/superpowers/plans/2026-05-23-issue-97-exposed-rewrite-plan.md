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
  - **[KE-P0-1 fix]** Exposed 의존성은 `libs.bluetape4k.exposed` (없는 심볼) 대신 명시:
    - `implementation(libs.exposed.core)` — bluetape4k exposed-core
    - `implementation(libs.exposed.jdbc)` — bluetape4k exposed-jdbc
    - `implementation(libs.jetbrains.exposed.spring.boot4.starter)` — Spring TX 통합
    - `implementation(libs.jetbrains.exposed.spring7.transaction)` — Spring 7 TX

### T1-2. 스키마 정의 (complexity: medium)

파일: `author/schema/AuthorTable.kt`, `BookTable.kt`
파일: `order/schema/OrderTable.kt`, `OrderLineTable.kt`, `ProductTable.kt`, `OrderStatus.kt`

- [ ] **[KE-P1-1 fix]** Exposed v1 import 정책 준수:
  - `import org.jetbrains.exposed.v1.core.*`
  - `import org.jetbrains.exposed.v1.jdbc.*`
  - ~~`import org.jetbrains.exposed.sql.*`~~ 사용 금지 (레거시)
- [ ] `AuthorTable`: id(Long autoIncrement), firstName, lastName, email(unique)
- [ ] `BookTable`: id, title, publishDate, authorId(FK → AuthorTable)
- [ ] `ProductTable`: id, name, price(Decimal(12,2)), stock(Int)
- [ ] `OrderTable`: id, customerId(Long), orderDate, status — **[Advisory fix]** `enumerationByName("status", length=20, klass=OrderStatus::class)`
- [ ] `OrderLineTable`: id, orderId(FK), productId(FK), quantity, unitPrice(Decimal — snapshot)
- [ ] `OrderStatus` enum: `PENDING`, `PAID`, `CANCELLED`

### T1-3. DTO + Mapper 정의 (complexity: medium)

Author 도메인:
- [ ] `AuthorDTO`, `BookDTO`, `AuthorWithBooksDTO`
- [ ] `AuthorMappers.kt` (ResultRow → DTO extension functions)

Order 도메인:
- [ ] `ProductDTO`, `OrderDTO`, `OrderLineDTO`, `OrderWithLinesDTO`
- [ ] `PlaceOrderRequest(customerId: Long, lines: List<OrderLineRequest>)` — `@field:Positive`, `@field:Valid @field:NotEmpty`
- [ ] `OrderLineRequest(productId: Long, quantity: Int)` — `@field:Positive`, `@field:Min(1)`
- [ ] `OrderMappers.kt`
- [ ] **[KE-P1-2 fix]** 모든 DTO는 `java.io.Serializable` 구현 + `companion object { const val serialVersionUID = 1L }` 선언

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

- [ ] **[Arch P1-2 fix]** `InsufficientStockException.kt` 별도 파일 생성:
  ```kotlin
  class InsufficientStockException(val productId: Long) :
      RuntimeException("Insufficient stock for product: $productId") // internal only
  ```
- [ ] `DatabaseConfig.kt`: `DataSource` 빈 (HikariCP), `Database.connect(dataSource)` Exposed 등록
- [ ] `DatabaseInitializer.kt`: `ApplicationRunner` 구현
  - **[SF-P0-1 fix]** 멱등성 보장: `if (ProductTable.selectAll().count() == 0L)` 조건 후 seed insert
  - **[SF-P0-1 fix]** 초기화 실패 시 `error("DatabaseInitializer failed: ...")` 로 앱 시작 중단
  - `SchemaUtils.create(AuthorTable, BookTable, ProductTable, OrderTable, OrderLineTable)`
  - seed: Products 5개, Authors 3명, Books 5권
- [ ] `GlobalExceptionHandler.kt` — **[SF-P1-4/5/6 fix]** 모든 핸들러 명시 (no "나머지 동일"):
  ```
  NoSuchElementException            → 404  NOT_FOUND
  IllegalArgumentException          → 400  BAD_REQUEST
  InsufficientStockException        → 409  CONFLICT  (외부 메시지에 productId 노출 금지)
  MethodArgumentNotValidException   → 400  BAD_REQUEST  (field errors 포함)
  ConstraintViolationException      → 400  BAD_REQUEST
  Exception (catch-all)             → 500  INTERNAL_SERVER_ERROR  (log with context; sanitized message)
  ```
  `ErrorResponse(status: Int, code: String, message: String)` envelope
- [ ] `SwaggerConfig.kt`: springdoc OpenAPI 설정
- [ ] `application.yml`: **[Arch P1-4 fix]** `@DynamicPropertySource` 또는 `jdbc:tc:postgresql:///` TC URL — 하드코딩 금지
- [ ] `ExposedMvcJdbcApp.kt`

### T1-8. 테스트 (complexity: high)

- [ ] `AbstractMvcJdbcTest.kt`:
  - `@SpringBootTest(webEnvironment=RANDOM_PORT)`, `WebTestClient`
  - `@Transactional` rollback per test (정상 테스트용)
  - `PostgreSQLServer.Launcher.postgres` 싱글턴
  - **[Arch P1-4 fix]** `@DynamicPropertySource` — PostgreSQL URL/user/password 주입
- [ ] `AuthorControllerTest.kt`: CRUD 검증
- [ ] `BookControllerTest.kt`: CRUD + 저자별 조회 검증
- [ ] `AuthorRepositoryTest.kt`: Repository 단위 검증
- [ ] `ProductControllerTest.kt`: CRUD 검증
- [ ] `OrderControllerTest.kt`: placeOrder 성공/실패 검증
- [ ] `OrderRepositoryTest.kt`: Repository 단위 검증
- [ ] `PlaceOrderRollbackTest.kt`: 중간 실패(존재하지 않는 productId) → 전체 롤백 검증
  - **[Test P1-1 fix]** 명시적 어설션:
    - `OrderTable.selectAll().count() == 0L` (주문 레코드 없음)
    - `OrderLineTable.selectAll().count() == 0L` (주문 라인 없음)
    - `ProductTable.select(stock).where { id eq productId }.single()[stock] == originalStock` (재고 불변)
- [ ] `ConcurrentPlaceOrderTest.kt`: **TOCTOU 검증**
  - **[Test P1-2 fix]** N=10, stock=1 상품 → 병렬 주문
  - **[Test P2-1 fix]** 클래스 레벨 `@Transactional` rollback opt-out (별도 `@Commit` 또는 클래스 분리)
  - 어설션: `successCount == 1 && failureCount == 9 && finalProductStock == 0`
- [ ] `test/resources/junit-platform.properties`: `junit.jupiter.execution.parallel.enabled=false` (TestMutexService)
- [ ] `test/resources/logback-test.xml`

---

## 페이즈 2 — `exposed/mvc-virtualthread` 구현

> 스택: Spring MVC + Virtual Threads + Exposed JDBC (manual TX)
> 패키지: `io.bluetape4k.workshop.exposed.mvc.vt`
> **`@Transactional` 금지** — `virtualFuture(executor) { transaction(db) {} }` 패턴만 사용

### T2-1. `build.gradle.kts` 생성 (complexity: medium)

- [ ] `mvc-jdbc` 기반 + 차이점만 반영:
  - **[KE-P1-5 fix]** `implementation(libs.bluetape4k.virtualthread.jdk25)` (Java 25 타깃; jdk21 아님)
  - `jetbrains-exposed-spring-boot4-starter` 제거 (수동 DB 연결)
  - `jetbrains-exposed-spring7-transaction` 제거
  - **[KE-P0-1 fix]** Exposed 의존성 명시:
    - `implementation(libs.exposed.core)`
    - `implementation(libs.exposed.jdbc)`
  - `spring-boot.starter.validation` 포함
  - `springdoc-openapi-starter-webmvc-ui` 유지

### T2-2. 스키마 정의 (complexity: low)

- [ ] **[KE-P1-1 fix]** Exposed v1 import 정책:
  - `import org.jetbrains.exposed.v1.core.*`
  - `import org.jetbrains.exposed.v1.jdbc.*`
- [ ] `mvc-jdbc`와 동일 테이블 정의 복제 (D1: self-contained)
- [ ] **[Advisory fix]** `OrderTable.status`: `enumerationByName("status", length=20, klass=OrderStatus::class)`

### T2-3. DTO + Mapper (complexity: low)

- [ ] `mvc-jdbc`와 동일 DTO 복제 (패키지만 `mvc.vt`로 변경)
- [ ] **[KE-P1-2 fix]** 모든 DTO `java.io.Serializable` + `serialVersionUID = 1L`

### T2-4. Repository 계층 (complexity: high)

**핵심**: 각 Repository 메서드가 `virtualFuture(executor) { transaction(db) {} }` 로 래핑.

- [ ] `companion object : KLogging()` — **KLogging 사용** (비코루틴 컨텍스트)
- [ ] `private val executor = Executors.newVirtualThreadPerTaskExecutor().apply { ShutdownQueue.register(this) }`
- [ ] `AuthorRepository`, `BookRepository`, `ProductRepository`, `OrderRepository`, `OrderLineRepository`
  - 단순 단일 테이블 CRUD만 (`placeOrder` 비즈니스 로직 없음 — Service로)
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
    - **[KE-P1-3 fix]** `ProductTable.update {}` 에서 기본 receiver 사용 (implicit `it` 사용; `with(SqlExpressionBuilder) {}` 제거):
      ```kotlin
      ProductTable.update({ ProductTable.id eq productId }) {
          it[stock] = stock - quantity  // 기본 receiver, SqlExpressionBuilder import 불필요
      }
      ```
    - 결과 `OrderTable.selectAll().where { id eq orderId }.single().toOrderDTO()`
  - **[Arch P1-3 fix]** `fun cancelOrder(orderId: Long): VirtualFuture<OrderDTO> = virtualFuture(executor) { transaction(db) { ... } }`
    - `OrderTable.update` status → `CANCELLED`
    - 존재하지 않는 orderId → `NoSuchElementException`
  - `fun findAll(): VirtualFuture<List<OrderDTO>>`
  - `fun findById(id: Long): VirtualFuture<OrderDTO?>`

### T2-6. Controller 계층 (complexity: medium)

- [ ] `.await()` 패턴: `val result = service.foo(...).await()`
- [ ] **POST handler에 `@Valid @RequestBody` 필수**
- [ ] `AuthorController`, `BookController`, `ProductController`
- [ ] `OrderController` — **[Arch P1-3 fix]** `cancelOrder` 엔드포인트 추가:
  - `POST /api/v1/orders` (placeOrder)
  - `GET /api/v1/orders/{id}`
  - `GET /api/v1/orders/{id}/lines`
  - `GET /api/v1/orders/{id}/total`
  - **`PATCH /api/v1/orders/{id}/cancel`** (cancelOrder)

### T2-7. Support 인프라 (complexity: medium)

- [ ] **[Arch P1-2 fix]** `InsufficientStockException.kt` 생성 (T1-7과 동일 구조)
- [ ] `DatabaseConfig.kt`: `Database.connect(dataSource)` (HikariCP, spring 자동설정 활용)
- [ ] `TomcatConfig.kt`: `TomcatProtocolHandlerCustomizer<*>` — Tomcat VT executor 설정
- [ ] `DatabaseInitializer.kt`: `ApplicationRunner`
  - `virtualFuture(executor) { transaction(db) { SchemaUtils.create(...) } }.await()`
  - **[SF-P0-1 fix]** 멱등성: `if (ProductTable.selectAll().count() == 0L)` 후 seed
  - **[SF-P0-1 fix]** 초기화 실패 시 앱 시작 중단
- [ ] `GlobalExceptionHandler.kt` — **[SF-P1-4/5/6 fix]** 모든 핸들러 명시:
  ```
  NoSuchElementException            → 404  NOT_FOUND
  IllegalArgumentException          → 400  BAD_REQUEST
  InsufficientStockException        → 409  CONFLICT  (외부 메시지에 productId 노출 금지)
  MethodArgumentNotValidException   → 400  BAD_REQUEST  (field errors 포함)
  ConstraintViolationException      → 400  BAD_REQUEST
  Exception (catch-all)             → 500  INTERNAL_SERVER_ERROR  (log with context; sanitized message)
  ```
- [ ] `SwaggerConfig.kt`
- [ ] `application.yml`: **[Arch P1-4 fix]** `@DynamicPropertySource` 또는 TC URL
- [ ] `ExposedMvcVirtualThreadApp.kt`

### T2-8. 테스트 (complexity: high)

- [ ] **[Advisory fix]** `truncateAll()` 유틸리티 구현: `transaction(db) { AuthorTable.deleteAll(); BookTable.deleteAll(); ... }` — 테스트 격리용
- [ ] `AbstractMvcVirtualThreadTest.kt`:
  - `@BeforeEach` — `truncateAll()` + re-seed (VT 환경에서 `@Transactional` rollback 불가)
  - `PostgreSQLServer.Launcher.postgres` 싱글턴
  - **[Arch P1-4 fix]** `@DynamicPropertySource` — PostgreSQL URL 주입
- [ ] `AuthorControllerTest.kt`, `BookControllerTest.kt`
- [ ] `ProductControllerTest.kt`, `OrderControllerTest.kt`
- [ ] `AuthorRepositoryTest.kt`, `OrderRepositoryTest.kt`
- [ ] `PlaceOrderRollbackTest.kt`:
  - **[Test P1-1 fix]** 3개 테이블 모두 어설션:
    - `OrderTable.selectAll().count() == 0L`
    - `OrderLineTable.selectAll().count() == 0L`
    - `ProductTable.select(stock).where { id eq productId }.single()[stock] == originalStock`
- [ ] `ConcurrentPlaceOrderTest.kt`:
  - **[Test P1-2 fix]** N=10, stock=1 → `successCount == 1 && failureCount == 9 && finalStock == 0`
- [ ] **[SF-P1-1 fix]** `@Transactional` 부재 검증 태스크:
  - 구현 완료 후: `rg "@Transactional" exposed/mvc-virtualthread/src/main/` → **결과 0건 확인** (있으면 제거)
- [ ] `test/resources/junit-platform.properties`, `logback-test.xml`

---

## 페이즈 3 — `exposed/webflux-r2dbc` 구현

> 스택: WebFlux + Coroutines + Exposed R2DBC
> 패키지: `io.bluetape4k.workshop.exposed.webflux.r2dbc`
> TX 모델: `suspendTransaction(db = db) {}`

### T3-1. `build.gradle.kts` 생성 (complexity: medium)

- [ ] 스펙 §6 `exposed/webflux-r2dbc/build.gradle.kts` 반영:
  - **[KE-P0-1 fix]** Exposed 의존성 명시:
    - `implementation(libs.exposed.r2dbc)` — bluetape4k exposed-r2dbc
    - `implementation(libs.jetbrains.exposed.r2dbc)` — JetBrains Exposed R2DBC
  - `r2dbc.pool`, `r2dbc.postgresql`
  - `bluetape4k.coroutines`, `kotlinx.coroutines.core.lib`, `kotlinx.coroutines.reactor`
  - `reactor.netty`, `reactor.kotlin.extensions`
  - `spring.boot.starter.webflux.lib` (not webmvc)
  - `springdoc-openapi-starter-webflux-ui`
  - `spring.boot.starter.validation`
  - **[KE-P1-4 fix]** R2DBC pool bean 명시적 wiring — `spring-boot-starter-data-r2dbc` 의존성 추가 (또는 직접 `ConnectionPool` 구성)
  - webmvc + Exposed JDBC starter + spring7-transaction 제거

### T3-2. 스키마 정의 (complexity: low)

- [ ] 동일 테이블 정의 (R2DBC 임포트 정책):
  - `import org.jetbrains.exposed.v1.r2dbc.*`
  - `import org.jetbrains.exposed.v1.core.*`
  - ~~`import org.jetbrains.exposed.v1.jdbc.*`~~ 사용 금지 (R2DBC 모듈)
- [ ] **[Advisory fix]** `OrderTable.status`: `enumerationByName("status", length=20, klass=OrderStatus::class)`

### T3-3. DTO + Mapper (complexity: low)

- [ ] `mvc-jdbc`와 동일 DTO 복제 (패키지만 `webflux.r2dbc`로)
- [ ] **[KE-P1-2 fix]** 모든 DTO `java.io.Serializable` + `serialVersionUID = 1L`

### T3-4. Repository 계층 (complexity: high)

**핵심**: `suspend fun` for scalars, `fun findAll(): Flow<T>` for lists — **TX 없음** (service의 `suspendTransaction` 안에서 collect).

- [ ] `KLoggingChannel` 사용 (코루틴 컨텍스트 — webflux-r2dbc 전용)
- [ ] `AuthorRepository`:
  - `fun findAll(): Flow<AuthorDTO>`
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

- [ ] `KLoggingChannel` 사용 (코루틴 컨텍스트)
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
- [ ] `AuthorController`, `BookController`, `ProductController`
- [ ] `OrderController`:
  - `POST /api/v1/orders` (placeOrder)
  - `GET /api/v1/orders/{id}`
  - `GET /api/v1/orders/{id}/lines`
  - `GET /api/v1/orders/{id}/total`
  - `PATCH /api/v1/orders/{id}/cancel`

### T3-7. Support 인프라 (complexity: high)

- [ ] **[Arch P1-2 fix]** `InsufficientStockException.kt` 생성
- [ ] `ExposedR2dbcConfig.kt`:
  - **[KE-P1-4 fix]** `ConnectionFactory` 빈 — programmatic `ConnectionPool` 구성:
    ```kotlin
    @Bean
    fun connectionFactory(props: R2dbcProperties): ConnectionFactory {
        val options = ConnectionFactoryOptions.parse(props.url)
        val factory = ConnectionFactories.get(options)
        return ConnectionPoolConfiguration.builder(factory)
            .initialSize(5).maxSize(20)
            .maxIdleTime(Duration.ofMinutes(30))
            .maxAcquireTime(Duration.ofSeconds(3))
            .validationQuery("SELECT 1")
            .build()
            .let { ConnectionPool(it) }
    }
    ```
  - `R2dbcDatabase.connect(connectionPool, config)` 빈 등록
- [ ] `DatabaseInitializer.kt` (R2DBC 전용):
  ```kotlin
  class DatabaseInitializer(private val r2dbcDatabase: R2dbcDatabase) : ApplicationRunner {
      override fun run(args: ApplicationArguments) {
          val jdbcUrl = ... // r2dbc URL → jdbc URL 변환
          val jdbcDb = Database.connect(jdbcUrl, "org.postgresql.Driver", user, password)
          try {
              transaction(jdbcDb) {
                  SchemaUtils.create(AuthorTable, BookTable, ProductTable, OrderTable, OrderLineTable)
                  // [SF-P0-1 fix] 멱등성: count 확인 후 seed
                  if (ProductTable.selectAll().count() == 0L) {
                      // seed data
                  }
              }
          } catch (e: Exception) {
              error("DatabaseInitializer failed: ${e.message}")
          } finally {
              // [SF-P0-2 fix] JDBC connection 명시적 정리
              TransactionManager.closeAndUnregister(jdbcDb)
          }
      }
  }
  ```
- [ ] `GlobalExceptionHandler.kt` — **[SF-P1-4/5/6 fix]** WebFlux 전용, 모든 핸들러 명시:
  ```
  NoSuchElementException            → 404  NOT_FOUND
  IllegalArgumentException          → 400  BAD_REQUEST
  InsufficientStockException        → 409  CONFLICT  (외부 메시지에 productId 노출 금지)
  WebExchangeBindException          → 400  BAD_REQUEST  (field errors; WebFlux 전용 — MethodArgumentNotValidException 아님)
  ConstraintViolationException      → 400  BAD_REQUEST
  Exception (catch-all)             → 500  INTERNAL_SERVER_ERROR  (log with context; sanitized message)
  ```
- [ ] `SwaggerConfig.kt`
- [ ] `application.yml`: **[Arch P1-4 fix]** R2DBC datasource + pool 설정; `@DynamicPropertySource` 또는 TC URL
- [ ] `ExposedWebfluxR2dbcApp.kt`

### T3-8. 테스트 (complexity: high)

- [ ] **[Advisory fix]** `truncateAll()` 유틸리티: `suspendTransaction(db) { OrderLineTable.deleteAll(); OrderTable.deleteAll(); ProductTable.deleteAll(); BookTable.deleteAll(); AuthorTable.deleteAll() }`
- [ ] `AbstractWebfluxR2dbcTest.kt`:
  - `@BeforeEach suspend fun setUp() = truncateAll()` + re-seed via `suspendTransaction`
  - `PostgreSQLServer.Launcher.postgres` 싱글턴
  - **[Arch P1-4 fix]** `@DynamicPropertySource` — R2DBC URL 주입
- [ ] `AuthorControllerTest.kt`, `BookControllerTest.kt`
- [ ] `ProductControllerTest.kt`, `OrderControllerTest.kt`
- [ ] `AuthorRepositoryTest.kt`, `OrderRepositoryTest.kt`
- [ ] `PlaceOrderRollbackTest.kt`:
  - **[Test P1-1 fix]** 3개 테이블 모두 어설션:
    - `suspendTransaction(db) { OrderTable.selectAll().count() } == 0L`
    - `suspendTransaction(db) { OrderLineTable.selectAll().count() } == 0L`
    - `suspendTransaction(db) { ProductTable.select(stock).where { id eq productId }.single()[stock] } == originalStock`
- [ ] `ConcurrentPlaceOrderTest.kt`:
  - **[Test P1-3 fix]** `runTest {}` 금지 (TestCoroutineScheduler가 코루틴 직렬화 — DB 동시성 불가)
  - 대신: `runBlocking { coroutineScope { List(N) { async(Dispatchers.IO) { client.post(...) } }.awaitAll() } }`
  - **[Test P1-2 fix]** N=10, stock=1 → `successCount == 1 && failureCount == 9 && finalStock == 0`
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

### T4-3. `exposed/README.ko.md` 동기화 (complexity: low)

- [ ] **[Advisory fix]** `exposed/README.ko.md` 한국어 README 업데이트 (또는 없으면 생성)
- [ ] 3개 모듈 소개 + 선택 가이드 반영

### T4-4. Public API KDoc (complexity: low)

- [ ] **[Advisory fix]** 신규 public API (Controller, Service, Repository 인터페이스)에 영어 KDoc 추가:
  - 1-line summary
  - `@param`, `@return`, `@throws` (예외 발생 조건 포함)
  - Service `placeOrder` / `cancelOrder` 에 `@throws InsufficientStockException` 명시

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

### T5-3. `@Transactional` 부재 검증 (complexity: low)

- [ ] **[SF-P1-1 fix]** `rg "@Transactional" exposed/mvc-virtualthread/src/main/` → 결과 0건 확인
  - 결과 있으면 즉시 제거 후 재빌드

---

## 페이즈 6 — Step 7 Lessons + PR 생성

### T6-1. Lessons 문서 작성 (complexity: low)

- [ ] **[Advisory fix]** `docs/lessons/2026-05-23-issue-97-exposed-rewrite.md` 생성 (feature branch에 commit)
  - 결정 사항: VT `@Transactional` 금지 이유, R2DBC Flow TX 밖 소비 금지, TOCTOU forUpdate 패턴
  - 리뷰 발견사항 및 수정 내역
  - 향후 참고용 지침

### T6-2. PR 생성 (complexity: low)

- [ ] **[Advisory fix]** PR 생성:
  ```bash
  gh pr create \
    --title "feat: Exposed 예제 전면 재작성 — mvc-jdbc, mvc-virtualthread, webflux-r2dbc" \
    --body "..." \
    --assignee debop \
    --label "..." # issue #97과 동일한 labels
  ```
  - PR body: DoD 체크리스트 형식 (test results, rationale, verification commands)
  - **Merge는 사용자에게 요청 — `gh pr merge` 자동 실행 금지**

---

## 의존성 체크리스트

> **[KE-P0-2 fix]** 참조 파일: `gradle/libs.versions.toml` (buildSrc/Libs.kt 없음)

| 의존성 | 심볼 | 확인 |
|---|---|---|
| `bluetape4k-exposed-core` | `libs.exposed.core` | [ ] |
| `bluetape4k-exposed-jdbc` | `libs.exposed.jdbc` | [ ] |
| `bluetape4k-exposed-r2dbc` | `libs.exposed.r2dbc` | [ ] |
| `bluetape4k-virtualthread-jdk25` | `libs.bluetape4k.virtualthread.jdk25` | [ ] |
| `jetbrains-exposed-spring-boot4-starter` | `libs.jetbrains.exposed.spring.boot4.starter` | [ ] |
| `jetbrains-exposed-spring7-transaction` | `libs.jetbrains.exposed.spring7.transaction` | [ ] |
| `jetbrains-exposed-r2dbc` | `libs.jetbrains.exposed.r2dbc` | [ ] |
| `r2dbc-pool` | `libs.r2dbc.pool` | [ ] |
| `r2dbc-postgresql` | `libs.r2dbc.postgresql` | [ ] |
| `springdoc-openapi-starter-webmvc-ui` | `libs.springdoc.openapi.starter.webmvc.ui` | [ ] |
| `springdoc-openapi-starter-webflux-ui` | `libs.springdoc.openapi.starter.webflux.ui` | [ ] |
| `spring-boot-starter-validation` | `libs.spring.boot.starter.validation` | [ ] |
| `spring-boot-devtools` | `libs.spring.boot.devtools` | [ ] |
| `spring-boot-starter-data-r2dbc` | `libs.spring.boot.starter.data.r2dbc` (or equivalent) | [ ] |

누락 심볼 발견 시 → `gradle/libs.versions.toml` 에 추가 후 진행.

---

## 핵심 위험 및 대응

| 위험 | 대응 |
|---|---|
| TOCTOU 재고 경쟁 (race condition) | 모든 모듈에서 `ProductTable.forUpdate()` 적용; `ConcurrentPlaceOrderTest` N=10으로 검증 |
| R2DBC Flow TX 밖 소비 | Service `findAll()`에서 `suspendTransaction { repo.findAll().toList() }` — List 반환 |
| VT 모듈 `@Transactional` 사용 오류 | 코드 리뷰 시 `rg "@Transactional"` grep — 0건 확인 필수 |
| R2DBC 스키마 bootstrap 실패 | `DatabaseInitializer` one-shot JDBC 방식; try/finally로 연결 명시적 정리 |
| JDBC 연결 누수 (webflux-r2dbc) | `TransactionManager.closeAndUnregister(jdbcDb)` in finally 블록 |
| DTO validation 미작동 | POST handler: `@Valid @RequestBody` 필수 |
| 테스트 병렬 DB 충돌 | `TestMutexService` (maxParallelUsages=1) + `junit-platform.properties` 직렬 설정 |
| 동시성 테스트 코루틴 직렬화 | webflux-r2dbc `ConcurrentPlaceOrderTest`: `runBlocking { coroutineScope { async(Dispatchers.IO) } }` — `runTest {}` 금지 |
| Seed 멱등성 | `if (count() == 0L)` 가드; 초기화 실패 시 `error(...)` 앱 중단 |
| ProductId 외부 노출 | `InsufficientStockException` 핸들러: 내부 로그만; 외부 메시지는 일반화 |

---

## 구현 순서 요약

```
T0 → T1 (mvc-jdbc 전체) → T1-빌드/테스트 → T2 (mvc-virtualthread 전체) → T2-빌드/테스트
  → T3 (webflux-r2dbc 전체) → T3-빌드/테스트 → T4 (README+KDoc) → T5 (최종 검증)
  → T6 (Lessons + PR 생성)
```

각 모듈을 순차적으로 완성하고 빌드/테스트 통과 후 다음 모듈로 진행.

---

## Appendix — Step 3-R Review Iteration Log

### Round 1 (2026-05-23)

| Reviewer | P0 | P1 | P2 | P3 |
|---|---|---|---|---|
| Architect | 0 | 5 | 6 | 4 |
| Testing | 0 | 3 | 6 | 4 |
| Silent Failure Hunter | 2 | 9 | 6 | 4 |
| Kotlin/Exposed | 2 | 5 | 4 | 2 |
| **합계** | **4** | **22** | **22** | **14** |

P0/P1 findings 전량 본 플랜에 반영 (Round 1 후 버전).

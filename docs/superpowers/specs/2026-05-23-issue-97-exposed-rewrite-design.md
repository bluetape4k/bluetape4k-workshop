# Issue #97 — Exposed 예제 전면 재작성 설계

**Date**: 2026-05-23
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/97
**Parent Epic**: #76
**Branch**: `feat/issue-97-exposed-rewrite`
**Status**: Draft

---

## 1. 배경 및 목적

현재 `exposed/` 폴더에는 5개의 모듈이 있으나 각각 단편적인 예제(Movie/Actor 단순 도메인, MySQL 중심, Gatling 포함)로 구성되어 있어 실무 패턴을 보여주지 못한다. Epic #76 구조 개편의 일환으로 3개의 "실전 앱" 형태 모듈로 교체한다.

### 삭제 대상 (5개)

| 모듈 | 이유 |
|---|---|
| `exposed/domain` | 테스트 전용 교육 모듈; `src/main` 없음 |
| `exposed/dao-web-transaction` | Spring MVC + Exposed DAO; 신규 mvc-jdbc 로 대체 |
| `exposed/spring-transaction` | Spring MVC + Exposed SQL DSL; 신규 mvc-jdbc 로 대체 |
| `exposed/sql-web-virtualthread` | Virtual Thread + JDBC (MySQL); 신규 mvc-virtualthread 로 대체 |
| `exposed/sql-webflux-coroutines` | WebFlux + Exposed JDBC (MySQL, R2DBC 아님); 신규 webflux-r2dbc 로 대체 |

### 신규 생성 대상 (3개)

| 모듈 | 스택 | 특징 |
|---|---|---|
| `exposed/mvc-jdbc` | Spring MVC + Exposed JDBC + Spring TX | `@Transactional` 선언적 트랜잭션, Author/Book + Order 도메인 |
| `exposed/mvc-virtualthread` | Spring MVC + Virtual Threads + Exposed JDBC | `VirtualFuture<T>` 패턴, Tomcat VT executor, manual `transaction {}` |
| `exposed/webflux-r2dbc` | WebFlux + Coroutines + Exposed R2DBC | `suspend fun` / `Flow<T>`, `suspendTransaction`, R2DBC 커넥션 풀 |

---

## 2. 결정 사항

| # | 결정 | 근거 |
|---|---|---|
| D1 | 각 모듈 자체 완결 (도메인 복제) | 학습용 self-contained; `exposed/shared` 추출 불필요 |
| D2 | 단일 PR (삭제 5 + 추가 3) | 원자적 변경; 중간 상태 없음 |
| D3 | `exposed/domain` 교육 테스트 삭제 수용 | #77 결정 준수 |
| D4 | Gatling 시뮬레이션 제외 | Issue #97 스코프 외 |
| D5 | DB 단일: PostgreSQL (Testcontainers) | 실무 패턴, H2 제거 |
| D6 | DTO 단일 계층 (`XxxDTO`) | 레이어 오버헤드 불필요, `Record` 레이어 제외 |
| D7 | `mvc-jdbc`: `@Transactional` 선언적 TX | 스프링 표준; `jetbrains-exposed-spring-boot4-starter` 활용 |
| D8 | `mvc-virtualthread`: manual `transaction(db){}` inside `virtualFuture` | 스프링 AOP TX는 VT에서 컨텍스트 손실 — `@Transactional` 금지 |
| D9 | `webflux-r2dbc`: `suspendTransaction(db){}` on service layer | R2DBC 전용 TX 브릿지; `newSuspendedTransaction` (JDBC) 금지 |
| D10 | 재고 검증: `SELECT … FOR UPDATE` 방식 채택 | READ COMMITTED 하에서 TOCTOU 경쟁 방지; 학습 목적상 최적 패턴 (조건부 UPDATE + row-count 검사 대안보다 명시적) |
| D11 | `findAll()` service 레이어에서 `List<T>` 반환 (R2DBC) | `Flow<T>` 소비는 반드시 R2DBC TX 내에서; service가 `suspendTransaction {}` 안에서 `toList()` collect |

---

## 3. 도메인 모델

두 도메인이 모든 모듈에 공통 적용 (각 모듈 내 복제):

### Simple: Author → Book

```
AuthorTable(id: Long, firstName, lastName, email[unique])
BookTable(id: Long, title, publishDate, authorId FK → AuthorTable)
```

REST 예시:
- `GET /api/v1/authors`, `POST /api/v1/authors`
- `GET /api/v1/books?authorId=1`, `POST /api/v1/books`
- `GET /api/v1/authors/{id}/books` (저자별 도서 목록)

### Complex: Order → OrderLine → Product

```
ProductTable(id: Long, name, price: Decimal(12,2), stock: Int)
OrderTable(id: Long, customerId: Long, orderDate, status: Enum[PENDING/PAID/CANCELLED])
OrderLineTable(id: Long, orderId FK, productId FK, quantity, unitPrice: Decimal[snapshot])
```

REST 예시:
- `GET /api/v1/products`, `POST /api/v1/products`
- `POST /api/v1/orders` — `placeOrder()` 트랜잭션: 주문 생성 + 라인 삽입(단가 스냅샷) + 재고 감소
- `GET /api/v1/orders/{id}/lines`, `GET /api/v1/orders/{id}/total`
- `PATCH /api/v1/orders/{id}/cancel`

**Request / Response DTO 정의**:

```kotlin
// PlaceOrderRequest — 주문 생성 요청 (P0-3 fix: 명시적 정의)
data class PlaceOrderRequest(
    @field:Positive val customerId: Long,
    @field:Valid @field:NotEmpty val lines: List<OrderLineRequest>,
) : Serializable

data class OrderLineRequest(
    @field:Positive val productId: Long,
    @field:Min(1) val quantity: Int,
) : Serializable

// insert 후 리턴: 할당된 id 포함 전체 DTO
// 예: OrderDTO(id=101, customerId=1, orderDate=..., status=PENDING, ...)
```

**핵심 비즈니스 규칙** (`placeOrder`) — TOCTOU 경쟁 방어 포함:

1. `OrderTable` insert → `orderId` 획득
2. 각 라인: `ProductTable.selectAll().where { id eq line.productId }.forUpdate()` — **row-level lock** (SELECT … FOR UPDATE), TOCTOU 방지
3. `require(stock >= quantity)` 위반 시 `InsufficientStockException(productId)` → **HTTP 409 Conflict**
4. `OrderLineTable` insert (현재 `ProductTable.price` 스냅샷 → `unitPrice` 필드)
5. `ProductTable.update { stock = stock - quantity }` (lock 선점 후 안전한 감소)
6. 모두 단일 트랜잭션 (일부 실패 시 전체 롤백)

> **Notes**:
> - `forUpdate()` = PostgreSQL `SELECT ... FOR UPDATE`. READ COMMITTED isolation에서 동시 주문에 의한 재고 초과판매 방지.
> - `InsufficientStockException : RuntimeException` — `GlobalExceptionHandler`에서 HTTP 409 매핑.
> - 인증/권한(IDOR) 보호는 이 예제의 스코프 외 (§9 참조).

---

## 4. 아키텍처

### 4-0. 모듈 선택 가이드 (학습자 결정 트리)

> **어느 모듈로 시작할지 모를 때** — 이 표를 참조:

| 상황 | 추천 모듈 |
|---|---|
| Spring MVC 표준 패턴 + 선언적 TX 학습 | `exposed/mvc-jdbc` |
| 블로킹 JDBC를 Virtual Thread로 처리량 향상 실험 | `exposed/mvc-virtualthread` |
| 비동기/리액티브 스택 (WebFlux + 코루틴) 필요 | `exposed/webflux-r2dbc` |

| 모듈 | TX 모델 | 스레드 | 특이사항 |
|---|---|---|---|
| `mvc-jdbc` | `@Transactional` (Spring AOP) | Platform thread (Tomcat) | 가장 단순, Spring 표준 |
| `mvc-virtualthread` | `transaction(db){}` (manual) | Virtual Thread | `@Transactional` 금지, VT 처리량 극대화 |
| `webflux-r2dbc` | `suspendTransaction {}` | Event loop (Netty) | 비블로킹 I/O, 코루틴 `Flow<T>` |

---

### 4-1. 패키지 구조 (공통 패턴)

```
io.bluetape4k.workshop.exposed.{module}/
├── {ModuleName}App.kt                   # @SpringBootApplication
├── author/
│   ├── controller/{AuthorController, BookController}.kt
│   ├── dto/{AuthorDTO, BookDTO, AuthorWithBooksDTO, Mappers}.kt
│   ├── repository/{AuthorRepository, BookRepository}.kt
│   ├── schema/{AuthorTable, BookTable}.kt
│   └── service/AuthorService.kt
├── order/
│   ├── controller/{OrderController, ProductController}.kt
│   ├── dto/{OrderDTO, OrderLineDTO, ProductDTO, PlaceOrderRequest, OrderWithLinesDTO, Mappers}.kt
│   ├── repository/{OrderRepository, OrderLineRepository, ProductRepository}.kt
│   ├── schema/{OrderTable, OrderLineTable, ProductTable, OrderStatus}.kt
│   └── service/OrderService.kt
└── support/
    ├── DatabaseConfig.kt                # DataSource (JDBC) or R2dbcDatabase (R2DBC)
    ├── DatabaseInitializer.kt           # ApplicationRunner: schema + seed
    └── SwaggerConfig.kt
```

패키지 루트:
- `mvc-jdbc` → `io.bluetape4k.workshop.exposed.mvc.jdbc`
- `mvc-virtualthread` → `io.bluetape4k.workshop.exposed.mvc.vt`
- `webflux-r2dbc` → `io.bluetape4k.workshop.exposed.webflux.r2dbc`

### 4-2. 레이어 계약 (모듈별)

#### `exposed/mvc-jdbc`

```
Controller → Service(@Transactional) → Repository(no TX) → Exposed DSL → HikariCP → PostgreSQL
```

- Controller: `T?`, `List<T>`, `ResponseEntity<T>` 반환
- Service: `@Transactional`/`@Transactional(readOnly=true)` 경계 — 모든 TX 오픈/커밋
- Repository: plain `fun`, TX 없음, Exposed DSL 직접 호출
- `jetbrains-exposed-spring-boot4-starter`가 `SpringTransactionManager` 자동 등록

```kotlin
// Service TX boundary example (P0-1 fix: SELECT FOR UPDATE, P1-6 fix: 409, P1-7 fix: sanitized messages)
@Service
class OrderService(
    private val orderRepo: OrderRepository,
    private val orderLineRepo: OrderLineRepository,
    private val productRepo: ProductRepository,
) {
    @Transactional
    fun placeOrder(req: PlaceOrderRequest): OrderDTO {
        val order = orderRepo.insert(req)   // returns OrderDTO with assigned id
        req.lines.forEach { line ->
            // SELECT ... FOR UPDATE: row-level lock prevents concurrent overselling (TOCTOU fix)
            val product = productRepo.findByIdForUpdate(line.productId)
                ?: throw NoSuchElementException("product not found")
            if (product.stock < line.quantity) throw InsufficientStockException(line.productId)
            orderLineRepo.insert(order.id, line, product.price)   // unitPrice snapshot
            productRepo.decrementStock(line.productId, line.quantity)
        }
        return order
    }
}

// InsufficientStockException → HTTP 409 via GlobalExceptionHandler
class InsufficientStockException(productId: Long) :
    RuntimeException("Insufficient stock")   // external msg: no productId leak
```

#### `exposed/mvc-virtualthread`

```
Controller(.await()) → Service(VirtualFuture) → virtualExecutor VT → transaction(db){} → Exposed DSL → HikariCP → PostgreSQL
```

- Controller: `val result = service.foo(...).await()` (Tomcat VT 환경에서 블로킹 안전)
- **Service**: 다중 테이블 비즈니스 로직 + `VirtualFuture<T>` 소유 (P1-2 fix: Repository에서 Service로 이동)
- **Repository**: 단일 테이블 단순 CRUD만 — `fun foo(): VirtualFuture<T> = virtualFuture(executor) { transaction(db) { ... } }`
- **`@Transactional` 사용 금지**: Spring AOP TX는 호출 스레드에 `Connection`을 바인딩한다. `virtualFuture { transaction(db) {} }`는 별도 VT에서 실행되므로 AOP 관리 Connection이 실제 SQL VT에 전달되지 않음 → TX 경계 누락. VT 모듈은 `transaction(db){}` 수동 관리만 사용 (D8)
- `ShutdownQueue.register(executor)` 필수

```kotlin
// Service: 복잡 비즈니스 로직 + VirtualFuture 소유 (P1-2 fix)
@Service
class OrderService(private val db: Database) {
    companion object : KLogging() {  // KLogging (비코루틴 컨텍스트 — P1-3 fix)
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
            .apply { ShutdownQueue.register(this) }
    }

    fun placeOrder(req: PlaceOrderRequest): VirtualFuture<OrderDTO> = virtualFuture(executor) {
        transaction(db) {
            val orderId = OrderTable.insertAndGetId { ... }
            req.lines.forEach { line ->
                // SELECT ... FOR UPDATE: row-level lock prevents TOCTOU overselling (P0-1 fix)
                val snapshot = ProductTable.selectAll()
                    .where { ProductTable.id eq line.productId }
                    .forUpdate()
                    .single()
                if (snapshot[ProductTable.stock] < line.quantity)
                    throw InsufficientStockException(line.productId)  // → HTTP 409 (P1-6 fix)
                OrderLineTable.insert { ... }
                ProductTable.update({ ProductTable.id eq line.productId }) {
                    with(SqlExpressionBuilder) { it[stock] = stock - line.quantity }
                }
            }
            OrderTable.selectAll().where { OrderTable.id eq orderId }.single().toOrderDTO()
        }
    }
}

// Repository: 단일 테이블 단순 조회/삽입만 (P1-2 fix: placeOrder는 Service로)
@Repository
class OrderRepository(private val db: Database) {
    companion object : KLogging() {  // KLogging (비코루틴 — P1-3 fix)
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
            .apply { ShutdownQueue.register(this) }
    }

    fun findById(id: Long): VirtualFuture<OrderDTO?> = virtualFuture(executor) {
        transaction(db) { OrderTable.selectAll().where { OrderTable.id eq id }.firstOrNull()?.toOrderDTO() }
    }

    fun findAll(): VirtualFuture<List<OrderDTO>> = virtualFuture(executor) {
        transaction(db) { OrderTable.selectAll().map { it.toOrderDTO() } }
    }
}
```

#### `exposed/webflux-r2dbc`

```
Controller(suspend/Flow) → Service(suspendTransaction) → Repository(suspend/Flow) → Exposed R2DBC DSL → R2DBC Pool → PostgreSQL
```

- Controller: `suspend fun` for scalars, `suspend fun` for lists (List 반환) — `Flow<T>`는 Repository 레이어에서만
- Service: `suspendTransaction(db = db) {}` 모든 쓰기 + 읽기 (P0-2 fix: Flow는 TX 내에서 collect)
- Repository: `suspend fun findById(): T?`, `fun findAll(): Flow<T>` — TX 없음
- R2DBC imports: `org.jetbrains.exposed.v1.r2dbc.*` (not `jdbc`)

```kotlin
// Service suspendTransaction boundary (P0-1 fix: SELECT FOR UPDATE, P0-2 fix: findAll wraps Flow in TX)
@Service
class OrderService(
    private val db: R2dbcDatabase,
    private val orderRepo: OrderRepository,
    private val orderLineRepo: OrderLineRepository,
    private val productRepo: ProductRepository,
) {
    suspend fun placeOrder(req: PlaceOrderRequest): OrderDTO = suspendTransaction(db = db) {
        val order = orderRepo.insert(req)
        req.lines.forEach { line ->
            // SELECT ... FOR UPDATE: R2DBC row-level lock (P0-1 fix)
            val product = productRepo.findByIdForUpdate(line.productId)
                ?: throw NoSuchElementException("product not found")
            if (product.stock < line.quantity) throw InsufficientStockException(line.productId)
            orderLineRepo.insert(order.id, line, product.price)
            productRepo.decrementStock(line.productId, line.quantity)
        }
        order
    }

    // P0-2 fix: Flow는 TX 내에서 collect → List 반환 (TX 밖에서 Flow 소비 불가)
    suspend fun findAll(): List<OrderDTO> = suspendTransaction(db = db) {
        orderRepo.findAll().toList()
    }

    suspend fun findById(id: Long): OrderWithLinesDTO? = suspendTransaction(db = db) {
        orderRepo.findById(id)?.let { o ->
            o.withLines(orderLineRepo.findByOrderId(id).toList())
        }
    }
}

// Repository: Flow<T> 반환 — TX 없음, service에서 suspendTransaction 내에서 collect
```

### 4-3. 스키마 부트스트랩

- **JDBC 모듈**: `DatabaseInitializer` (`ApplicationRunner`)에서 `SchemaUtils.create(*tables)` + seed 데이터
- **R2DBC 모듈**: R2DBC `SchemaUtils`는 제한적 → 시작 시 one-shot JDBC `Database.connect(jdbcUrl)`로 `SchemaUtils.create` 후 닫기, 런타임 DML은 R2DBC 사용 (P2-4 fix: 구체적 패턴 추가)

```kotlin
// DatabaseInitializer.kt (webflux-r2dbc)
@Component
class DatabaseInitializer(
    private val connectionFactoryOptions: ConnectionFactoryOptions,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        // R2DBC ConnectionFactoryOptions에서 JDBC URL 조립 (one-shot bootstrap)
        val host = connectionFactoryOptions.getValue(ConnectionFactoryOptions.HOST)?.toString() ?: "localhost"
        val port = connectionFactoryOptions.getValue(ConnectionFactoryOptions.PORT) as Int
        val database = connectionFactoryOptions.getValue(ConnectionFactoryOptions.DATABASE)?.toString() ?: error("DB name required")
        val user = connectionFactoryOptions.getValue(ConnectionFactoryOptions.USER)?.toString() ?: "postgres"
        val password = connectionFactoryOptions.getValue(ConnectionFactoryOptions.PASSWORD)?.toString() ?: ""
        // P2-A fix: PASSWORD/USER options are Option<CharSequence> — use toString() not direct cast

        val jdbcUrl = "jdbc:postgresql://$host:$port/$database"
        val jdbcDb = Database.connect(jdbcUrl, "org.postgresql.Driver", user, password)
        transaction(jdbcDb) {
            SchemaUtils.create(AuthorTable, BookTable, ProductTable, OrderTable, OrderLineTable)
        }
        // DriverManager 연결 — HikariCP 아님. 단일 연결, 트랜잭션 완료 후 JVM GC로 정리됨 (P3-A fix)
    }
}
```

### 4-4. 테스트 전략

| 모듈 | TX 격리 | 테스트 베이스 클래스 |
|---|---|---|
| `mvc-jdbc` | `@Transactional` rollback per test | `AbstractMvcJdbcTest` |
| `mvc-virtualthread` | `@BeforeEach truncateAll()` + re-seed | `AbstractMvcVirtualThreadTest` |
| `webflux-r2dbc` | `@BeforeEach truncateAll()` via `suspendTransaction` | `AbstractWebfluxR2dbcTest` |

공통 규칙:
- `@TestInstance(Lifecycle.PER_CLASS)`
- `PostgreSQLServer.Launcher.postgres` 싱글턴 (직접 `GenericContainer` 인스턴스화 금지)
- `WebTestClient` (`shared` 모듈 확장함수 활용)
- `runTest {}` 코루틴 테스트
- bluetape4k assertions (`shouldBeEqualTo`, `shouldHaveSize`, etc.)

테스트 클래스 (모듈별):
- `AuthorControllerTest`, `BookControllerTest`
- `OrderControllerTest`, `ProductControllerTest`
- `AuthorRepositoryTest`, `OrderRepositoryTest`
- `PlaceOrderRollbackTest` — 핵심: 중간 실패 시 전체 롤백 검증

---

## 5. 파일 목록

### 삭제

```
exposed/domain/
exposed/dao-web-transaction/
exposed/spring-transaction/
exposed/sql-web-virtualthread/
exposed/sql-webflux-coroutines/
```

### 신규 (모듈당 ~35개 파일)

```
exposed/mvc-jdbc/
├── README.md
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── kotlin/io/bluetape4k/workshop/exposed/mvc/jdbc/
    │   │   ├── ExposedMvcJdbcApp.kt
    │   │   ├── author/controller/{AuthorController,BookController}.kt
    │   │   ├── author/dto/{AuthorDTO,BookDTO,AuthorWithBooksDTO,AuthorMappers}.kt
    │   │   ├── author/repository/{AuthorRepository,BookRepository}.kt
    │   │   ├── author/schema/{AuthorTable,BookTable}.kt
    │   │   ├── author/service/AuthorService.kt
    │   │   ├── order/controller/{OrderController,ProductController}.kt
    │   │   ├── order/dto/{OrderDTO,OrderLineDTO,ProductDTO,PlaceOrderRequest,OrderWithLinesDTO,OrderMappers}.kt
    │   │   ├── order/repository/{OrderRepository,OrderLineRepository,ProductRepository}.kt
    │   │   ├── order/schema/{OrderTable,OrderLineTable,ProductTable,OrderStatus}.kt
    │   │   ├── order/service/OrderService.kt
    │   │   └── support/{DatabaseConfig,DatabaseInitializer,SwaggerConfig,GlobalExceptionHandler}.kt
    │   └── resources/{application.yml,logback-spring.xml}
    └── test/
        ├── kotlin/io/bluetape4k/workshop/exposed/mvc/jdbc/
        │   ├── AbstractMvcJdbcTest.kt
        │   ├── author/{AuthorControllerTest,BookControllerTest,AuthorRepositoryTest}.kt
        │   └── order/{OrderControllerTest,ProductControllerTest,OrderRepositoryTest,PlaceOrderRollbackTest}.kt
        └── resources/{junit-platform.properties,logback-test.xml}

exposed/mvc-virtualthread/  (동일 구조, mvc/vt 패키지, +TomcatConfig, +AsyncConfig)
exposed/webflux-r2dbc/      (동일 구조, webflux/r2dbc 패키지, +ExposedR2dbcConfig, controller=suspend/Flow)
```

### 수정

```
exposed/README.md    (3개 신규 모듈로 재작성)
scripts/smoke-validate.sh    (data-access-full 그룹: 5개 삭제 모듈명 → 3개 신규 모듈명으로 교체)  ← P1-9 fix
docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md    (T3 Full 표: 신규 모듈명 반영)
```

`settings.gradle.kts` 변경 없음 — `includeModules("exposed", false, true)`가 자동 등록.

---

## 6. 의존성 (공통 + 모듈별)

### `exposed/mvc-jdbc/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}
springBoot { mainClass.set("io.bluetape4k.workshop.exposed.mvc.jdbc.ExposedMvcJdbcAppKt") }
configurations { testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get()) }

dependencies {
    implementation(platform(libs.spring.boot4.dependencies))
    testImplementation(project(":shared"))

    implementation(libs.bluetape4k.exposed)
    implementation(libs.bluetape4k.jdbc)
    implementation(libs.bluetape4k.testcontainers)

    implementation(libs.jetbrains.exposed.core)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.jetbrains.exposed.java.time)
    implementation(libs.jetbrains.exposed.spring.boot4.starter)
    implementation(libs.jetbrains.exposed.spring7.transaction)

    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql.driver)
    implementation(libs.testcontainers.postgresql)

    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)    // P1-4 fix: webflux→webmvc (MVC 스택)

    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    developmentOnly(libs.spring.boot.devtools)                  // P1-5 fix: runtimeOnly→developmentOnly

    testImplementation(libs.spring.boot.starter.webflux.lib)   // WebTestClient
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)
}
```

### `exposed/mvc-virtualthread/build.gradle.kts` (추가 의존성)

`mvc-jdbc` 기반에 추가:
```kotlin
implementation(libs.bluetape4k.virtualthread.jdk21)
implementation(libs.spring.boot.starter.validation)   // P1-8 fix: validation starter
// spring-boot-starter-webmvc-lib 유지 (Tomcat)
// jetbrains-exposed-spring-boot4-starter 제거 (수동 Database.connect)
// jetbrains-exposed-spring7-transaction 제거
// springdoc-openapi-starter: webmvc-ui 사용
```

### `exposed/webflux-r2dbc/build.gradle.kts` (교체 의존성)

```kotlin
// 추가
implementation(libs.exposed.r2dbc)            // bluetape4k-exposed-r2dbc
implementation(libs.jetbrains.exposed.r2dbc)
implementation(libs.r2dbc.pool)
implementation(libs.r2dbc.postgresql)
implementation(libs.bluetape4k.coroutines)
implementation(libs.kotlinx.coroutines.core.lib)
implementation(libs.kotlinx.coroutines.reactor)
implementation(libs.reactor.netty)
implementation(libs.reactor.kotlin.extensions)
implementation(libs.spring.boot.starter.validation)      // P1-8 fix: 입력 검증
// 교체: webmvc → webflux
implementation(libs.spring.boot.starter.webflux.lib)
implementation(libs.springdoc.openapi.starter.webflux.ui)  // WebFlux 스택용 springdoc
// 제거: spring-boot-starter-webmvc, Exposed JDBC starter, spring7-transaction
```

> **R2DBC 풀 설정 가이드** (P2-2 fix: 기본값이 너무 작음):
> ```yaml
> # application.yml 권장 설정
> spring:
>   r2dbc:
>     pool:
>       initial-size: 5
>       max-size: 20
>       max-idle-time: 30m
>       max-acquire-time: 3s
>       validation-query: "SELECT 1"
> ```

---

## 7. 오류 처리

### 예외 → HTTP 매핑

| 예외 | HTTP | 설명 |
|---|---|---|
| `NoSuchElementException` | 404 Not Found | 리소스 없음 |
| `IllegalArgumentException` | 400 Bad Request | 입력 형식/값 위반 |
| `InsufficientStockException` | **409 Conflict** | 비즈니스 상태 위반 (재고 부족) — P1-6 fix |
| `ConstraintViolationException` | 400 Bad Request | Jakarta Validation 위반 |
| `Exception` (catch-all) | 500 Internal Server Error | 내부 오류 |

```kotlin
// GlobalExceptionHandler.kt
@RestControllerAdvice
class GlobalExceptionHandler {
    // 에러 응답 envelope (P2-5 fix: 안정적 응답 포맷)
    data class ErrorResponse(val status: Int, val code: String, val message: String)

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException) =
        ResponseEntity.status(404).body(ErrorResponse(404, "NOT_FOUND", "Resource not found"))

    @ExceptionHandler(InsufficientStockException::class)
    fun handleInsufficientStock(ex: InsufficientStockException) =
        ResponseEntity.status(409).body(ErrorResponse(409, "INSUFFICIENT_STOCK", "Insufficient stock"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException) =
        ResponseEntity.status(400).body(ErrorResponse(400, "BAD_REQUEST", ex.message ?: "Bad request"))

    // P1-12 fix: @Valid @RequestBody throws MethodArgumentNotValidException (MVC), NOT ConstraintViolationException
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleBodyValidation(ex: MethodArgumentNotValidException) =
        ResponseEntity.status(400).body(
            ErrorResponse(400, "VALIDATION_FAILED",
                ex.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" })
        )
}

// webflux-r2dbc GlobalExceptionHandler: WebExchangeBindException instead
// @ExceptionHandler(WebExchangeBindException::class) → same 400 / VALIDATION_FAILED mapping
```

> **에러 메시지 가이드** (P1-7 fix): external 응답에 `productId`, DB 내부 상태 노출 금지.
> 내부 로깅에는 상세 정보 포함 가능 (`log.warn { "stock check failed: product=$productId, stock=$stock, req=$qty" }`).

- `suspend` 블록 내 예외: `CancellationException` 먼저 rethrow 후 일반 catch (webflux-r2dbc 전용)
- `mvc-virtualthread`에는 suspend 없음 — `CancellationException` 규칙 불필요 (P2-1 내부 모순 명확화)

---

## 8. README 구조 (모듈별)

1. **Architecture** — Mermaid 다이어그램
2. **Core Features** — 특징 목록
3. **Used Bluetape4k Features** 표

   | Feature | Library | Usage |
   |---|---|---|
   | `KLoggingChannel` | `bluetape4k-logging` | Structured logging in repositories |
   | `VirtualFuture` | `bluetape4k-virtualthread-jdk21` | Non-blocking JDBC on Virtual Threads |
   | `PostgreSQLServer.Launcher` | `bluetape4k-testcontainers` | Singleton Testcontainers pattern |
   | ... | ... | ... |

4. **Usage Examples** — curl 예시, 테스트 예시
5. **Configuration**
6. **Dependencies**

---

## 9. 리스크 및 완화

**스코프 외 명시** (P1-1 fix): 인증/권한(Spring Security, JWT, IDOR 방어)은 이 PR의 스코프 외. `customerId`는 요청 바디에서 수신 (시연용).

| 리스크 | 완화 |
|---|---|
| R2DBC `SchemaUtils` 제한 | One-shot JDBC bootstrap (§4-3 스니펫) |
| VT + `@Transactional` 혼용 | 명시적 금지 + 레이어 분리 (Service에 placeOrder) + `PlaceOrderRollbackTest` |
| TOCTOU 재고 경쟁 | SELECT … FOR UPDATE (모든 3개 모듈, §3, §4-2) |
| `SqlExpressionBuilder.eq` deprecated import | lint + CLAUDE.md 규칙 준수 |
| Implicit receiver shadowing in `insert {}` | 로컬 변수 추출 |
| `exposed/domain` 교육 테스트 소실 | 소실 수용 (#77 결정); 추후 별도 이슈 가능 |
| 3개 모듈 동시 삭제+추가 → 큰 PR | 단일 PR이지만 Phase별로 커밋 분리 |
| CI 실패 시 롤백 방안 (P1-10 fix) | **PR revert**: `git revert <merge-commit>` → 삭제된 5개 모듈 git history 보존, 언제든 복구 가능 |

---

## 10. 검증 명령

```bash
# 모듈 등록 확인
./gradlew projects -q | grep "exposed"
# expected: :exposed-mvc-jdbc, :exposed-mvc-virtualthread, :exposed-webflux-r2dbc

# 개별 테스트
./gradlew :exposed-mvc-jdbc:test
./gradlew :exposed-mvc-virtualthread:test
./gradlew :exposed-webflux-r2dbc:test

# 전체 빌드
./gradlew :exposed-mvc-jdbc:build :exposed-mvc-virtualthread:build :exposed-webflux-r2dbc:build

# Detekt
./gradlew :exposed-mvc-jdbc:detekt :exposed-mvc-virtualthread:detekt :exposed-webflux-r2dbc:detekt
```

---

## Appendix A — Step 1-R Research Summary

- 기존 `sql-web-virtualthread`: `VirtualFuture<T>` + `transaction(db){}` 패턴 확인
- 기존 `dao-web-transaction`: `@Transactional` + `jetbrains-exposed-spring-boot4-starter` 패턴 확인
- `exposed-r2dbc-workshop`: `R2dbcDatabase.connect(connectionPool, config)` + `@Profile("postgres")` + `suspendTransaction` 패턴 확인
- `libs.versions.toml`: R2DBC 라이브러리 (`r2dbc-pool`, `r2dbc-postgresql`, `exposed-r2dbc`) 모두 사용 가능 확인
- `settings.gradle.kts`: `includeModules("exposed", false, true)` 자동 등록 확인

## Appendix B — Review Iteration Log

| Round | Reviewers | P0 | P1 | P2 | P3 | Status |
|---|---|---|---|---|---|---|
| Round 1 | Phase 1 (4×parallel) + Claude Code 6-tier advisor + Phase 2 critic | 3 | 11 | 8 | 2 | Applied |

**Round 1 findings summary**:
- P0-1: TOCTOU stock race → SELECT FOR UPDATE (모든 3개 모듈)
- P0-2: `findAll(): Flow<T>` outside `suspendTransaction` → service에서 `toList()` collect
- P0-3: `PlaceOrderRequest` DTO 미정의 → §3에 명시
- P1-1: IDOR/auth 미정의 → §9 스코프 외 명시
- P1-2: placeOrder가 Repository에 위치 → OrderService로 이동 (mvc-virtualthread)
- P1-3: `KLoggingChannel` in non-suspend context → `KLogging`으로 교체
- P1-4: `springdoc-openapi-starter-webflux-ui` in MVC → `webmvc-ui`
- P1-5: `runtimeOnly(devtools)` → `developmentOnly`
- P1-6: HTTP 400 for stock → 409 + `InsufficientStockException`
- P1-7: error message leaks productId → sanitized external messages
- P1-8: validation starter missing in mvc-vt, webflux-r2dbc → added
- P1-9: `smoke-validate.sh` 삭제 모듈명 참조 → §5 수정 목록에 추가
- P1-10: no rollback plan → §9에 git revert 절차 추가
- P1-11: missing learner decision tree → §4-0 추가

**Round 2**: 미시작 (Phase 3 Codex — agent timeout 발생; 재실행 필요)

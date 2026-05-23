# Issue #103 — Observability End-to-End 예제 설계 (Rev 4)

**작성일**: 2026-05-23  
**브랜치**: `feat/issue-103-observability-e2e`  
**스택**: Kotlin 2.3.21 + Java 25 + Spring Boot 4.0.6 + bluetape4k 1.8.0-SNAPSHOT  
**라이브러리 버그 수정**: `bluetape4k-projects` `fix/observation-coroutines-stop` 브랜치 — `withObservationContextSuspending` happy-path `stop()` 누락 수정 (commit 742551713)

---

## 1. 목적 및 범위

Issue #103: 분산 시스템에서 trace/log/metric 상관관계(correlation)를 엔드-투-엔드로 보여주는 워크샵 예제.

### 두 개 모듈 분리 방침

사용자 요구사항: "예제니까 A 방식, B 방식 (kafka 제외)을 따로 만드는 게 어때?"

| 모듈 | 디렉토리 | 핵심 교육 포인트 |
|------|---------|---------------|
| **A — Focused** | `observability/observability-focused` | HTTP → coroutine service → outbound WebClient 3-hop span tree (인프라 없음) |
| **B — Fullstack** | `observability/observability-fullstack` | HTTP + service + DB (Exposed JDBC) + Redis cache-aside, no Kafka |

### Module A vs 기존 `micrometer-tracing-coroutines` 관계

기존 모듈 `micrometer-tracing-coroutines`는 OTel-Zipkin 전체 스택 (Testcontainer Zipkin, SSL 이슈)을 보여주는 참조 구현. Module A는 **보완(complement)**: Zipkin 없이 `TestObservationRegistry`만으로 3-hop span tree를 검증하는 경량 예제. 중복이 아닌 다른 교육 지점 (인프라 의존 없는 unit-level 검증 vs full-stack 통합).

### 비목표 (명시적 제외)
- Kafka (사용자 명시 제외)
- Zipkin Testcontainer (ZipkinServerLaunchTest SSL 이슈로 @Disabled 상태)
- R2DBC observation proxy
- AOT native compilation

---

## 2. 기술 결정

### 결정 1: 라이브러리 버그 수정 (P0 — 완료)

**문제**: `withObservationContextSuspending` (두 오버로드 모두) happy path에서 `observation.stop()` 미호출 → 모든 성공 스팬이 OTel/Zipkin에 보고되지 않음.

**수정**: `bluetape4k-projects infra/micrometer/.../ObservationCoroutinesSupport.kt` — happy path에 `val result = withContext(...) { block() }; observation.stop(); return result` 추가.  
브랜치: `fix/observation-coroutines-stop` in `bluetape4k-projects` (commit 742551713). 이 브랜치가 머지되고 SNAPSHOT 재발행 후에만 workshop CI 테스트가 통과함 (§8 CI 선행조건 참조).

**영향**: 수정 후 `TestObservationRegistry`의 `hasBeenStopped()` 정상 통과.

### 결정 2: 테스트 관측 방식 — `TestObservationRegistry`

- **선택**: `TestObservationRegistry` + `SimpleTracer` (Zipkin export 없음)
- **이유**: 기존 `ZipkinServerLaunchTest`가 SSL handshake 문제로 `@Disabled`. Testcontainer Zipkin은 CI에서 불안정.
- **테스트 검증 범위**: 수동으로 추가한 span(manual observation) 이름, 시작, 완료 상태 검증. W3C traceparent 전파 검증은 별도 HTTP intercept 테스트로 수행.

### 결정 3: 계측 방식 — `withObservationSuspending` 단독 사용

- **선택**: `io.bluetape4k.micrometer.observation.coroutines.withObservationSuspending`
- **이유**: `@Observed`는 suspend 함수에 작동하지 않음. 수동 `start()/stop()`은 취소 시 누락 위험.
- **단, Unit 반환 시**: `withObservationSuspending<Unit>("name", registry) { ... }` — 명시적 타입 파라미터 사용, bare `null` 금지.

### 결정 4: InventoryClient 엔드포인트 — `/todos/{id}` 사용

- **선택**: `BluetapeHttpServer.jsonplaceholderUrl + /todos/{id}`
- **이유**: `/posts/{id}` 응답 `{id, userId, title, body}` → `Inventory` 필드 불일치로 silent zero-value. `/todos/{id}` 응답 `{id, userId, title, completed}` 도 불일치하므로 `@JsonIgnoreProperties(ignoreUnknown=true)` + `itemId=id`, `available=50` 하드코딩 응답 stub 사용 (WireMock 또는 `BluetapeHttpServer` mock endpoint).
- **대안**: `MockServerContainer`나 `WireMock` — 더 정확하지만 의존성 추가. 결정: **MockMvcServer / WireMock 없이 `application-test.yml`에서 `base-url`을 MockServer로 오버라이드** + `@TestConfiguration`에 inventory 응답 stub `@Bean`. 이를 통해 인프라 추가 없이 명확한 검증 가능.

**구현 방식**: `MockWebServer` (OkHttp) from `libs.bluetape4k.mock.web.server` — `AbstractFocusedTest`에서 MockWebServer 시작 후 `DynamicPropertySource`로 `base-url` 주입.

### 결정 5: W3C TraceContext 전파 — Spring Boot 4 자동 처리

- Spring Boot 4 + `spring-boot-starter-opentelemetry` 자동 구성.
- `WebClient.Builder` (주입된 빈) → W3C `traceparent` 자동 전파.
- 커스텀 `ExchangeFilterFunction` 불필요 → `WebClientConfig.kt` 파일 제거.
- 단, 테스트에서 `traceparent` 헤더 전파 검증 필요 (P1).

### 결정 6: Exposed JDBC wrapping (Module B)

- `withObservationSuspending("user.db.find", registry) { withContext(Dispatchers.IO) { transaction { } } }`
- Observation OUTER, dispatcher INNER — `ObservationThreadLocalAccessor`가 context 유지.
- `WebFlux + blocking JDBC`는 의도적 교육 포인트: "dispatcher boundary crossing" 패턴.

### 결정 7: Redis 에러 처리 — cache fallback (P1 수정)

- Redis 장애 시 hard fail 대신 DB fallback:
  ```kotlin
  val cached = try {
      cache.get(id)
  } catch (e: CancellationException) {
      throw e
  } catch (e: Exception) {
      log.warn(e) { "Redis cache read failed for id=$id, falling back to DB" }
      null
  }
  ```
- `cache.put()` 실패도 soft fail — 로그만 남기고 계속.

---

## 3. Module A: `observability/observability-focused`

### 3.1 흐름도 (span tree)

```
HTTP GET /orders/{id}
  └─ http.server.requests              (자동 — ServerHttpObservationFilter)
      └─ order.service.fetch           (수동 — withObservationSuspending in OrderService)
          └─ http.client.requests      (자동 — 주입된 WebClient.Builder + OTel starter)
              → MockWebServer (Inventory 응답 stub)
```

모든 span에서 `%X{traceId}`, `%X{spanId}` MDC로 로그 상관관계 확인 가능.

### 3.2 패키지 구조

```
io.bluetape4k.workshop.observability.focused
├── FocusedObservabilityApp.kt
├── client/
│   └── InventoryClient.kt
├── controller/
│   └── OrderController.kt
├── model/
│   ├── Inventory.kt
│   └── Order.kt
└── service/
    └── OrderService.kt
```

`WebClientConfig.kt` 제거 — Spring Boot 4 자동 구성 `WebClient.Builder` 직접 사용.

### 3.3 컴포넌트 설계

#### `model/Order.kt`
```kotlin
data class Order(
    val id: Long,
    val itemId: Long,
    val quantity: Int,
    val inventoryAvailable: Int,
) : java.io.Serializable {
    companion object {
        @JvmStatic private val serialVersionUID: Long = 1L
    }
}
```

#### `model/Inventory.kt`
```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class Inventory(
    val itemId: Long,
    val available: Int,
) : java.io.Serializable {
    companion object {
        @JvmStatic private val serialVersionUID: Long = 1L
    }
}
```

#### `client/InventoryClient.kt`
```kotlin
@Component
class InventoryClient(
    builder: WebClient.Builder,
    @Value("\${workshop.observability.inventory.base-url}") private val baseUrl: String,
) {
    companion object : KLoggingChannel()

    private val client = builder.baseUrl(baseUrl).build()

    /**
     * Fetches inventory for the given item.
     * W3C traceparent header is propagated automatically via the injected WebClient.Builder.
     */
    suspend fun fetchInventory(itemId: Long): Inventory? =
        client.get().uri("/inventory/{id}", itemId)
            .retrieve()
            .onStatus({ it.isError }) { resp ->
                resp.createException().flatMap { Mono.error(it) }
            }
            .awaitBodyOrNull<Inventory>()
            .also { if (it == null) log.warn { "fetchInventory returned null for itemId=$itemId" } }
}
```
- `/inventory/{id}` → MockWebServer에서 응답 stub.
- 4xx/5xx는 `onStatus` → `createException()`으로 명시적 에러 전파.
- Manual observation 없음 — `http.client.requests` span 자동 생성.

#### `service/OrderService.kt`
```kotlin
@Service
class OrderService(
    private val inventoryClient: InventoryClient,
    private val observationRegistry: ObservationRegistry,
) {
    companion object : KLoggingChannel()

    /**
     * Fetches order details by combining inventory data.
     * Produces manual span 'order.service.fetch' wrapping the outbound WebClient call.
     */
    suspend fun getOrder(orderId: Long): Order? =
        withObservationSuspending("order.service.fetch", observationRegistry) {
            log.debug { "Fetching order id=$orderId" }
            val inventory = inventoryClient.fetchInventory(orderId) ?: return@withObservationSuspending null
            Order(
                id = orderId,
                itemId = inventory.itemId,
                quantity = 1,
                inventoryAvailable = inventory.available,
            )
        }
}
```
- **반환 타입 `Order?`** (아키텍처 일관성 — null coercion 금지).
- `log.debug`에서 `MDC.get("traceId")` 직접 읽기 금지 — logback `%X{traceId}` 패턴 사용.

#### `controller/OrderController.kt`
```kotlin
@RestController
@RequestMapping("/orders")
class OrderController(private val orderService: OrderService) {
    @GetMapping("/{id}")
    suspend fun getOrder(@PathVariable id: Long): ResponseEntity<Order> {
        val order = orderService.getOrder(id)
        return if (order != null) ResponseEntity.ok(order) else ResponseEntity.notFound().build()
    }
}
```

### 3.4 `build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.observability.focused.FocusedObservabilityAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.micrometer.bom))
    implementation(platform(libs.micrometer.tracing.bom))

    // bluetape4k
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.micrometer)
    implementation(libs.bluetape4k.jackson3)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.mock.web.server)
    testImplementation(project(":shared"))

    // Observation / Tracing
    implementation(libs.micrometer.observation.lib)
    testImplementation(libs.micrometer.observation.test)
    implementation(libs.micrometer.tracing.lib)
    testImplementation(libs.micrometer.tracing.test)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.micrometer.context.propagation)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry.lib)
    testImplementation(libs.spring.boot.starter.opentelemetry.test)
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines / Reactor
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    implementation(libs.reactor.netty)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)
}
```

### 3.5 리소스 파일

#### `src/main/resources/application.yml`
```yaml
spring:
  application:
    name: observability-focused-demo

workshop:
  observability:
    inventory:
      base-url: http://localhost:8080  # test에서 DynamicPropertySource로 오버라이드

management:
  endpoints:
    web:
      exposure:
        include: '*'
  tracing:
    enabled: true
    sampling:
      probability: 1.0
  otlp:
    tracing:
      export:
        enabled: false  # no external export in demo mode
  metrics:
    tags:
      application: ${spring.application.name}
```

#### `src/main/resources/logback-spring.xml` (핵심: `%X{traceId}` `%X{spanId}` 모두 MDC)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <property name="CONSOLE_LOG_PATTERN"
              value="%clr(%d{HH:mm:ss.SSS}){faint} %clr(%5p) %clr([%X{traceId:-},%X{spanId:-}]){yellow} %clr(---){faint} %clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n"/>
    <appender name="console" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${CONSOLE_LOG_PATTERN}</pattern>
        </encoder>
    </appender>
    <logger name="io.bluetape4k.workshop" level="DEBUG"/>
    <root level="INFO">
        <appender-ref ref="console"/>
    </root>
</configuration>
```

#### `src/test/resources/application-test.yml`
```yaml
spring:
  application:
    name: observability-focused-test   # 명시 — main profile 상속에 의존하지 않음

management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      export:
        enabled: false   # 테스트 중 OTLP export 비활성화
```

#### `src/test/resources/junit-platform.properties`
```properties
junit.jupiter.testinstance.lifecycle.default=per_class
junit.jupiter.execution.parallel.enabled=false
```

#### `src/test/resources/logback-test.xml`
Main `logback-spring.xml`과 동일 내용 — 명시적으로 복사 (byte-identical).
`%X{traceId:-}` / `%X{spanId:-}` MDC syntax 확인 필수 (property substitution `${...}` 금지).

### 3.6 테스트 전략

#### `AbstractFocusedTest.kt`
```kotlin
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractFocusedTest {
    companion object : KLogging() {  // test base class: not coroutine-heavy → KLogging
        // Shared across all subclasses (serial execution, PER_CLASS).
        // NOTE: do NOT @AfterAll shutdown here — multiple subclasses share this instance.
        // JVM shutdown cleans up; MockWebServer is lightweight.
        val mockServer = MockWebServer()

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("workshop.observability.inventory.base-url") { mockServer.url("/").toString() }
        }
    }

    @Autowired protected lateinit var context: ApplicationContext
    protected val webTestClient: WebTestClient by lazy { WebTestClient.bindToApplicationContext(context).build() }

    @BeforeEach
    fun setup() {
        // MockWebServer에 inventory 응답 stub 등록
        val body = """{"itemId":1,"available":50}"""
        mockServer.enqueue(MockResponse().setBody(body).addHeader("Content-Type", "application/json"))
    }

    @AfterEach
    fun resetMockServerDispatcher() {
        // response-queue 초기화 — 다음 테스트에 잔류 응답 오염 방지
        // (takeRequest drains received requests; QueueDispatcher() resets the response queue)
        mockServer.dispatcher = QueueDispatcher()
    }
}
```

#### `OrderServiceTest` — 핵심 검증
```kotlin
@TestConfiguration
class TestObservationConfig {
    @Bean @Primary
    fun testObservationRegistry(): TestObservationRegistry = TestObservationRegistry.create()
}

@Import(TestObservationConfig::class)
class OrderServiceTest : AbstractFocusedTest() {
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var testRegistry: TestObservationRegistry

    @AfterEach
    fun assertNoLeakedObservation() {
        ObservationRegistryAssert.assertThat(testRegistry)
            .doesNotHaveAnyRemainingCurrentObservation()
    }

    @BeforeEach
    fun clearRegistry() {
        testRegistry.clear()
    }

    @Test
    fun `getOrder - order.service.fetch span started and stopped`() = runSuspendIO {
        val result = orderService.getOrder(42L)
        result.shouldNotBeNull()
        result.inventoryAvailable shouldBeEqualTo 50

        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("order.service.fetch")
            .that()
            .hasBeenStarted()
            .hasBeenStopped()
    }

    @Test
    fun `getOrder - returns null when inventory client returns null`() = runSuspendIO {
        mockServer.enqueue(MockResponse().setResponseCode(404))
        testRegistry.clear()
        val result = orderService.getOrder(999L)
        result.shouldBeNull()
        // span must still be stopped even on null result (no error)
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("order.service.fetch").that().hasBeenStopped()
        testRegistry.observations.all { it.context.error == null }.shouldBeTrue()
    }

    @Test
    fun `getOrder - observation records error on 5xx`() = runSuspendIO {
        // Stub 5xx error → WebClient throws; observation should capture error
        mockServer.enqueue(MockResponse().setResponseCode(500))
        testRegistry.clear()
        // Use explicit try/catch — runCatching swallows CancellationException (forbidden in suspend)
        try {
            orderService.getOrder(1L)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // expected — 5xx triggers exception in WebClient
        }
        // span must be stopped and carry the error
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("order.service.fetch").that().hasBeenStopped()
        testRegistry.observations
            .filter { it.context.name == "order.service.fetch" }
            .any { it.context.error != null }
            .shouldBeTrue()
    }

    @Test
    fun `getOrder - observation stopped even on cancellation`() = runSuspendIO {
        // Use slow stub so cancel races the in-flight request reliably
        mockServer.enqueue(
            MockResponse()
                .setBody("""{"itemId":1,"available":50}""")
                .addHeader("Content-Type", "application/json")
                .setBodyDelay(500, TimeUnit.MILLISECONDS)
        )
        // supervisorScope: child cancellation doesn't propagate to parent scope
        supervisorScope {
            val job = launch(Dispatchers.Default) {
                orderService.getOrder(1L)
            }
            delay(50)  // let coroutine start and issue WebClient request
            job.cancelAndJoin()
            job.isCancelled.shouldBeTrue()
        }
        // After cancel, no observation should remain open
        ObservationRegistryAssert.assertThat(testRegistry)
            .doesNotHaveAnyRemainingCurrentObservation()
    }
}
```

#### `OrderControllerTest` — W3C propagation 검증 포함
```kotlin
@Import(TestObservationConfig::class)
class OrderControllerTest : AbstractFocusedTest() {
    @Autowired lateinit var testRegistry: TestObservationRegistry

    @BeforeEach
    fun clearRegistry() { testRegistry.clear() }

    @AfterEach
    fun assertNoLeakedObservation() {
        ObservationRegistryAssert.assertThat(testRegistry)
            .doesNotHaveAnyRemainingCurrentObservation()
    }

    @Test
    fun `GET orders id - 200 OK with order.service.fetch span`() = runSuspendIO {
        webTestClient.httpGet("/orders/42")
            .expectStatus().is2xxSuccessful()
            .expectBody<Order>()
            .consumeWith { resp ->
                resp.responseBody.shouldNotBeNull()
                resp.responseBody!!.inventoryAvailable shouldBeEqualTo 50
            }

        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("order.service.fetch")
    }

    @Test
    fun `GET orders id - traceparent header propagated to downstream`() = runSuspendIO {
        // @BeforeEach already enqueued one response — use it directly
        webTestClient.httpGet("/orders/1").exchange()

        val request = mockServer.takeRequest(1, TimeUnit.SECONDS)
        request.shouldNotBeNull()
        // W3C traceparent must be present — auto-propagated by Spring Boot OTel
        request.getHeader("traceparent").shouldNotBeNull()
    }
}
```

---

## 4. Module B: `observability/observability-fullstack`

### 4.1 흐름도 (span tree)

Cache miss 경로:
```
HTTP GET /users/{id}
  └─ http.server.requests          (자동)
      └─ user.service.get          (수동)
          ├─ user.cache.get        (수동 — null → miss)
          ├─ user.db.find          (수동 — Exposed JDBC + withContext(IO))
          └─ user.cache.put        (수동 — Redis RMapCache populate)

HTTP GET /users/{id} (cache hit):
  └─ http.server.requests
      └─ user.service.get
          └─ user.cache.get        (수동 — User 반환)
```

POST `/users/{id}` 경로:
```
HTTP POST /users
  └─ http.server.requests          (자동)
      └─ user.service.create       (수동)
          └─ user.db.save          (수동 — Exposed JDBC + withContext(IO))
```

### 4.2 패키지 구조

```
io.bluetape4k.workshop.observability.fullstack
├── FullstackObservabilityApp.kt
├── config/
│   ├── RedissonConfig.kt
│   └── SchemaInitializer.kt
├── controller/
│   └── UserController.kt
├── model/
│   ├── User.kt
│   └── Users.kt
├── repository/
│   ├── UserCacheRepository.kt
│   └── UserRepository.kt
└── service/
    └── UserService.kt
```

`DataSourceConfig.kt` 제거 — `jetbrains-exposed-spring-boot4-starter`가 `spring.datasource.*`에서 자동 구성.

### 4.3 컴포넌트 설계

#### `model/User.kt`
```kotlin
data class User(
    val id: Long,
    val name: String,
    val email: String,
) : java.io.Serializable {
    companion object {
        @JvmStatic private val serialVersionUID: Long = 1L
    }
}
```

#### `model/Users.kt` (Exposed Table)
```kotlin
object Users : Table("users") {
    val id = long("id")
    val name = varchar("name", 100)
    val email = varchar("email", 200)
    override val primaryKey = PrimaryKey(id)
}

fun ResultRow.toUser() = User(
    id = this[Users.id],
    name = this[Users.name],
    email = this[Users.email],
)
```

#### `repository/UserRepository.kt`
```kotlin
@Repository
class UserRepository {
    companion object : KLoggingChannel()

    suspend fun findById(id: Long): User? = withContext(Dispatchers.IO) {
        transaction {
            Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUser()
        }
    }

    suspend fun save(user: User): Unit = withContext(Dispatchers.IO) {
        transaction {
            Users.insert {
                it[id] = user.id
                it[name] = user.name
                it[email] = user.email
            }
        }
    }
}
```

#### `repository/UserCacheRepository.kt`
```kotlin
@Repository
class UserCacheRepository(
    private val redisson: RedissonClient,
    private val observationRegistry: ObservationRegistry,
) {
    companion object : KLoggingChannel()

    private val cache: RMapCache<Long, User> by lazy {
        redisson.getMapCache("workshop:observability:users")
    }

    suspend fun get(id: Long): User? =
        withObservationSuspending("user.cache.get", observationRegistry) {
            withContext(Dispatchers.IO) { cache[id] }
        }

    suspend fun put(user: User, ttlSeconds: Long = 60L) {
        withObservationSuspending<Unit>("user.cache.put", observationRegistry) {
            withContext(Dispatchers.IO) {
                cache.put(user.id, user, ttlSeconds, TimeUnit.SECONDS)
            }
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { cache.remove(id) }
}
```
- `withObservationSuspending<Unit>` — 명시적 타입 파라미터, bare `null` 금지.
- `delete()` — 테스트 격리용, observation 불필요 (내부 유틸).

#### `service/UserService.kt`
```kotlin
@Service
class UserService(
    private val repo: UserRepository,
    private val cache: UserCacheRepository,
    private val observationRegistry: ObservationRegistry,
) {
    companion object : KLoggingChannel()

    suspend fun getById(id: Long): User? =
        withObservationSuspending("user.service.get", observationRegistry) {
            val cached = try {
                cache.get(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Redis cache read failed for id=$id, falling back to DB" }
                null
            }

            cached ?: run {
                val fromDb = withObservationSuspending("user.db.find", observationRegistry) {
                    repo.findById(id)
                }
                fromDb?.also {
                    try {
                        cache.put(it)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "Redis cache write failed for id=${it.id}" }
                    }
                }
            }
        }

    suspend fun create(user: User): User =
        requireNotNull(
            withObservationSuspending("user.service.create", observationRegistry) {
                withObservationSuspending("user.db.save", observationRegistry) {
                    repo.save(user)
                    user
                }
            }
        ) { "user.service.create returned null — observation contract violated" }
}
```

#### `controller/UserController.kt`
```kotlin
@RestController
@RequestMapping("/users")
class UserController(private val userService: UserService) {
    @GetMapping("/{id}")
    suspend fun getUser(@PathVariable id: Long): ResponseEntity<User> {
        val user = userService.getById(id)
        return if (user != null) ResponseEntity.ok(user) else ResponseEntity.notFound().build()
    }

    @PostMapping
    suspend fun createUser(@RequestBody user: User): ResponseEntity<User> =
        ResponseEntity.status(HttpStatus.CREATED).body(userService.create(user))
}
```

#### `config/RedissonConfig.kt`
```kotlin
@Configuration(proxyBeanMethods = false)
class RedissonConfig {
    @Bean(destroyMethod = "shutdown")
    fun redissonClient(
        @Value("\${workshop.observability.redis.url}") url: String,
    ): RedissonClient = redissonClient {
        useSingleServer().setAddress(url)
    }
}
```

#### `config/SchemaInitializer.kt`
```kotlin
@Component
class SchemaInitializer : ApplicationRunner {
    companion object : KLogging()   // blocking class → KLogging not KLoggingChannel

    override fun run(args: ApplicationArguments) {
        try {
            transaction {
                SchemaUtils.create(Users)
                Users.selectAll().limit(0).toList()  // verify table accessible
            }
            log.info { "DB schema initialized: Users table is accessible." }
        } catch (e: Exception) {
            log.error(e) { "DB schema initialization FAILED — application may not function correctly" }
            throw e  // fail fast
        }
    }
}
```

### 4.4 `build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.observability.fullstack.FullstackObservabilityAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.micrometer.bom))
    implementation(platform(libs.micrometer.tracing.bom))

    // bluetape4k
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.micrometer)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.redis)
    implementation(libs.bluetape4k.redisson)
    implementation(libs.bluetape4k.idgenerators)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(project(":shared"))

    // Exposed JDBC (bluetape4k wrappers + JetBrains starters)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.spring.boot4.starter)
    implementation(libs.jetbrains.exposed.spring7.transaction)

    // DB / Pool
    implementation(libs.hikaricp)
    runtimeOnly(libs.h2.v2)

    // Redisson
    implementation(libs.redisson.lib)

    // Observation / Tracing
    implementation(libs.micrometer.observation.lib)
    testImplementation(libs.micrometer.observation.test)
    implementation(libs.micrometer.tracing.lib)
    testImplementation(libs.micrometer.tracing.test)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.micrometer.context.propagation)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry.lib)
    testImplementation(libs.spring.boot.starter.opentelemetry.test)
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines / Reactor
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    implementation(libs.reactor.netty)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)
}
```

### 4.5 리소스 파일

#### `src/main/resources/application.yml`
```yaml
spring:
  application:
    name: observability-fullstack-demo
  datasource:
    url: jdbc:h2:mem:observability;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
    username: sa
    password: ""
    driver-class-name: org.h2.Driver

workshop:
  observability:
    redis:
      url: redis://localhost:6379

management:
  endpoints:
    web:
      exposure:
        include: '*'
  tracing:
    enabled: true
    sampling:
      probability: 1.0
  otlp:
    tracing:
      export:
        enabled: false
  metrics:
    tags:
      application: ${spring.application.name}
```

#### `src/main/resources/logback-spring.xml`
Module A의 `logback-spring.xml`과 동일 (`%X{traceId}`, `%X{spanId}` 모두 올바른 MDC 문법).

#### `src/test/resources/application-test.yml`
```yaml
spring:
  application:
    name: observability-fullstack-test   # 명시 — main profile 상속에 의존하지 않음

management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      export:
        enabled: false   # 테스트 중 OTLP export 비활성화
```

#### `src/test/resources/junit-platform.properties`, `logback-test.xml`
Module A와 동일.

### 4.6 테스트 전략

#### `AbstractFullstackTest.kt`
```kotlin
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractFullstackTest {
    companion object : KLogging() {  // test base class: not coroutine-heavy → KLogging
        private val redis = RedisServer.Launcher.redis

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("workshop.observability.redis.url") { redis.url }
        }
    }
    @Autowired protected lateinit var context: ApplicationContext
    protected val webTestClient: WebTestClient by lazy { WebTestClient.bindToApplicationContext(context).build() }
}
```

#### `UserServiceTest` — 핵심 검증
```kotlin
@TestConfiguration
class TestObservationConfig {
    @Bean @Primary
    fun testObservationRegistry(): TestObservationRegistry = TestObservationRegistry.create()
}

@Import(TestObservationConfig::class)
class UserServiceTest : AbstractFullstackTest() {
    @Autowired lateinit var service: UserService
    @Autowired lateinit var repo: UserRepository
    @Autowired lateinit var cache: UserCacheRepository
    @Autowired lateinit var testRegistry: TestObservationRegistry

    private val testUser = User(id = 1001L, name = "alice", email = "alice@example.com")

    @BeforeEach
    fun setup() = runSuspendIO {
        testRegistry.clear()
        withContext(Dispatchers.IO) { transaction { Users.deleteAll() } }  // transaction is blocking
        cache.delete(testUser.id)
    }

    @AfterEach
    fun assertNoLeakedObservation() {
        ObservationRegistryAssert.assertThat(testRegistry)
            .doesNotHaveAnyRemainingCurrentObservation()
    }

    @Test
    fun `getById - cache miss instruments expected spans`() = runSuspendIO {
        repo.save(testUser)
        val result = service.getById(testUser.id)
        result shouldBeEqualTo testUser

        // Use name-based assertions only; count may vary due to auto-spans
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.get").that().hasBeenStarted().hasBeenStopped()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.db.find").that().hasBeenStarted().hasBeenStopped()
        // DB path implies no db.find span is absent (cache miss path)
        testRegistry.observations.any { it.context.name == "user.db.find" }.shouldBeTrue()
    }

    @Test
    fun `getById - cache hit skips db span`() = runSuspendIO {
        // Seed cache directly
        cache.put(testUser)
        testRegistry.clear()

        val result = service.getById(testUser.id)
        result shouldBeEqualTo testUser

        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.get").that().hasBeenStarted().hasBeenStopped()
        // Cache hit: no DB observation
        testRegistry.observations.none { it.context.name == "user.db.find" }.shouldBeTrue()
    }

    @Test
    fun `getById - returns null for non-existent user`() = runSuspendIO {
        val result = service.getById(99999L)
        result.shouldBeNull()
        // No error in observations
        testRegistry.observations.all { it.context.error == null }.shouldBeTrue()
    }

    @Test
    fun `create - produces user.service.create and user.db.save observations`() = runSuspendIO {
        val created = service.create(testUser)
        created shouldBeEqualTo testUser

        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.create").that().hasBeenStopped()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.db.save").that().hasBeenStopped()
    }
}
```

#### `UserControllerTest`
```kotlin
@Import(TestObservationConfig::class)
class UserControllerTest : AbstractFullstackTest() {
    @Autowired lateinit var testRegistry: TestObservationRegistry

    @BeforeEach
    fun setup() = runSuspendIO {
        testRegistry.clear()
        withContext(Dispatchers.IO) { transaction { Users.deleteAll() } }  // transaction is blocking
    }

    @AfterEach
    fun assertNoLeakedObservation() {
        ObservationRegistryAssert.assertThat(testRegistry)
            .doesNotHaveAnyRemainingCurrentObservation()
    }

    @Test
    fun `POST users then GET - cache hit path`() = runSuspendIO {
        val user = User(id = 2001L, name = "bob", email = "bob@example.com")
        webTestClient.post().uri("/users").bodyValue(user)
            .exchange().expectStatus().isCreated

        testRegistry.clear()
        // First GET — cache miss: must hit DB
        webTestClient.httpGet("/users/${user.id}").expectStatus().is2xxSuccessful()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.get").that().hasBeenStopped()
        testRegistry.observations.any { it.context.name == "user.db.find" }.shouldBeTrue()

        testRegistry.clear()
        // Second GET — cache hit: must NOT hit DB
        webTestClient.httpGet("/users/${user.id}").expectStatus().is2xxSuccessful()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.get").that().hasBeenStopped()
        testRegistry.observations.none { it.context.name == "user.db.find" }.shouldBeTrue()
    }
}
```

---

## 5. 리스크 및 완화 (업데이트)

| 리스크 | 심각도 | 완화 |
|-------|------|------|
| `withObservationContextSuspending` happy-path stop() 누락 | P0 → **수정 완료** (branch `fix/observation-coroutines-stop` commit 742551713) | 머지 + SNAPSHOT 재발행 후 CI 통과 (§8 참조) |
| `TestObservationRegistry` 테스트 간 미초기화 | P0 | `@BeforeEach testRegistry.clear()` 필수 |
| Redis/H2 상태 누출 | P0 | `@BeforeEach`: `Users.deleteAll()` + `cache.delete(id)` |
| MockWebServer 응답 불일치 | P0 | MockWebServer `/inventory/{id}` stub, 명시적 JSON 응답 |
| `runCatching {}` in suspend | P0 | `withObservationSuspending`가 이미 try/catch 내부. service에서 `runCatching {}` 사용 금지 |
| Redis 장애 → hard fail | P1 | cache get/put 모두 soft-fail + log.warn + DB fallback |
| `@Observed` on suspend | P1 | 모든 계측에 `withObservationSuspending` 사용. `@Observed` 완전 제외 |
| Exposed JDBC on reactor thread | P1 | 모든 `transaction { }` → `withContext(Dispatchers.IO)` 내부 |
| Observation wrapping order | P1 | `withObservation { withContext(IO) { } }` 순서 고정 |

---

## 6. DoD 기준

### Module A
- [ ] `./gradlew :observability-focused:compileKotlin` 성공
- [ ] `./gradlew :observability-focused:test` — 모든 테스트 통과
- [ ] `OrderServiceTest`: `order.service.fetch` 관측 시작 + 완료 검증
- [ ] `OrderControllerTest`: GET /orders/{id} 200 OK + `traceparent` 헤더 전파 검증
- [ ] logback-spring.xml: `%X{traceId}`, `%X{spanId}` 올바른 MDC 문법
- [ ] README.md (English) + README.ko.md (span tree 다이어그램 포함)

### Module B
- [ ] `./gradlew :observability-fullstack:compileKotlin` 성공
- [ ] `./gradlew :observability-fullstack:test` — 모든 테스트 통과
- [ ] `UserServiceTest`: cache miss 경로에 `user.service.get` + `user.db.find` span 포함, cache hit 경로에 `user.db.find` span 미포함 (이름 기반 검증 — 개수 기반 금지)
- [ ] `UserControllerTest`: POST + GET miss/hit 시나리오 통과
- [ ] Testcontainers Redis 연동 확인
- [ ] README.md (English) + README.ko.md (cache-aside span tree 다이어그램 포함)

---

## 7. CI 후속 작업 (이 PR 범위 외)

- `.github/workflows/ci.yml` — `:observability-focused:build`, `:observability-fullstack:build` 추가
- `.github/workflows/nightly-tests.yml` — `:observability-focused:test`, `:observability-fullstack:test` 추가
- `smoke-validate.sh` observability 그룹 — 신규 모듈 등록
- CLAUDE.md 모듈 테이블 업데이트

---

## 8. CI 선행조건 (크로스-저장소 결합)

> ⚠️ **이 섹션은 구현자가 반드시 확인해야 할 선행조건입니다.**

Workshop CI가 `./gradlew :observability-focused:test` / `:observability-fullstack:test` 를 통과하려면:

1. **`bluetape4k-projects/fix/observation-coroutines-stop` 브랜치 (commit 742551713) 머지 필수**  
   — `withObservationContextSuspending` happy-path `stop()` 버그 수정 포함.  
   — 이 변경이 없으면 모든 `hasBeenStopped()` 단언이 실패.

2. **`1.8.0-SNAPSHOT` 재발행 필수**  
   — 머지 후 `./gradlew :bluetape4k-micrometer:publishToMavenLocal` (로컬) 또는  
     Sonatype Snapshots CI 자동 발행 대기.  
   — Workshop `gradle.properties`: `bluetape4k.version=1.8.0-SNAPSHOT`

3. **검증 순서**  
   ```bash
   # 1. bluetape4k-projects 에서
   cd .worktrees/fix-observation-coroutines-stop
   ./gradlew :bluetape4k-micrometer:publishToMavenLocal
   
   # 2. workshop worktree 에서
   cd <workshop>/.worktrees/feat/issue-103-observability-e2e
   ./gradlew :observability-focused:test :observability-fullstack:test
   ```

---

## Appendix: Step 2-R 리뷰 이력

### Round 1 (2026-05-23)

| Reviewer | P0 | P1 | P2 | P3 |
|----------|----|----|----|----|
| Silent Failure Hunter | 2 | 3 | 3 | 2 |
| Kotlin/Coroutine | 0 | 1 | 3 | 1 |
| Architect | 0 | 2 | 4 | 3 |
| Testing | 3 | 4 | 3 | 2 |
| **통합** | **5** | **10** | **13** | **8** |

### Round 1 적용 내역

| Finding | 조치 |
|---------|------|
| P0: 라이브러리 `stop()` 누락 | `bluetape4k-projects` 수정 + 테스트 확인 (9/9 pass) |
| P0: TestObservationRegistry 미초기화 | `@BeforeEach testRegistry.clear()` 추가 |
| P0: Redis/H2 상태 누출 | `@BeforeEach` 상태 정리 추가 |
| P0: InventoryClient endpoint 불일치 | MockWebServer + `/inventory/{id}` stub 방식으로 변경 |
| P1: Module A ↔ 기존 모듈 관계 | "보완(complement)" 명시 |
| P1: `UserService.create()` 관측 없음 | `user.service.create` + `user.db.save` span 추가 |
| P1: Redis 장애 시 hard fail | soft-fail + DB fallback 패턴 추가 |
| P1: `fetchInventory` 4xx/5xx 삼킴 | `onStatus` → `createException()` 추가 |
| P1: `OrderService.getOrder` → `Order?` | 반환 타입 `Order?` 변경, `?: error()` 제거 |
| P1: W3C traceparent 테스트 없음 | `OrderControllerTest`에 traceparent 헤더 검증 추가 |
| P1: 취소 테스트 없음 | `getOrder - observation stopped even on cancellation` 추가 |
| P2: `WebClientConfig` 불필요 | 파일 제거 |
| P2: `DataSourceConfig` 미명세 | 파일 제거 (`exposed-spring-boot4-starter` 자동 구성) |
| P2: `application-test.yml` 누락 | 양쪽 모듈에 추가 |
| P2: Logback `${spanId:-}` 오타 | `%X{spanId:-}`로 수정 |
| P2: `@TestInstance(PER_CLASS)` 누락 | abstract test class에 추가 |
| P2: `SchemaInitializer` KLoggingChannel | `KLogging()`으로 수정 + fail-fast 추가 |
| P2: `withObservationSuspending<Unit>` bare null | `<Unit>` 명시적 타입 파라미터 |

### Round 2 상태

**목표**: 통합 P0=0, P1=0
Round 2 결과: (실행 후 채움)

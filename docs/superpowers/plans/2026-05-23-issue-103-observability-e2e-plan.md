# Plan: Issue #103 — Observability End-to-End Workshop Example

**Date**: 2026-05-23
**Branch**: `feat/issue-103-observability-e2e`
**Spec (Rev 6, approved)**: `docs/superpowers/specs/2026-05-23-issue-103-observability-e2e-design.md`
**Stack**: Kotlin 2.3.21 + Java 25 + Spring Boot 4.0.6 + bluetape4k 1.8.0-SNAPSHOT

---

## Modules

| Module | Directory | Gradle ID |
|--------|-----------|-----------|
| A — Focused | `observability/observability-focused` | `:observability-focused` |
| B — Fullstack | `observability/observability-fullstack` | `:observability-fullstack` |

---

## Phase 0 — Prerequisites (BLOCKING, external)

> Workshop CI cannot pass until both are done. Tracked in spec §8.

1. **`bluetape4k-projects` `fix/observation-coroutines-stop` 머지** (commit 742551713)
   - `withObservationContextSuspending` happy-path `stop()` 누락 버그 수정.
   - 미머지 시 모든 `hasBeenStopped()` 단언 실패.
2. **`1.8.0-SNAPSHOT` 재발행**
   - 머지 후 `./gradlew :bluetape4k-micrometer:publishToMavenLocal` (로컬) 또는 Sonatype Snapshots CI 자동 발행 대기.
   - Workshop `gradle/libs.versions.toml`: `bluetape4k = "1.8.0-SNAPSHOT"` (이미 설정됨).

---

## Non-Goals / Anti-Patterns (DO NOT DO)

> 구현자 드리프트 방지를 위한 명시적 금지 리스트.

- ❌ `WebClientConfig.kt` — Spring Boot 4 + `spring-boot-starter-opentelemetry` 자동 구성이 `WebClient.Builder`에 traceparent propagation 주입. 커스텀 `ExchangeFilterFunction` 불필요.
- ❌ `DataSourceConfig.kt` — `jetbrains-exposed-spring-boot4-starter`가 `spring.datasource.*`에서 자동 구성.
- ❌ Kafka, Zipkin Testcontainer, R2DBC observation proxy, AOT native compilation.
- ❌ MockWebServer shared `@BeforeEach` enqueue stub — happy-path stub이 error-path / cancellation 테스트 오염.
- ❌ `runCatching {}` in suspend code — `CancellationException`을 삼킴. 명시적 try/catch + rethrow.
- ❌ `?: fallback` on `withObservationSuspending` result — `requireNotNull(withObservationSuspending(...)) { "message" }` 사용.
- ❌ Count-based span assertions (`hasNumberOfObservationsEqualTo`) — 이름 기반만 (`hasObservationWithNameEqualTo`).
- ❌ `@Observed` annotation — suspend 함수에 작동하지 않음. `withObservationSuspending` 단독 사용.
- ❌ `runBlocking` in suspend tests — `runSuspendIO {}` (bluetape4k-junit5) 사용.

---

## Implementation Constraints (numbered for task reference)

| # | Constraint |
|---|------------|
| C1 | `withObservationSuspending(name, registry) { block }` — sole manual instrumentation primitive |
| C2 | `withObservationSuspending<Unit>` — explicit type param when Unit return |
| C3 | Observation OUTER, `withContext(Dispatchers.IO)` INNER for Exposed JDBC calls |
| C4 | `TestObservationRegistry` for test assertions (no Zipkin Testcontainer) |
| C5 | `hasObservationWithNameEqualTo("name").that().hasBeenStarted().hasBeenStopped()` — name-based only |
| C6 | `runSuspendIO {}` for all suspend IO tests (not `runBlocking`) |
| C7 | `KLogging()` for test base classes; `KLoggingChannel()` for coroutine service/repo classes |
| C8 | No `runCatching {}` in suspend — explicit try/catch + `CancellationException` rethrow |
| C9 | MockWebServer: NO shared `@BeforeEach` enqueue; each test enqueues its own stub |
| C10 | `@AfterEach resetMockServerDispatcher()` resets `QueueDispatcher()` |
| C11 | Redis soft-fail: `CancellationException` rethrow, other → `log.warn + null` |
| C12 | `withContext(Dispatchers.IO) { transaction { } }` for ALL Exposed calls in suspend context |
| C13 | `@Import(TestObservationConfig::class)` required on every test class |
| C14 | `@AfterEach assertNoLeakedObservation()` — `doesNotHaveAnyRemainingCurrentObservation()` |
| C15 | All data classes implement `java.io.Serializable` with `serialVersionUID` |
| C16 | `requireNotNull(withObservationSuspending(...)) { "message" }` — no `?: fallback` |

---

## Task List

### Phase 1 — Workspace Registration (Verification Only)

#### T1: settings.gradle.kts 등록 확인
- **complexity**: low
- **files**: `settings.gradle.kts`
- `includeModules("observability", false, false)` 라인 존재 확인. 하위 디렉토리 이름 그대로 Gradle 모듈로 자동 등록됨. **수정 불필요**.
- 검증: `./gradlew projects | grep observability` — 두 모듈 자동 등록 확인.

#### T2: gradle/libs.versions.toml 검증
- **complexity**: low
- **files**: `gradle/libs.versions.toml`
- 다음 alias 모두 존재 확인 (수정 불필요):
  - `bluetape4k = "1.8.0-SNAPSHOT"`
  - `bluetape4k-micrometer`, `bluetape4k-redis`, `bluetape4k-redisson`, `bluetape4k-idgenerators`, `bluetape4k-mock-web-server`
  - `redisson-lib`, `h2-v2`
  - `jetbrains-exposed-spring-boot4-starter`, `jetbrains-exposed-spring7-transaction`

---

### Phase 2 — Module A: `observability/observability-focused`

> 인프라 없음 (no DB, no Redis). MockWebServer만 사용. 먼저 구현하여 빠르게 검증.

#### T3: Module A — build.gradle.kts
- **complexity**: medium
- **files**: `observability/observability-focused/build.gradle.kts`
- Plugins: `kotlin.spring`, `spring.boot`.
- `springBoot { mainClass.set("io.bluetape4k.workshop.observability.focused.FocusedObservabilityAppKt") }`.
- `configurations { testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get()) }`.
- `implementation(platform(libs.micrometer.bom))` + `implementation(platform(libs.micrometer.tracing.bom))`.
- bluetape4k: `logging`, `coroutines`, `micrometer`, `jackson3`.
- testImplementation: `bluetape4k.junit5`, `bluetape4k.assertions`, `bluetape4k.testcontainers`, `bluetape4k.mock.web.server`, `project(":shared")`.
- Observation/Tracing: `micrometer.observation.lib`, `micrometer.observation.test`, `micrometer.tracing.lib`, `micrometer.tracing.test`, `micrometer.tracing.bridge.otel`, `micrometer.context.propagation`.
- Spring Boot 4: `autoconfigure.lib`, `autoconfigure.processor`, `configuration.processor`, `devtools`, `starter.actuator`, `starter.opentelemetry.lib`, `starter.opentelemetry.test`, `starter.webflux.lib`, `starter.webflux.test`, `starter.test` (junit/mockito 제외).
- Coroutines: `kotlinx.coroutines.core.lib`, `kotlinx.coroutines.reactor`, `kotlinx.coroutines.test.lib`, `reactor.netty`, `reactor.kotlin.extensions`, `reactor.test`.

#### T4: Module A — FocusedObservabilityApp.kt + 패키지 스켈레톤
- **complexity**: low
- **files**: `observability/observability-focused/src/main/kotlin/io/bluetape4k/workshop/observability/focused/FocusedObservabilityApp.kt`
- `@SpringBootApplication class FocusedObservabilityApp` + top-level `fun main`.
- 디렉토리 생성: `model/`, `client/`, `service/`, `controller/`.

#### T5: Module A — model/Order.kt + model/Inventory.kt
- **complexity**: low
- **files**:
  - `...focused/model/Order.kt`
  - `...focused/model/Inventory.kt`
- **C15 적용**: 두 data class 모두 `java.io.Serializable` + `companion object { @JvmStatic private val serialVersionUID: Long = 1L }`.
- `Order(id: Long, itemId: Long, quantity: Int, inventoryAvailable: Int)`.
- `Inventory(itemId: Long, available: Int)` — `@JsonIgnoreProperties(ignoreUnknown = true)` 부착.
- 영문 KDoc 1줄 요약 + `## Behavior / Contract`.

#### T6: Module A — client/InventoryClient.kt
- **complexity**: medium
- **files**: `...focused/client/InventoryClient.kt`
- `@Component` + 생성자: `builder: WebClient.Builder`, `@Value("\${workshop.observability.inventory.base-url}") private val baseUrl: String`.
- `companion object : KLoggingChannel()` (C7).
- `private val client = builder.baseUrl(baseUrl).build()` — 주입된 builder 사용 (traceparent 자동 전파).
- `suspend fun fetchInventory(itemId: Long): Inventory?` — `/inventory/{id}`.
- **5xx만** `.onStatus({ it.is5xxServerError }) { resp -> resp.createException().flatMap { Mono.error(it) } }`.
- 4xx (404 포함): `awaitBodyOrNull<Inventory>()` → null 반환 ("item not found" semantics).
- `.also { if (it == null) log.warn { "fetchInventory returned null for itemId=$itemId" } }`.
- Manual observation 없음 — `http.client.requests` span은 OTel 자동 생성.
- 영문 KDoc: "W3C traceparent header is propagated automatically via the injected WebClient.Builder."

#### T7: Module A — service/OrderService.kt
- **complexity**: high
- **files**: `...focused/service/OrderService.kt`
- `@Service`, 생성자: `inventoryClient: InventoryClient`, `observationRegistry: ObservationRegistry`.
- `companion object : KLoggingChannel()` (C7).
- `suspend fun getOrder(orderId: Long): Order?` — 반환 타입 `Order?` (null coercion 금지).
- **C1 적용**: `withObservationSuspending("order.service.fetch", observationRegistry) { ... }`.
- 블록 내부: `inventoryClient.fetchInventory(orderId) ?: return@withObservationSuspending null` → `Order(...)`.
- `log.debug` 사용 — `MDC.get("traceId")` 직접 읽기 금지; logback `%X{traceId}` 패턴이 자동 출력.
- **No `runCatching {}`** (C8).
- 영문 KDoc: "Produces manual span 'order.service.fetch' wrapping the outbound WebClient call."

#### T8: Module A — controller/OrderController.kt
- **complexity**: low
- **files**: `...focused/controller/OrderController.kt`
- `@RestController @RequestMapping("/orders")`, 생성자: `orderService: OrderService`.
- `@GetMapping("/{id}") suspend fun getOrder(@PathVariable id: Long): ResponseEntity<Order>`.
- `orderService.getOrder(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()`.

#### T9: Module A — Main resources
- **complexity**: low
- **files**:
  - `...focused/src/main/resources/application.yml`
  - `...focused/src/main/resources/logback-spring.xml`
- `application.yml`: `spring.application.name=observability-focused-demo`, `workshop.observability.inventory.base-url=http://localhost:8080`, `management.tracing.enabled=true`, `management.tracing.sampling.probability=1.0`, `management.otlp.tracing.export.enabled=false`.
- `logback-spring.xml`: **`%X{traceId:-}` `%X{spanId:-}` MDC syntax** (`property substitution ${...}` 금지). Pattern: `%clr([%X{traceId:-},%X{spanId:-}]){yellow}`. `<logger name="io.bluetape4k.workshop" level="DEBUG"/>`.

#### T10: Module A — Test resources
- **complexity**: low
- **files**:
  - `...focused/src/test/resources/application-test.yml`
  - `...focused/src/test/resources/junit-platform.properties`
  - `...focused/src/test/resources/logback-test.xml`
- `application-test.yml`: `spring.application.name=observability-focused-test` 명시, `management.otlp.tracing.export.enabled=false`, `management.tracing.sampling.probability=1.0`.
- `junit-platform.properties`: `junit.jupiter.testinstance.lifecycle.default=per_class`, `junit.jupiter.execution.parallel.enabled=false`.
- `logback-test.xml`: Main `logback-spring.xml`과 byte-identical. `%X{traceId:-}` `%X{spanId:-}` 문법 재확인.

#### T11: Module A — AbstractFocusedTest.kt
- **complexity**: medium
- **files**: `...focused/src/test/kotlin/.../AbstractFocusedTest.kt`
- `@SpringBootTest(webEnvironment = RANDOM_PORT) @ActiveProfiles("test") @TestInstance(PER_CLASS)`.
- `companion object : KLogging()` (C7: test base class — KLogging).
- `val mockServer = MockWebServer()` — 모든 subclass 공유, serial execution.
- **`@AfterAll shutdown 금지`** — 여러 subclass가 instance 공유; JVM shutdown이 정리.
- `@JvmStatic @DynamicPropertySource fun props(registry: DynamicPropertyRegistry)`: `registry.add("workshop.observability.inventory.base-url") { mockServer.url("/").toString() }`.
- `@Autowired protected lateinit var context: ApplicationContext`.
- `protected val webTestClient: WebTestClient by lazy { WebTestClient.bindToApplicationContext(context).build() }`.
- **C9 적용**: `@BeforeEach` enqueue 없음.
- **C10 적용**: `@AfterEach fun resetMockServerDispatcher() { mockServer.dispatcher = QueueDispatcher() }`.
- Helper: `protected fun enqueueSuccessInventory(itemId: Long = 1L, available: Int = 50)` — `{"itemId":...,"available":...}` JSON enqueue.

#### T12 (TEST): Module A — OrderServiceTest
- **complexity**: high
- **files**: `...focused/src/test/kotlin/.../service/OrderServiceTest.kt`
- `@TestConfiguration class TestObservationConfig { @Bean @Primary fun testObservationRegistry(): TestObservationRegistry = TestObservationRegistry.create() }`.
- **C13 적용**: `@Import(TestObservationConfig::class)`.
- `@Autowired lateinit var orderService: OrderService`, `@Autowired lateinit var testRegistry: TestObservationRegistry`.
- `@BeforeEach fun clearRegistry() { testRegistry.clear() }`.
- **C14 적용**: `@AfterEach fun assertNoLeakedObservation() { ObservationRegistryAssert.assertThat(testRegistry).doesNotHaveAnyRemainingCurrentObservation() }`.
- **4 test methods** (C6: 모두 `runSuspendIO {}`):
  1. `` `getOrder - order.service.fetch span started and stopped` `` — `enqueueSuccessInventory()` → call → **C5**: `hasObservationWithNameEqualTo("order.service.fetch").that().hasBeenStarted().hasBeenStopped()`.
  2. `` `getOrder - returns null when inventory client returns null` `` — `mockServer.enqueue(MockResponse().setResponseCode(404))` → result null + span stopped + no error.
  3. `` `getOrder - observation records error on 5xx` `` — `setResponseCode(500)` → **C8**: 명시적 try/catch with `CancellationException` rethrow → span stopped + `context.error != null`.
  4. `` `getOrder - observation stopped even on cancellation` `` — `setBodyDelay(500ms)` + `supervisorScope { launch { ... }; delay(50); job.cancelAndJoin() }` → `doesNotHaveAnyRemainingCurrentObservation()`.

#### T13 (TEST): Module A — OrderControllerTest (W3C propagation 포함)
- **complexity**: high
- **files**: `...focused/src/test/kotlin/.../controller/OrderControllerTest.kt`
- **C13 적용**: `@Import(TestObservationConfig::class)` + extends `AbstractFocusedTest()`.
- `@BeforeEach clearRegistry()` + **C14** `@AfterEach assertNoLeakedObservation()`.
- **2 test methods** (C6: `runSuspendIO {}`):
  1. `` `GET orders id - 200 OK with order.service.fetch span` `` — `enqueueSuccessInventory()` → `webTestClient.httpGet("/orders/42")` → 200 + body + **C5** name assertion.
  2. `` `GET orders id - traceparent header propagated to downstream` `` — `enqueueSuccessInventory()` → GET → `mockServer.takeRequest(1, TimeUnit.SECONDS)` → `request.getHeader("traceparent").shouldNotBeNull()`.

#### T14: Module A — README.md + README.ko.md
- **complexity**: low
- **files**:
  - `observability/observability-focused/README.md` (English)
  - `observability/observability-focused/README.ko.md` (Korean)
- Mermaid span tree:
  ```
  HTTP GET /orders/{id}
    └─ http.server.requests (auto)
        └─ order.service.fetch (manual)
            └─ http.client.requests (auto)
                → MockWebServer
  ```
- Module A는 `micrometer-tracing-coroutines`의 **보완(complement)** — Zipkin 없이 `TestObservationRegistry` 기반 unit-level 검증임을 명시.
- 구조: 1) Architecture diagram, 2) Core features, 3) Usage examples, 4) Configuration, 5) Dependencies.

---

### Phase 3 — Module B: `observability/observability-fullstack`

> H2 (Exposed JDBC) + Redis (Redisson/Testcontainers) + WebFlux. Module A 완료 후 진행.

#### T15: Module B — build.gradle.kts
- **complexity**: medium
- **files**: `observability/observability-fullstack/build.gradle.kts`
- Module A의 모든 의존성 + 추가:
  - bluetape4k: `redis`, `redisson`, `idgenerators`.
  - Exposed: `exposed.core`, `exposed.jdbc`, `jetbrains.exposed.spring.boot4.starter`, `jetbrains.exposed.spring7.transaction`.
  - DB: `hikaricp`, `runtimeOnly(libs.h2.v2)`.
  - Redisson: `redisson.lib`.
- `springBoot { mainClass.set("io.bluetape4k.workshop.observability.fullstack.FullstackObservabilityAppKt") }`.
- **`DataSourceConfig.kt` 작성 금지** — `jetbrains-exposed-spring-boot4-starter` 가 자동 구성.

#### T16: Module B — FullstackObservabilityApp.kt + 패키지 스켈레톤
- **complexity**: low
- **files**: `...fullstack/FullstackObservabilityApp.kt`
- `@SpringBootApplication class FullstackObservabilityApp` + top-level `fun main`.
- 디렉토리 생성: `config/`, `model/`, `repository/`, `service/`, `controller/`.

#### T17: Module B — model/User.kt + model/Users.kt
- **complexity**: low
- **files**:
  - `...fullstack/model/User.kt`
  - `...fullstack/model/Users.kt`
- **C15 적용**: `User(id: Long, name: String, email: String)` — `Serializable` + `serialVersionUID`.
- `object Users : Table("users")` — `long("id")`, `varchar("name", 100)`, `varchar("email", 200)`, `override val primaryKey = PrimaryKey(id)`.
- `fun ResultRow.toUser()` extension.
- Exposed 1.2+ 규칙: top-level operator `eq` import.

#### T18: Module B — config/SchemaInitializer.kt
- **complexity**: medium
- **files**: `...fullstack/config/SchemaInitializer.kt`
- `@Component class SchemaInitializer : ApplicationRunner`.
- `companion object : KLogging()` (C7: blocking class — KLogging).
- `override fun run(args: ApplicationArguments)`: `transaction { SchemaUtils.create(Users); Users.selectAll().limit(0).toList() }`.
- 실패 시 `log.error(e) { ... }` + `throw e` (fail fast, silent swallow 금지).

#### T19: Module B — config/RedissonConfig.kt
- **complexity**: medium
- **files**: `...fullstack/config/RedissonConfig.kt`
- `@Configuration(proxyBeanMethods = false)`.
- `@Bean(destroyMethod = "shutdown") fun redissonClient(@Value("\${workshop.observability.redis.url}") url: String): RedissonClient`.
- bluetape4k DSL: `redissonClient { useSingleServer().setAddress(url) }`.

#### T20: Module B — repository/UserRepository.kt
- **complexity**: medium
- **files**: `...fullstack/repository/UserRepository.kt`
- `@Repository class UserRepository`, `companion object : KLoggingChannel()`.
- **C12 적용**: 모든 method `suspend ... = withContext(Dispatchers.IO) { transaction { ... } }`.
- `suspend fun findById(id: Long): User?` — `Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUser()`.
- `suspend fun save(user: User): Unit` — `Users.insert { it[id] = user.id; ... }`.
- **이 layer는 observation 없음** — observation은 service layer에서 wrap (C3: outer observation).

#### T21: Module B — repository/UserCacheRepository.kt
- **complexity**: medium
- **files**: `...fullstack/repository/UserCacheRepository.kt`
- `@Repository class UserCacheRepository(private val redisson: RedissonClient, private val observationRegistry: ObservationRegistry)`.
- `companion object : KLoggingChannel()`.
- `private val cache: RMapCache<Long, User> by lazy { redisson.getMapCache("workshop:observability:users") }`.
- **C1 + C3 적용**: `suspend fun get(id: Long): User? = withObservationSuspending("user.cache.get", observationRegistry) { withContext(Dispatchers.IO) { cache[id] } }`.
- **C2 적용**: `suspend fun put(user: User, ttlSeconds: Long = 60L) { withObservationSuspending<Unit>("user.cache.put", observationRegistry) { withContext(Dispatchers.IO) { cache.put(user.id, user, ttlSeconds, TimeUnit.SECONDS) } } }` — `<Unit>` 명시적 타입 파라미터.
- `suspend fun delete(id: Long) = withContext(Dispatchers.IO) { cache.remove(id) }` — 테스트 격리용, observation 불필요.

#### T22: Module B — service/UserService.kt
- **complexity**: high
- **files**: `...fullstack/service/UserService.kt`
- `@Service class UserService(repo, cache, observationRegistry)`, `companion object : KLoggingChannel()`.
- `suspend fun getById(id: Long): User?`:
  - 외부 wrap: `withObservationSuspending("user.service.get", observationRegistry) { ... }`.
  - **C11 적용**: cache.get 실패 시 soft-fail:
    ```kotlin
    val cached = try { cache.get(id) }
      catch (e: CancellationException) { throw e }
      catch (e: Exception) { log.warn(e) { "Redis cache read failed for id=$id, falling back to DB" }; null }
    ```
  - Cache miss → `withObservationSuspending("user.db.find", observationRegistry) { repo.findById(id) }`.
  - DB hit → `cache.put(it)` soft-fail (같은 패턴).
- `suspend fun create(user: User): User`:
  - **C16 적용**: `requireNotNull(withObservationSuspending("user.service.create", observationRegistry) { withObservationSuspending("user.db.save", observationRegistry) { repo.save(user); user } }) { "user.service.create returned null — observation contract violated" }`.
- **No `runCatching {}`** (C8) — 모두 명시적 try/catch.

#### T23: Module B — controller/UserController.kt
- **complexity**: low
- **files**: `...fullstack/controller/UserController.kt`
- `@RestController @RequestMapping("/users")`, 생성자: `userService: UserService`.
- `@GetMapping("/{id}") suspend fun getUser(@PathVariable id: Long): ResponseEntity<User>` — null이면 404.
- `@PostMapping suspend fun createUser(@RequestBody user: User): ResponseEntity<User>` — 201 Created.

#### T24: Module B — Main resources
- **complexity**: low
- **files**:
  - `...fullstack/src/main/resources/application.yml`
  - `...fullstack/src/main/resources/logback-spring.xml`
- `application.yml`:
  - `spring.application.name=observability-fullstack-demo`.
  - `spring.datasource.url=jdbc:h2:mem:observability;MODE=PostgreSQL;DB_CLOSE_DELAY=-1`, `username=sa`, `password=""`, `driver-class-name=org.h2.Driver`.
  - `workshop.observability.redis.url=redis://localhost:6379` (테스트에서 Testcontainers URL 오버라이드).
  - `management.tracing.*` + `management.otlp.tracing.export.enabled=false`.
- `logback-spring.xml`: Module A와 동일 (`%X{traceId:-}` `%X{spanId:-}` MDC).

#### T25: Module B — Test resources
- **complexity**: low
- **files**:
  - `...fullstack/src/test/resources/application-test.yml`
  - `...fullstack/src/test/resources/junit-platform.properties`
  - `...fullstack/src/test/resources/logback-test.xml`
- `application-test.yml`: `spring.application.name=observability-fullstack-test` 명시, `management.otlp.tracing.export.enabled=false`.
- Module A와 동일 패턴.

#### T26: Module B — AbstractFullstackTest.kt
- **complexity**: medium
- **files**: `...fullstack/src/test/kotlin/.../AbstractFullstackTest.kt`
- `@SpringBootTest(webEnvironment = RANDOM_PORT) @ActiveProfiles("test") @TestInstance(PER_CLASS)`.
- `companion object : KLogging()` (C7).
- **bluetape4k testcontainers 패턴**: `private val redis = RedisServer.Launcher.redis` — singleton (`@Testcontainers` 불필요).
- `@JvmStatic @DynamicPropertySource fun props(registry)`: `registry.add("workshop.observability.redis.url") { redis.url }`.
- `@Autowired protected lateinit var context: ApplicationContext`.
- `protected val webTestClient by lazy { WebTestClient.bindToApplicationContext(context).build() }`.

#### T27 (TEST): Module B — UserServiceTest
- **complexity**: high
- **files**: `...fullstack/src/test/kotlin/.../service/UserServiceTest.kt`
- `@TestConfiguration class TestObservationConfig` (동일).
- **C13 적용**: `@Import(TestObservationConfig::class)`.
- `@Autowired` service, repo, cache, testRegistry.
- `private val testUser = User(id = 1001L, name = "alice", email = "alice@example.com")`.
- `@BeforeEach fun setup() = runSuspendIO { testRegistry.clear(); withContext(Dispatchers.IO) { transaction { Users.deleteAll() } }; cache.delete(testUser.id) }` — **C12**: `transaction {}` blocking → `withContext(IO)`.
- **C14 적용**: `@AfterEach assertNoLeakedObservation()`.
- **4 test methods** (C6: `runSuspendIO {}`):
  1. `` `getById - cache miss instruments expected spans` `` — `repo.save(testUser)` → call → **C5**: name-based `user.service.get` + `user.db.find` assertions. Count-based **금지**.
  2. `` `getById - cache hit skips db span` `` — `cache.put(testUser)` → `testRegistry.clear()` → call → `user.service.get` stopped, `user.db.find` **없음** 확인.
  3. `` `getById - returns null for non-existent user` `` — call(99999L) → null + no error.
  4. `` `create - produces user.service.create and user.db.save observations` `` — both spans `hasBeenStopped()`.

#### T28 (TEST): Module B — UserControllerTest
- **complexity**: high
- **files**: `...fullstack/src/test/kotlin/.../controller/UserControllerTest.kt`
- **C13 적용**: `@Import(TestObservationConfig::class)` + extends `AbstractFullstackTest()`.
- `@BeforeEach`: `testRegistry.clear()` + `withContext(Dispatchers.IO) { transaction { Users.deleteAll() } }`.
- **C14 적용**: `@AfterEach assertNoLeakedObservation()`.
- **1 test method** (C6: `runSuspendIO {}`):
  1. `` `POST users then GET - cache hit path` `` — POST (201) → `testRegistry.clear()` → GET miss (→ `user.db.find` 존재 확인) → `testRegistry.clear()` → GET hit (→ `user.db.find` 없음 확인). **C5**: name-based only.

#### T29: Module B — README.md + README.ko.md
- **complexity**: low
- **files**:
  - `observability/observability-fullstack/README.md` (English)
  - `observability/observability-fullstack/README.ko.md` (Korean)
- Mermaid 다이어그램 2개:
  - Cache miss: `http.server.requests` → `user.service.get` → `user.cache.get` (null) → `user.db.find` → `user.cache.put`.
  - Cache hit: `http.server.requests` → `user.service.get` → `user.cache.get` (hit).
- 교육 포인트: "dispatcher boundary crossing" (`withObservation { withContext(IO) { transaction { } } }`), Redis soft-fail cache-aside.

---

### Phase 4 — Verification

#### T30 (TEST): Module A 컴파일 + 테스트
- **complexity**: high
- `./gradlew :observability-focused:compileKotlin` → 성공.
- `./gradlew :observability-focused:test` → 모든 테스트 통과.
- DoD (spec §6 Module A):
  - [ ] `OrderServiceTest` 4건 통과 (started/stopped/error/cancellation).
  - [ ] `OrderControllerTest` 2건 통과 (200 OK + traceparent 전파).
  - [ ] IDE diagnostics: 미해결 `@Deprecated` 0건.
- **⚠️ Prerequisite**: Phase 0 `bluetape4k 1.8.0-SNAPSHOT` 재발행 완료 필수.

#### T31 (TEST): Module B 컴파일 + 테스트
- **complexity**: high
- `./gradlew :observability-fullstack:compileKotlin`.
- `./gradlew :observability-fullstack:test`.
- DoD (spec §6 Module B):
  - [ ] `UserServiceTest` 4건 통과.
  - [ ] `UserControllerTest` 1건 통과.
  - [ ] Testcontainers Redis 정상 기동.
- 실행 시간 + 패스 카운트 기록.

#### T32 (REVIEW): 코드 리뷰
- **complexity**: medium
- 모든 `.kt` 파일 `ide_diagnostics` 0 errors / 0 unresolved deprecations.
- `oh-my-claudecode:code-reviewer` — CRITICAL/HIGH 이슈 해결.
- **C1–C16 self-check** (모든 제약이 올바르게 적용됐는지 최종 검토):
  - C1: `withObservationSuspending` — OrderService, UserCacheRepository, UserService.
  - C2: `withObservationSuspending<Unit>` — UserCacheRepository.put.
  - C3: Observation OUTER, `withContext(IO)` INNER.
  - C7: `KLogging()` test base; `KLoggingChannel()` coroutine class.
  - C8: No `runCatching {}` in suspend.
  - C9: No shared `@BeforeEach` MockWebServer enqueue.
  - C10: `QueueDispatcher()` reset in `@AfterEach`.
  - C11: Redis soft-fail + `CancellationException` rethrow.
  - C12: `withContext(IO) { transaction { } }` in all Exposed calls.
  - C13: `@Import(TestObservationConfig::class)` on all 4 test classes.
  - C14: `doesNotHaveAnyRemainingCurrentObservation()` in `@AfterEach`.
  - C15: `Serializable` + `serialVersionUID` on all data classes.
  - C16: `requireNotNull(withObservationSuspending(...)) { "message" }`.

---

## Out-of-PR Scope (spec §7 — 별도 PR)

- `.github/workflows/ci.yml` — `:observability-focused:build`, `:observability-fullstack:build` 추가.
- `.github/workflows/nightly-tests.yml` — 두 모듈 `:test` task 추가.
- `smoke-validate.sh` observability 그룹 — 신규 모듈 등록.
- Root `CLAUDE.md` 모듈 테이블 업데이트.

---

## Build Sequence Summary

```
Phase 0 (BLOCKING, external):  bluetape4k-projects fix 머지 + SNAPSHOT 재발행
   ↓
Phase 1: T1 → T2                    (verification only, no edits)
   ↓
Phase 2: T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 (TEST) → T13 (TEST) → T14
   ↓
Phase 3: T15 → T16 → T17 → T18 → T19 → T20 → T21 → T22 → T23 → T24 → T25 → T26 → T27 (TEST) → T28 (TEST) → T29
   ↓
Phase 4: T30 (TEST) → T31 (TEST) → T32 (REVIEW)
```

## Task Statistics

| Category | Count | Tasks |
|----------|-------|-------|
| complexity: high | 8 | T7, T12, T13, T22, T27, T28, T30, T31 |
| complexity: medium | 11 | T3, T6, T11, T15, T18, T19, T20, T21, T26, T32 + T2 |
| complexity: low | 13 | T1, T4, T5, T8, T9, T10, T14, T16, T17, T23, T24, T25, T29 |
| **Total** | **32** | |

| Type | Count |
|------|-------|
| Implementation | 22 (T1–T11, T14–T26, T29) |
| Test | 6 (T12, T13, T27, T28, T30, T31) |
| Review | 1 (T32) |
| Verification only | 3 (T1, T2, T30/T31 DoD check) |

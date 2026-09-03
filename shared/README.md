# Bluetape4k Workshop Shared

[한국어](README.ko.md) | English

This module provides small HTTP client and integration-test helpers used by workshop examples. It is not a runnable application; it is a shared utility dependency for modules that need concise `RestClient`, `WebClient`, or `WebTestClient` calls.

## Utility Map

![Bluetape4k Workshop Shared utility map](../docs/images/readme-diagrams/shared-readme-architecture-01.png)

## Test Helper Flow

![Bluetape4k Workshop Shared WebTestClient flow](../docs/images/readme-diagrams/shared-readme-test-sequence-01.png)

## Key Features

- **RestClientExtensions**: `RestClient` extension functions (GET, POST, PUT, PATCH, DELETE)
- **WebClientExtensions**: Reactive `WebClient` extension functions
- **WebTestClientExtensions**: Test extension functions for `WebTestClient`
- **AbstractSpringTest**: Base class for Spring integration tests
- **RedisTestSupport**: Shared Redis Testcontainers and Spring dynamic-property helper

## Module Structure

## Provided Utilities

### RestClientExtensions (Synchronous HTTP Client)

Extension functions that wrap Spring `RestClient` method chains in a concise form.

| Function | HTTP Method | Description |
|---|---|---|
| `httpGet(uri, accept?)` | GET | Simple retrieval |
| `httpHead(uri, accept?)` | HEAD | Header-only retrieval |
| `httpPost(uri, value?, ...)` | POST | Send a single object |
| `httpPost<T>(uri, publisher, ...)` | POST | Send a Reactor Publisher stream |
| `httpPost<T>(uri, flow, ...)` | POST | Send a Kotlin Flow stream |
| `httpPut(uri, value?, ...)` | PUT | Update a single object |
| `httpPatch(uri, value?, ...)` | PATCH | Partial update |
| `httpDelete(uri, accept?)` | DELETE | Delete |

### WebClientExtensions (Asynchronous/Reactive HTTP Client)

Extension functions with the same signatures for Spring `WebClient`. `Publisher<T>` and `Flow<T>` overloads simplify reactive and coroutine stream requests.

### WebTestClientExtensions (For Integration Tests)

Extension functions for `WebTestClient` with built-in HTTP status assertions through the `httpStatus` parameter. They reduce `exchange()` + `expectStatus()` calls to one line.

```kotlin
// Usage example
webTestClient.httpGet("/tasks/1", HttpStatus.OK)
    .expectBody<Task>().returnResult()

webTestClient.httpPost("/tasks", task, HttpStatus.CREATED)
```

### RedisTestSupport (Redis Testcontainers)

`RedisTestSupport` reuses `RedisServer.Launcher.redis` and registers the
following Spring `DynamicPropertyRegistry` keys:

- `testcontainers.redis.host`
- `testcontainers.redis.port`
- `testcontainers.redis.url`

Use it from a Redis example test and connect the consumer module to `shared`
with `testImplementation(project(":shared"))`. The consumer must continue to
declare its own Spring Test and Testcontainers runtime dependencies, plus the
optional Spring bridge:

```kotlin
testImplementation(libs.bluetape4k.testcontainers.spring)
```

`registerRedisProperties` delegates to
`PropertyExportingServer.registerDynamicProperties` from
`bluetape4k-testcontainers-spring`. The bridge registers lazy suppliers for the
three keys; it does not start or stop a container, wait for readiness, or write
JVM system properties. Accessing `RedisTestSupport.redis` still uses the shared
`RedisServer.Launcher.redis` singleton and can start Redis, so helper-based
endpoint tests require Docker.

~~~kotlin
import io.bluetape4k.workshop.shared.testcontainers.RedisTestSupport
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@JvmStatic
@DynamicPropertySource
fun redisProperties(registry: DynamicPropertyRegistry) {
    RedisTestSupport.registerRedisProperties(registry)
}
~~~

The Docker-free bridge contract is exercised as one isolated test invocation:

```bash
./gradlew :shared:test \
  --tests '*PropertyExportingServerDynamicPropertyRegistryTest' \
  --tests '*PropertyExportingServerDynamicPropertyRegistryContextTest' \
  --tests '*RedisTestSupportBridgeContractTest'
```

Run `*RedisTestSupportTest` and the full Redis consumer suite separately with a
Docker-capable environment. `propertyKeys()` is a `Set`, so registration order
is not part of the contract; each supplier resolves the current
`properties()` map when Spring evaluates it.

## Usage

The `shared` module is repository-internal workshop support code. The examples in this repository already wire it through Gradle. External projects should copy the helper pattern they need instead of adding a published dependency.

### Extending AbstractSpringTest

Extending the Spring WebFlux integration test base class automatically injects the `WebTestClient` bean.

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractSpringTest {
    @Autowired
    lateinit var webTestClient: WebTestClient
}

class MyControllerTest : AbstractSpringTest() {
    @Test
    fun `find tasks`() {
        webTestClient.httpGet("/tasks", HttpStatus.OK)
            .expectBodyList<Task>().hasSize(2)
    }
}
```

## Build

```bash
./gradlew :shared:build
./gradlew :shared:test
```

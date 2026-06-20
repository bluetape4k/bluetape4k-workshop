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

## Usage

Add the dependency to `build.gradle.kts`.

```kotlin
// For production code
implementation(project(":shared"))

// For test code
testImplementation(project(":shared"))
```

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

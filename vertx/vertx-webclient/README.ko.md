# Vert.x WebClient Examples

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Vert.x WebClient Examples**를 실행 가능한 Vert.x reactive service 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 관찰에 초점을 맞춥니다.

## 아키텍처 다이어그램

![Vert.x WebClient Examples Graphviz architecture diagram](../../docs/images/readme-diagrams/vertx-vertx-webclient-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.vertx` 패키지를 기준으로 삼습니다.

## 시퀀스 다이어그램

![Vert.x WebClient Examples sequence diagram](../../docs/images/readme-diagrams/vertx-vertx-webclient-sequence-01.png)

[Vert.x WebClient](https://vertx.io/docs/vertx-web-client/java/)는 asynchronous, non-blocking WebClient입니다.
Spring WebClient와 유사한 기능을 제공하지만, Reactor 대신 Coroutines로 더 쉽게 구현할 수 있습니다.

## HTTP 요청 처리 흐름

![Vert.x WebClient Examples Diagram 1](../../docs/images/readme-diagrams/vertx-vertx-webclient-readme-sequence-01.png)

## 주요 기능

| 기능 | 설명 |
|------|------|
| HTTP GET | `client.get(port, host, path).send().coAwait()` — non-blocking GET request |
| HTTP PUT/POST | `client.put(...).sendBuffer(body).coAwait()` — body를 포함한 request |
| BodyCodec support | `BodyCodec.string()`, `BodyCodec.jsonObject()`, `BodyCodec.json(Class)` — 자동 response deserialization |
| Coroutine integration | callback 없이 `suspend` 함수처럼 `coAwait()` extension function을 사용합니다 |
| CoroutineVerticle | `CoroutineVerticle`을 확장하면 `start()`를 `suspend`로 선언할 수 있습니다 |
| JSON response mapping | `BodyCodec.json(User::class.java)` — response body를 Jackson으로 domain object에 자동 변환합니다 |

## 예제 파일 구성

| File | 설명 |
|------|------|
| `SimpleExamples.kt` | `AbstractVerticle` 기반 기본 HTTP server + WebClient GET request |
| `CoroutineExamples.kt` | `CoroutineVerticle` 기반 coroutine server + `coAwait()` 사용 |
| `RequestExamples.kt` | `Router` + `BodyHandler`를 등록한 뒤 PUT request로 string body 전송 |
| `ResponseExamples.kt` | JSON response를 `JsonObject`와 custom class로 deserialize |

## 사용 예제

### 기본 GET Request(Coroutine, Callback 없음)

```kotlin
val client = WebClient.create(vertx)
val response = client
    .get(8080, "localhost", "/")
    .`as`(BodyCodec.string())
    .send()
    .coAwait()                       // suspend - no callback required

println(response.body())             // "Hello World!"
```

### PUT Request로 Body 전송

```kotlin
val body = Buffer.buffer("Hello World!")
val response = client
    .put(9989, "localhost", "/simple")
    .`as`(BodyCodec.string())
    .sendBuffer(body)
    .coAwait()

response.statusCode() // 200
response.body()       // "OK"
```

### JSON Response를 Domain Object로 Deserialize

```kotlin
data class User(val firstname: String, val lastname: String, val male: Boolean)

val response = client
    .put(9999, "localhost", "/")
    .`as`(BodyCodec.json(User::class.java))   // automatic Jackson mapping
    .send()
    .coAwait()

val user: User = response.body()
```

### CoroutineVerticle로 Server 구현

```kotlin
class CoroutineServer : CoroutineVerticle() {
    override suspend fun start() {                 // can be declared as a suspend function
        vertx.createHttpServer()
            .requestHandler { req -> req.response().end("Hello Coroutines!") }
            .listen(9988)
            .coAwait()
    }
}

// Deploy
vertx.deployVerticle(CoroutineServer()).coAwait()
```

## Coroutine 통합 접근 방식 비교

| 접근 방식 | API | 특징 |
|------|-----|------|
| Callback | `send { ar -> ... }` | 전통적인 Vert.x style이며 nesting이 발생하기 쉽습니다 |
| `coAwait()` | `send().coAwait()` | 동기 코드처럼 읽히는 Kotlin coroutine style입니다 |
| `CoroutineVerticle` | `override suspend fun start()` | 전체 Verticle을 coroutine code로 작성합니다 |

## 사용한 bluetape4k 기능

| 기능 | Artifact | 코드 위치 | 이점 |
|---|---|---|---|
| `suspendHandler { }` | `bluetape4k-vertx` | `RequestExamples.kt`, `ResponseExamples.kt` | Vert.x `Handler<RoutingContext>`를 suspend lambda로 감쌉니다 |
| `withSuspendTestContext` | `bluetape4k-vertx` | 모든 테스트 | coroutine block 안에서 Vert.x `VertxTestContext`를 안전하게 사용하기 위한 test helper입니다 |
| `Jackson` (jackson3) | `bluetape4k-jackson3` | `ResponseExamples.kt` | Jackson 3.x `ObjectMapper` singleton을 제공하며 serialization/deserialization에 shared instance를 사용합니다 |
| `runSuspendTest { }` | `bluetape4k-junit5` | 모든 테스트 | JUnit 5에서 suspend test를 실행하는 extension function입니다 |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 companion object | coroutine context가 포함된 structured logging입니다 |
| `bluetape4k-assertions` | `bluetape4k-core` | 모든 테스트 | `shouldBeEqualTo`, `shouldNotBeNull` 같은 읽기 쉬운 assertion입니다 |

## bluetape4k Before / After

### `suspendHandler { }` vs Callback Routing

```kotlin
// Before - traditional Vert.x router handler (callback)
router.put("/request").handler(BodyHandler.create()).handler { ctx ->
    val body = ctx.body().asString()
    // Business logic
    ctx.response().end("OK")
}

// After - bluetape4k suspendHandler (written as a suspend function)
router.put("/request").handler(BodyHandler.create()).suspendHandler { ctx ->
    val body = ctx.body().asString()
    // Can call suspend functions, such as DB queries or external API calls
    ctx.response().end("OK")
}
```

### `Jackson` Singleton vs 직접 ObjectMapper 생성

```kotlin
// Before - create ObjectMapper every time or declare it directly in a companion object
companion object {
    private val objectMapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
        registerModule(KotlinModule.Builder().build())
    }
}

// After - bluetape4k Jackson singleton (shares a preconfigured ObjectMapper)
import io.bluetape4k.jackson3.Jackson

val json = Jackson.defaultJsonMapper.writeValueAsString(user)
val user = Jackson.defaultJsonMapper.readValue(json, User::class.java)
```

## 취소, 구조화된 동시성, Context 전파

### `coAwait()` 취소와 HTTP Request Abort

`Future<T>.coAwait()`는 coroutine cancellation signal을 받으면 진행 중인 HTTP request를 abort합니다.
client가 timeout 또는 parent scope 취소로 취소되면 socket resource는 즉시 반환됩니다.

```kotlin
// Apply a timeout to the HTTP request with withTimeout
withTimeout(500) {
    val response = client
        .get(port, "localhost", "/slow-endpoint")
        .send()
        .coAwait()   // CancellationException when exceeding 500ms
                     // -> HTTP request aborted, socket returned
    response.body()
}
```

### `CoroutineVerticle` Undeploy와 WebClient Cleanup

`CoroutineVerticle`을 undeploy하면 해당 `CoroutineScope`가 취소됩니다.
`start()`에서 시작한 모든 child coroutine(예: `launch { ... }`)도 취소되므로 scope가 취소될 때 진행 중인 WebClient request도 정상적으로 중단됩니다.

```kotlin
class CoroutineServer: CoroutineVerticle() {
    private lateinit var client: WebClient

    override suspend fun start() {
        client = WebClient.create(vertx)
        vertx.createHttpServer()
            .requestHandler { ... }
            .listen(9988)
            .coAwait()
    }

    override suspend fun stop() {
        client.close()   // explicitly clean up WebClient on undeploy
    }
}
```

### `suspendHandler` 내부 Context 전파

`suspendHandler { ctx -> }` block 내부의 `coroutineContext`에는 Vert.x event-loop `vertx.dispatcher()`가 포함됩니다.
따라서 `EventBus` 또는 `FileSystem` 같은 Vert.x API 호출은 올바른 thread에서 실행됩니다.

```kotlin
router.get("/proxy").suspendHandler { ctx ->
    // Runs in the Vert.x dispatcher context
    val upstream = client.get(8081, "backend", "/data")
        .send()
        .coAwait()
    ctx.response().end(upstream.body())   // respond on the same event-loop thread
}
```

## 빌드와 테스트

```bash
./gradlew :vertx-vertx-webclient:test
```

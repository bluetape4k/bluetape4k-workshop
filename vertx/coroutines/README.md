# Example for Vert.x with Kotlin Coroutines

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Example for Vert.x with Kotlin Coroutines** as a runnable Vert.x reactive service workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Example for Vert.x with Kotlin Coroutines Graphviz architecture diagram](../../docs/images/readme-diagrams/vertx-coroutines-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.vertx` as the source of truth when comparing this README with the code.

![Example for Vert.x with Kotlin Coroutines architecture diagram](../../docs/images/readme-diagrams/vertx-coroutines-diagram-01.png)

## Flow Diagram

1. Prepare the local runtime required by `vertx-coroutines`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![Example for Vert.x with Kotlin Coroutines sequence diagram](../../docs/images/readme-diagrams/vertx-coroutines-sequence-01.png)

Vert.x 를 Kotlin Coroutines 와 함께 사용하는 예제입니다.

## 처리 흐름

![coroutines Sequence Flow diagram](../../docs/images/readme-diagrams/vertx-coroutines-sequence-01.png)

![Example for Vert.x with Kotlin Coroutines Diagram 1](../../docs/images/readme-diagrams/vertx-coroutines-readme-sequence-01.png)

## Vert.x 코루틴 통합 설명

`CoroutineVerticle`을 상속하면 Vert.x 이벤트 루프 위에서 `suspend` 함수를 직접 사용할 수 있습니다.
콜백 지옥 없이 순차적인 코드 스타일로 비동기 DB 쿼리와 HTTP 응답을 처리합니다.

| 구성 요소 | 설명 |
|-----------|------|
| `CoroutineVerticle` | `suspend fun start()` 를 오버라이드해 라우터·DB 초기화 |
| `suspendHandler { }` | Vert.x `Handler<RoutingContext>` 를 `suspend` 람다로 래핑 (`bluetape4k-vertx`) |
| `coAwait()` | `Future<T>.coAwait()` — Vert.x Future 를 코루틴 일시 중단으로 변환 |
| `JDBCPool` | H2 인메모리 DB, 커넥션 풀 크기 `maxSize=16` |
| `Router` | `GET /movie/:id`, `POST /rateMovie/:id`, `GET /getRating/:id` |

### 핵심 패턴: `suspendHandler`

```kotlin
// 기존 콜백 방식 대신 suspend 함수로 라우팅
router.get("/movie/:id").suspendHandler { ctx ->
    val rows = pool
        .preparedQuery("SELECT TITLE FROM MOVIE WHERE ID=?")
        .execute(Tuple.of(ctx.pathParam("id")))
        .coAwait()          // Future → suspend
    ctx.response().end(Json.obj { ... }.encode())
}
```

## 데이터 모델

![coroutines Entity Relationship 2 diagram](../../docs/images/readme-diagrams/vertx-coroutines-diagram-01.png)

## 제공 API 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/movie/:id` | 영화 제목 조회 (JSON 반환) |
| `POST` | `/rateMovie/:id?getRating=N` | 영화 평점 등록 |
| `GET` | `/getRating/:id` | 영화 평균 평점 조회 |

## 사용된 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `suspendHandler { }` | `bluetape4k-vertx` | `MainVerticle.kt` | Vert.x `Handler<RoutingContext>`를 suspend 람다로 래핑 — 콜백 중첩 없이 순차 코드 작성 |
| `withSuspendTestContext` | `bluetape4k-vertx` | 테스트 전체 | Vert.x `VertxTestContext`를 코루틴 블록에서 안전하게 사용하는 테스트 헬퍼 |
| `runSuspendTest { }` | `bluetape4k-junit5` | 테스트 전체 | JUnit 5에서 suspend 테스트를 실행하는 확장 함수 |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 companion object | 코루틴 컨텍스트 포함 구조적 로깅 |
| `bluetape4k-assertions` | `bluetape4k-core` | 테스트 전체 | 가독성 높은 단언문 (`shouldBeEqualTo`, `shouldNotBeEmpty` 등) |

## bluetape4k Before / After

### `suspendHandler { }` vs 콜백 기반 라우팅

```kotlin
// Before — 전통 Vert.x 콜백 방식
router.get("/movie/:id").handler { ctx ->
    pool.preparedQuery("SELECT TITLE FROM MOVIE WHERE ID=?")
        .execute(Tuple.of(ctx.pathParam("id"))) { ar ->
            if (ar.succeeded()) {
                val rows = ar.result()
                ctx.response().end(/* 결과 직렬화 */)
            } else {
                ctx.fail(ar.cause())
            }
        }
}

// After — bluetape4k suspendHandler (suspend 함수로 순차 처리)
router.get("/movie/:id").suspendHandler { ctx ->
    val rows = pool
        .preparedQuery("SELECT TITLE FROM MOVIE WHERE ID=?")
        .execute(Tuple.of(ctx.pathParam("id")))
        .coAwait()          // Future → suspend
    ctx.response().end(Json.obj { ... }.encode())
}
```

### `withSuspendTestContext` vs VertxTestContext 수동 처리

```kotlin
// Before — VertxTestContext 수동 완료/실패 처리
@Test
fun `test route`(vertx: Vertx, testContext: VertxTestContext) {
    vertx.deployVerticle(MainVerticle()) { ar ->
        if (ar.succeeded()) {
            // 콜백 중첩으로 테스트 로직 작성
            testContext.completeNow()
        } else {
            testContext.failNow(ar.cause())
        }
    }
}

// After — bluetape4k withSuspendTestContext (코루틴 블록 자동 완료)
@Test
fun `test route`(vertx: Vertx, testContext: VertxTestContext) = runSuspendTest {
    vertx.withSuspendTestContext(testContext) {
        vertx.deployVerticle(MainVerticle()).coAwait()
        // 순차 코드로 테스트 작성 — 예외 시 testContext.failNow() 자동 호출
    }
}
```

## 취소·구조적 동시성·컨텍스트 전파

### `CoroutineVerticle` 스코프와 언디플로이

`CoroutineVerticle` 은 `CoroutineScope` 를 구현합니다.
`Verticle`이 언디플로이되면 해당 스코프가 취소되어, `start()` 내부에서 시작된
모든 자식 코루틴이 즉시 취소됩니다. 이를 통해 구조적 동시성 (structured concurrency) 이 보장됩니다.

```kotlin
class MainVerticle: CoroutineVerticle() {
    override suspend fun start() {
        // 이 스코프(CoroutineVerticle)가 취소되면
        // launch { ... } 자식 코루틴도 모두 취소됨
        launch {
            // 백그라운드 작업 (예: 주기적 캐시 갱신)
        }
        // Router 등록 ...
    }
}
// vertx.undeploy(deploymentId) → MainVerticle 스코프 취소 → launch 블록 취소
```

### `coAwait()` 취소 동작

`Future<T>.coAwait()` 는 코루틴이 취소되면 Vert.x `Future` 를 즉시 취소합니다.
DB 쿼리 대기 중 HTTP 연결이 끊어져도 커넥션 풀에 커넥션이 정상 반환됩니다.

```kotlin
router.get("/movie/:id").suspendHandler { ctx ->
    // 이 suspend 블록 실행 중 ctx.request()가 abort되면
    // pool.preparedQuery(...).execute(...).coAwait() 가 CancellationException 발생
    // → JDBCPool 커넥션 자동 반환
    val rows = pool.preparedQuery("SELECT TITLE FROM MOVIE WHERE ID=?")
        .execute(Tuple.of(ctx.pathParam("id")))
        .coAwait()
    ctx.response().end(Json.obj { }.encode())
}
```

### `vertx.dispatcher()` 컨텍스트 전파

`CoroutineVerticle` 내부에서는 `coroutineContext` 에 `vertx.dispatcher()` 가 포함되어
Vert.x 이벤트 루프 스레드에서 코루틴이 실행됩니다.
`withContext(vertx.dispatcher())` 를 명시적으로 사용하면 이벤트 루프를 벗어난 컨텍스트에서
Vert.x API를 안전하게 호출할 수 있습니다.

```kotlin
// 다른 디스패처에서 Vert.x API 호출이 필요할 때
suspend fun queryAndRespond(ctx: RoutingContext) {
    val result = withContext(Dispatchers.IO) {
        // 블로킹 I/O 처리
        blockingComputation()
    }
    // Vert.x 이벤트 루프로 복귀하여 응답
    withContext(vertx.dispatcher()) {
        ctx.response().end(result)
    }
}
```

## 빌드 및 테스트

```bash
./gradlew :vertx-coroutines:test
```

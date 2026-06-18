# Spring WebFlux + Coroutines

[English](README.md) | 한국어

## 이 예제가 보여 주는 것

이 모듈은 같은 `Banner` 계약을 반환하는 두 가지 Spring WebFlux coroutine 스타일을 비교합니다.
`/controller/{default|io|vt}` 아래의 annotation controller와 root path의 functional `coRouter` endpoint가
나란히 있으며, 핵심 차이는 HTTP 모델이 아니라 coroutine dispatcher와 Flow 처리 방식입니다.

## 아키텍처 다이어그램

![Spring WebFlux coroutine architecture](../../docs/images/readme-diagrams/spring-boot-webflux-coroutines-readme-architecture-01.png)

아키텍처는 controller 계열, functional handler, flow 예제가 사용하는 shared `WebClient` loopback,
그리고 튜닝된 Reactor Netty resource를 나누어 보여 줍니다.

## Coroutine 흐름

![Spring WebFlux coroutine flow](../../docs/images/readme-diagrams/spring-boot-webflux-coroutines-readme-flow-01.png)

이 예제는 Spring WebFlux 환경에서 Kotlin coroutines를 사용합니다. bluetape4k `Dispatchers.VT`,
`Flow<T>.async`, `Runtimex`, shared `WebClient` helper를 보여 주되, 각 요청을 받는 controller style을
숨기지 않습니다.

## 구현 전략 비교

세 컨트롤러는 dispatcher 전략의 차이를 보여 줍니다.

| 컨트롤러 | Dispatcher 전략 | 설명 |
|---|---|---|
| `DefaultCoroutineController` | `Dispatchers.IO` | 기본 I/O dispatcher로 blocking 작업을 처리합니다 |
| `IOCoroutineController` | `Dispatchers.IO` (명시적) | I/O 중심 작업에 맞게 최적화했습니다 |
| `VTCoroutineController` | `Dispatchers.VT` | bluetape4k Virtual Thread CoroutineDispatcher |
| `CoroutineHandler` | `coRouter` DSL | 함수형 endpoint 스타일 |

## 사용된 bluetape4k 기능

| 기능 | Artifact | 코드 위치 | 이점 |
|---|---|---|---|
| `Dispatchers.VT` | `bluetape4k-coroutines` | `VTCoroutineController` | controller work에 사용할 virtual-thread 기반 `CoroutineDispatcher` |
| `Flow<T>.async { }` | `bluetape4k-coroutines` | `VTCoroutineController.concurrentFlow()` | Flow element를 병렬로 변환하는 operator |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 companion object | coroutine context를 포함하는 structured logging |
| `Base58.randomString()` | `bluetape4k-io` | `Banner.kt` | URL-safe random string 생성 |
| `Runtimex.availableProcessors` | `bluetape4k-core` | `NettyConfig.kt` | CPU core 수를 기준으로 event loop 크기 계산 |

## bluetape4k Before / After

### `Dispatchers.VT` vs 표준 Dispatcher

```kotlin
// Before — Standard Dispatchers.IO or manual ExecutorService
class CoroutineController : CoroutineScope by CoroutineScope(Dispatchers.IO) {
    // Or
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    val Dispatchers.VirtualThread: CoroutineDispatcher
        get() = executor.asCoroutineDispatcher()
}

// After — bluetape4k Dispatchers.VT (singleton, ready to use)
class VTCoroutineController(
    private val builder: WebClient.Builder,
) : CoroutineScope by CoroutineScope(Dispatchers.VT + CoroutineName("vt")) {
    // Virtual Thread per task, shared across the application
}
```

### `Flow<T>.async` vs 순차 Flow

```kotlin
// Before — Sequential processing (no parallelism)
fun sequentialFlow(): Flow<Banner> = flow {
    repeat(4) { emit(retrieveBanner()) }  // Runs 4 tasks sequentially
}

// After — Parallel transformation with bluetape4k .async { }
fun concurrentFlow(): Flow<Banner> =
    (0..3).asFlow()
        .async { retrieveBanner() }  // Runs 4 tasks concurrently while preserving order
```

### `Runtimex` vs `Runtime.getRuntime()`

```kotlin
// Before — Java Runtime API
val eventLoopSize = maxOf(Runtime.getRuntime().availableProcessors() * 8, 64)

// After — bluetape4k Runtimex (Kotlin property style)
val eventLoopSize = maxOf(Runtimex.availableProcessors * 8, 64)
```

## 고성능 Netty 설정

```kotlin
@Bean
fun reactorResourceFactory(): ReactorResourceFactory = ReactorResourceFactory().apply {
    isUseGlobalResources = false
    connectionProvider = ConnectionProvider.builder("http")
        .maxConnections(10_000)
        .maxIdleTime(Duration.ofSeconds(10))
        .build()
    loopResources = LoopResources.create(
        "event-loop",
        maxOf(Runtimex.availableProcessors * 8, 64),  // bluetape4k Runtimex
        true
    )
}
```

## 실행

```bash
./gradlew :webflux-coroutines:bootRun

# VT Dispatcher endpoints
curl http://localhost:8080/controller/vt/suspend
curl http://localhost:8080/controller/vt/concurrent-flow

# Functional router endpoint
curl http://localhost:8080/handler/banners
```

## 참고

- [Official Spring WebFlux + Coroutines documentation](https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html)
- [Reactor Meltdown explanation](https://blog.frankel.ch/project-reactor-meltdown/)
- [bluetape4k-coroutines](https://github.com/bluetape4k/bluetape4k-projects)

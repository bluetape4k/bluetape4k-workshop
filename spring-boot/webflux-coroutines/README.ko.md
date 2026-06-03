# Spring WebFlux + Coroutines

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring WebFlux + Coroutines**를 실행 가능한 Spring Boot 애플리케이션 기능 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Spring WebFlux + Coroutines architecture diagram](../../docs/images/readme-diagrams/spring-boot-webflux-coroutines-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지를 기준으로 삼으세요.

![Spring WebFlux + Coroutines Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-webflux-coroutines-readme-architecture-01.png)

## 흐름 다이어그램

1. `spring-boot-webflux-coroutines`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업은 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, 트레이스 또는 테스트 기대값으로 보이는 결과를 검증합니다.

![Spring WebFlux + Coroutines flow diagram](../../docs/images/readme-diagrams/spring-boot-webflux-coroutines-diagram-01.png)

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 에셋이 있는 모듈은 아래 이미지가 상호작용 순서를 보여 주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![Spring WebFlux + Coroutines sequence diagram](../../docs/images/readme-diagrams/spring-boot-webflux-coroutines-sequence-01.png)

이 예제는 Spring WebFlux 환경에서 Kotlin Coroutines를 사용합니다.
bluetape4k `Dispatchers.VT`, `Flow<T>.async`, `Runtimex` 및 관련 유틸리티를 사용해 Virtual Thread 기반 리액티브 컨트롤러를 구성합니다.

## 아키텍처

![webflux coroutines Architecture diagram](../../docs/images/readme-diagrams/spring-boot-webflux-coroutines-architecture-01.png)

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
| `Dispatchers.VT` | `bluetape4k-coroutines` | `VTCoroutineController` | OS thread 없이 coroutine을 실행하는 Virtual Thread 기반 CoroutineDispatcher |
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

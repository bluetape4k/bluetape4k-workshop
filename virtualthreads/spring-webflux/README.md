# Spring Webflux with Coroutines and Virtual Thread

Spring Webflux 환경에서 다양한 Coroutine Dispatcher 의 성능을 비교했습니다.

## Dispatcher 처리 모델 비교

![Dispatcher diagram](../../docs/images/readme-diagrams/virtualthreads-spring-webflux-diagram-01.png)

1. Dispatchers.Default
2. Dispatchers.IO
3. Custom Dispatcher with Thread Pool (size=16)
    - `Executors.newFixedThreadPool(16).asCoroutineDispatcher`
4. Dispatchers from Virtual Thread
   ```kotlin
      val Dispatchers.newVT: CoroutineDispatcher 
          get() = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    ```

## bluetape4k 활용 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| Virtual Thread 지원 라이브러리 | `bluetape4k-virtualthread-api` / `-jdk21` | `VirtualThreadDispatcherController`, `AsyncConfig` | JDK 21 `newVirtualThreadPerTaskExecutor` 래퍼 및 확장 유틸리티 |
| `KLoggingChannel` (코루틴 로거) | `bluetape4k-logging` | 모든 컨트롤러 | 코루틴 context-aware 로깅; MDC 자동 전파 |
| `uninitialized()` | `bluetape4k-core` | `AbstractDispatcherController` | `lateinit` 없이 non-null 필드 지연 초기화 (`@Value` 주입) |
| 코루틴 확장 | `bluetape4k-coroutines` | Flow 기반 엔드포인트 | `channelFlow`, `flatMapMerge` 등 코루틴 Flow 지원 |
| IO 유틸리티 | `bluetape4k-io` | 파일/스트림 처리 | Okio 기반 IO 확장 |
| Jackson 3.x 지원 | `bluetape4k-jackson3` | REST API 직렬화 | Spring Boot 4 + Jackson 3 호환 자동 설정 |
| Testcontainers 래퍼 | `bluetape4k-testcontainers` | `AbstractWebfluxVirtualThreadTest` | 외부 서비스 컨테이너 싱글턴 |

## bluetape4k Before / After

### Virtual Thread Dispatcher 설정

```kotlin
// Before — 표준 JDK API로 직접 설정
@RestController
@RequestMapping("/virtual-thread")
class VirtualThreadDispatcherController {

    private val dispatcher: CoroutineDispatcher =
        Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

    @GetMapping("/deferred")
    fun deferredEndpoint(): Deferred<Banner> = CoroutineScope(dispatcher).async {
        delay(100)
        randomBanner()
    }
}

// After — bluetape4k Dispatchers.VT (관용적 확장 프로퍼티)
import io.bluetape4k.concurrent.virtualthread.VT

@RestController
@RequestMapping("/virtual-thread")
class VirtualThreadDispatcherController {

    private val dispatcher: CoroutineDispatcher = Dispatchers.VT

    @GetMapping("/deferred")
    fun deferredEndpoint(): Deferred<Banner> = CoroutineScope(dispatcher).async {
        delay(100)
        randomBanner()
    }
}
```

### `@Value` 주입 필드 지연 초기화

```kotlin
// Before — lateinit var (nullable 위험, 컴파일 경고)
@Value("\${server.port:8080}")
private lateinit var port: String

// After — bluetape4k uninitialized()
import io.bluetape4k.support.uninitialized

@Value("\${server.port:8080}")
private val port: String = uninitialized()  // non-null val, Spring이 주입
```

### Async 작업에 Virtual Thread 사용

```kotlin
// Before — Executors.newVirtualThreadPerTaskExecutor() + TaskExecutorAdapter 수동 조합
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean
    fun asyncTaskExecutor(): AsyncTaskExecutor {
        return TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor())
    }
}

// After — bluetape4k virtual thread factory + inheritInheritableThreadLocals 설정 포함
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {
    companion object: KLoggingChannel()

    private val virtualThreadFactory = Thread.ofVirtual()
        .inheritInheritableThreadLocals(true)  // ThreadLocal 컨텍스트 전파 보장
        .name("vt-executor-", 0)
        .factory()

    @Bean
    fun asyncTaskExecutor(): AsyncTaskExecutor {
        return TaskExecutorAdapter(Executors.newThreadPerTaskExecutor(virtualThreadFactory))
    }
}
```

## Gatling 을 이용한 성능 측정

우선 _WebfluxVirtualThreadApp_ 을 gradle `bootRun` task를 이용하여 실행시킵니다.

```bash
./gradlew :spring-webflux-virtualthread:bootRun
```

다음으로 gradle `gatlingRun` task를 이용하여 Stress test를 수행합니다.

```bash
./gradlew :spring-webflux-virtualthread:gatlingRun
```

### Gatling 스트레스 시나리오

편의를 위해 `ScenarioProvider` 라는 Object 에서 시나리오와 부하 설정을 제공합니다.

시나리오는 기본적인 4개의 API 를 순차적으로 호출하도록 합니다.
부하는 30초 동안 10명에서 400명으로 점차 부하를 증가시켜서 호출하도록 합니다.

```kotlin
object ScenarioProvider {

    const val BASE_URL = "http://localhost:8080"

    fun getHttpProtocol(): HttpProtocolBuilder = http
        .baseUrl(BASE_URL)
        .acceptHeader("*/*")

    fun getScenario(dispatcherType: DispatcherType): ScenarioBuilder {
        val basePath = dispatcherType.code
        return scenario("$dispatcherType Simulation")
            .exec(http("Suspend").get("/$basePath/suspend"))
            .exec(http("Deferred").get("/$basePath/deferred"))
            .exec(http("Sequential flow").get("/$basePath/sequential-flow"))
            .exec(http("Concurrent flow").get("/$basePath/concurrent-flow"))
    }

    fun getRampConcurrentUsers(
        start: Int = 10,
        finish: Int = 400,
        duration: Duration = 30.seconds.toJavaDuration()
    ): ClosedInjectionStep {
        return rampConcurrentUsers(start).to(finish).during(duration)
    }
}
```

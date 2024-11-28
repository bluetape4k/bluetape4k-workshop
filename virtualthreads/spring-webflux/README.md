# Spring Webflux with Coroutines and Virtual Thread

Spring Webflux 환경에서 다양한 Coroutine Dispatcher 의 성능을 비교했습니다.

1. Dispatchers.Default
2. Dispatchers.IO
3. Custom Dispatcher with Thread Pool (size=16)
    - `Executors.newFixedThreadPool(16).asCoroutineDispatcher`
4. Dispatchers from Virtual Thread
   ```kotlin
      val Dispatchers.newVT: CoroutineDispatcher 
          get() = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    ```

## Gatling 을 이용한 성능 측정

우선 _WebfluxVirtualThreadApp_ 을 gradle `bootRun` task를 이용하여 실행시킵니다.

```bash
$ ./gradlew :spring-webflux-virtualthread:bootRun
```

다음으로 gradle `gatlingRun` task를 이용하여 Stress test를 수행합니다.

```bash
$ ./gradlew :spring-webflux-virtualthread:gatlingRun
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

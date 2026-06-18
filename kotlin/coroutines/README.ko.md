# Coroutines Examples

[English](README.md) | 한국어

이 모듈은 Kotlin coroutine 동작을 테스트로 익히는 예제 모음입니다. 하나의 실행 서비스가 아니라,
각 package가 하나의 coroutine 주제를 JUnit 테스트, coroutine test utility, logging helper,
flow assertion과 함께 보여줍니다.

## 학습 지도

![Coroutines examples learning map](../../docs/images/readme-diagrams/kotlin-coroutines-readme-architecture-01.png)

처음에는 `guide/` package에서 기본 용어와 패턴을 익히고, 이후 builder, dispatcher, cancellation,
flow/channel, context propagation, Spring scope lifecycle 예제로 넘어가면 됩니다.

## Flow 테스트와 디버깅 흐름

![Flow test and debug path](../../docs/images/readme-diagrams/kotlin-coroutines-readme-flow-test-01.png)

Flow 예제는 cold stream 또는 channel 기반 stream을 만들고, `Flow<T>.log()`로 emit/complete 이벤트를
확인한 뒤 `assertResult`, Turbine, cancellation check, failure assertion으로 결과를 검증합니다.

## 예제 범주

| package | examples | 독자가 확인할 질문 |
|---|---|---|
| `guide/` | builders, context, suspend functions, flow, shared flow, channels, MDC | coroutine 기본 구성요소는 무엇인가? |
| `builders/` | `coroutineScope`, `supervisorScope`, `withContext` | builder가 child job과 error propagation에 어떤 영향을 주는가? |
| `dispatchers/` | Default, IO, custom pools, Main dispatcher override | 이 coroutine은 어떤 dispatcher/thread에서 실행되는가? |
| `cancellation/` and `exceptions/` | cancellation propagation, `NonCancellable`, error handling | cancellation과 failure를 어떻게 모델링해야 하는가? |
| `flow/` and `channels/` | `flowOf`, `asFlow`, `callbackFlow`, `channelFlow`, actors | cold stream, hot stream, channel은 어떻게 다르게 동작하는가? |
| `context/` | `CounterCoroutineContext`, `UuidProviderCoroutineContext` | custom context element로 request-scope 상태를 어떻게 전달하는가? |
| `scope/spring/` | `SpringCoroutineScope`, bean destroy hook | Spring bean이 coroutine `Job`을 소유하고 취소하려면 어떻게 해야 하는가? |
| `tests/` | Turbine examples | 비동기 flow 동작을 테스트에서 어떻게 검증하는가? |

## 사용한 bluetape4k 기능

| function | artifact | code location | advantage |
|---|---|---|---|
| `KLoggingChannel` | `bluetape4k-logging` | 여러 예제의 companion object | coroutine-aware lazy logging |
| `suspendLogging { }` | `bluetape4k-logging` | `dispatchers/DispatcherExamples` | suspend context에서 log message 구성 |
| `Job.log("name")` | `bluetape4k-coroutines` | dispatcher examples | launched job에 completion logging 추가 |
| `Flow<T>.log()` | `bluetape4k-coroutines` | `flow/*`, `tests/TurbineExamples` | flow emit, completion, error event 기록 |
| `assertResult(...)` | `bluetape4k-coroutines` | `flow/FlowBuilderExamples` | Turbine block 없이 flow 값 순서 검증 |
| `runSuspendTest { }` / `runTest` | `bluetape4k-junit5`, kotlinx-coroutines-test | coroutine tests | JUnit 5에서 suspend 예제 실행 |
| `OutputCapture` / `OutputCapturer` | `bluetape4k-junit5` | `scope/spring/SpringCoroutineScopeTest` | destroy 시점 출력 검증 |
| `Uuid.V7` | `bluetape4k-idgenerators` | `context/UuidProviderCoroutineContext` | coroutine context를 통해 request-style identifier 제공 |

## 대표 패턴

### Dispatcher logging

```kotlin
launch(Dispatchers.IO) {
    val threadName = Thread.currentThread().name
    suspendLogging { "Running on thread $threadName" }
}.log("IO")
```

### Flow 생성과 간단한 검증

```kotlin
val function: suspend () -> String = suspend {
    delay(1000)
    "UserName"
}

function.asFlow()
    .log("function")
    .assertResult("UserName")
```

### Spring이 소유하는 coroutine scope

```kotlin
class MyBean: SpringCoroutineScope by SpringCoroutineScope() {
    suspend fun run(input: Int) =
        withContext(coroutineContext) {
            delay(1000)
            input
        }
}
```

`SpringCoroutineScope` 구현은 coroutine context의 `Job`을 노출하고 `destroy()`에서 취소합니다.
Spring bean lifecycle에 맞춰 coroutine 작업을 정리할 때 쓰기 좋은 형태입니다.

## 참고

- [Kotlin Coroutines official guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [Kotlin Flow documentation](https://kotlinlang.org/docs/flow.html)
- [Turbine](https://github.com/cashapp/turbine)

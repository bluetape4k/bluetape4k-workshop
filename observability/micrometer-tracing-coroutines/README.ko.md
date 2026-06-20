# WebFlux와 Coroutines용 Micrometer Tracing

[English](README.md) | 한국어

`observability/micrometer-tracing-coroutines`는 Spring Boot 4 WebFlux 애플리케이션에서 동기, Reactor, Kotlin coroutine
request handler의 tracing 방식을 비교한다. `ZipkinServer.Launcher.zipkin`으로 Zipkin을 시작하고, 설정된 Zipkin endpoint로
Micrometer tracing span을 export한다.

## 아키텍처

![Micrometer tracing architecture](../../docs/images/readme-diagrams/observability-micrometer-tracing-coroutines-readme-architecture-01.png)

이 모듈은 `ObservedAspect(observationRegistry)`를 등록하고, sync/reactor service 또는 method에는 `@Observed`를 사용한다.
명시적인 nested span은 bluetape4k `withObservation` / `withObservationSuspending` DSL로 만든다.

## Coroutine Trace Flow

![Coroutine trace flow](../../docs/images/readme-diagrams/observability-micrometer-tracing-coroutines-readme-coroutine-sequence-01.png)

`withObservationSuspending`은 `pre-processing`, `get-todo-by-id`, `post-processing` 같은 suspend block을 감싼다.
observation scope는 `delay(...)`, `WebClient.awaitBodyOrNull()` suspension point를 지나도 유지되고,
`CancellationException`은 span error로 기록하지 않고 다시 던진다.

## Endpoints

| Style | Endpoint | Service path |
|---|---|---|
| Sync | `/sync/name`, `/sync/todos/{id}` | `@Observed` controller/service method와 명시적인 `withObservation` 또는 manual `Observation`. |
| Reactor | `/reactor/name`, `/reactor/todos/{id}` | `ReactorService` class-level `@Observed`와 `Mono` pipeline. |
| Coroutine | `/coroutine/name`, `/coroutine/todos/{id}` | Suspend handler와 `CoroutineService` 내부 `withObservationSuspending` span. |

## 주요 구성 요소

| Component | Role |
|---|---|
| `TracingApplication` | Spring app과 singleton Zipkin Testcontainer를 시작한다. |
| `ObservationConfig` | `ObservationRegistry`가 있을 때 `ObservedAspect`를 등록한다. |
| `SyncService` | `@Observed`, manual `Observation`, `withObservation`을 보여준다. |
| `ReactorService` | Reactor `Mono` 작업과 class-level `@Observed`를 보여준다. |
| `CoroutineService` | suspend 함수에서 `withObservationSuspending`을 사용한다. |
| `ZipkinServer.Launcher.zipkin` | local trace export를 위한 `${testcontainers.zipkin.url}/api/v2/spans`를 제공한다. |

## bluetape4k 사용 지점

| Feature | Where | Why it matters |
|---|---|---|
| `withObservation {}` | `SyncService.preProcessing`, `postProcessing` | manual start/stop 없이 nested span을 만든다. |
| `withObservationSuspending {}` | `CoroutineService` | coroutine suspension과 cancellation을 지나도 span context를 올바르게 유지한다. |
| `KLoggingChannel` | Coroutine/Reactor components | trace context와 맞는 coroutine-aware logging을 유지한다. |
| `ZipkinServer.Launcher.zipkin` | `TracingApplication` | custom `GenericContainer` 없이 Zipkin을 한 번만 시작한다. |

## Coroutine Example

```kotlin
private suspend fun getTodoById(id: Int): Todo? {
    return withObservationSuspending("get-todo-by-id", observationRegistry) {
        client.get()
            .uri("/todos/${id}")
            .retrieve()
            .awaitBodyOrNull<Todo>()
    }
}
```

## 테스트

```bash
./gradlew :observability-micrometer-tracing-coroutines:test
```

테스트는 sync service span, coroutine service span, WebFlux controller integration, Zipkin container startup을 다룬다.

## 실행

```bash
./gradlew :observability-micrometer-tracing-coroutines:bootRun
curl http://localhost:8080/coroutine/todos/1
open http://localhost:9411
```

Zipkin이 Testcontainers로 시작되므로 Docker가 필요하다.

## 참고

- [Micrometer Observation](https://micrometer.io/docs/observation)
- [Micrometer Tracing](https://micrometer.io/docs/tracing)
- [`micrometer-observation`](../micrometer-observation) - Spring MVC observation basics.

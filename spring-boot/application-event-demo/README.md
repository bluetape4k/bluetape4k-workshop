# Spring Application Event Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Application Event Demo** as a runnable Spring Boot application feature workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Spring Application Event Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-application-event-demo-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springboot` as the source of truth when comparing this README with the code.

## Flow Diagram

1. Prepare the local runtime required by `spring-boot-application-event-demo`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![Spring Application Event Demo sequence diagram](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-01.png)

Spring Application Event를 비동기 방식(Reactor, Coroutines)으로 발행하고 수신하는 패턴을 보여줍니다.

## 이벤트 발행/수신 흐름

![/ diagram](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-01.png)

## AOP 기반 이벤트 흐름

![AOP diagram](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-02.png)

## 두 가지 이벤트 발행 방식

### 1. 직접 발행 (`custom/`)

`ApplicationEventPublisher`를 직접 주입받아 이벤트를 발행합니다.

```kotlin
@Component
class CustomEventPublisher(private val publisher: ApplicationEventPublisher) {
    fun publish(message: String) = publisher.publishEvent(CustomEvent(this, message))
}
```

리스너는 일반 방식과 Coroutine 비동기 방식 모두 제공합니다:
- `CustomEventListener` — `@EventListener`로 동기 수신
- `AnnotatedCoroutineCustomEventListener` — `@EventListener` + suspend 함수로 비동기 수신

### 2. AOP 기반 발행 (`aspect/`)

메서드 실행 시 AOP로 자동으로 이벤트를 발행합니다.

```kotlin
@AspectEventEmitter  // 이 어노테이션이 붙은 메서드 실행 시 자동으로 이벤트 발행
fun doSomething(): Result { ... }
```

- `AspectEventPublisherAspect` — Around Advice로 이벤트 캡처 후 발행
- `AspectEventListener` — 동기 수신
- `CoroutineAspectEventListener` — Coroutine 비동기 수신

## 참고

- [Spring Application Events](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-events)
- [Spring + Coroutines 이벤트 처리](https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html)

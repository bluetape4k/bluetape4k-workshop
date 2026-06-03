# Spring Application Event Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Application Event Demo** as a runnable Spring Boot application feature workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Spring Application Event Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-application-event-demo-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springboot` as the source of truth when comparing this README with the code.

## Sequence Diagram

![Spring Application Event Demo sequence diagram](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-01.png)

Shows patterns for publishing and receiving Spring Application Events asynchronously with Reactor and coroutines.

## AOP-Based Event Flow

![AOP diagram](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-02.png)

## Two Event Publishing Approaches

### 1. Direct Publishing (`custom/`)

Inject `ApplicationEventPublisher` directly and publish events from the component.

```kotlin
@Component
class CustomEventPublisher(private val publisher: ApplicationEventPublisher) {
    fun publish(message: String) = publisher.publishEvent(CustomEvent(this, message))
}
```

Listeners are provided in both regular and coroutine-based asynchronous styles:
- `CustomEventListener` — Receives events synchronously with `@EventListener`
- `AnnotatedCoroutineCustomEventListener` — Receives events asynchronously with `@EventListener` + a suspend function

### 2. AOP-Based Publishing (`aspect/`)

Publishes events automatically through AOP when a method executes.

```kotlin
@AspectEventEmitter  // Automatically publishes an event when a method with this annotation executes
fun doSomething(): Result { ... }
```

- `AspectEventPublisherAspect` — Captures and publishes events through Around Advice
- `AspectEventListener` — Synchronous event receiver
- `CoroutineAspectEventListener` — Coroutine-based asynchronous event receiver

## References

- [Spring Application Events](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-events)
- [Spring + Coroutines event handling](https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html)

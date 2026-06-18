# Spring Application Event Demo

[한국어](README.ko.md) | English

This module shows two practical ways to publish Spring application events from a WebFlux Spring Boot application:

- Direct publishing from a controller through `CustomEventPublisher`.
- Declarative publishing through `@AspectEventEmitter` and an AOP advice.

## Architecture

![Spring Application Event Demo architecture](../../docs/images/readme-diagrams/spring-boot-application-event-demo-readme-architecture-01.png)

Both paths meet at Spring `ApplicationEventPublisher`. The direct path builds a `CustomEvent` explicitly, while the AOP path intercepts an annotated service method, evaluates the configured SpEL expression, and builds an `AspectEvent`.

## Direct CustomEvent Flow

![Direct CustomEvent publishing flow](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-01.png)

`CustomEventController` exposes `GET /event?message=...` and calls `CustomEventPublisher.publish(message)` twice. The event is consumed by three listener styles:

- `CustomEventListener`, an `ApplicationListener<CustomEvent>` implementation.
- `AnnotatedCustomEventListener`, a regular `@EventListener` handler.
- `AnnotatedCoroutineCustomEventListener`, a Reactor `mono(Dispatchers.IO)` wrapper around suspend work.

## AOP AspectEvent Flow

![AOP AspectEvent publishing flow](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-02.png)

`MyEventService.someOperation()` stays focused on domain work. `AspectEventPublisherAspect` handles the publishing concern:

1. Intercepts methods annotated with `@AspectEventEmitter`.
2. Calls the target method with `joinPoint.proceed()`.
3. Evaluates the annotation `params` SpEL against the returned value.
4. Creates the configured event type and publishes it through Spring.

## Usage

Run the application and call the direct endpoint:

```bash
./gradlew :spring-boot:application-event-demo:bootRun
curl "http://localhost:8080/event?message=hello"
```

Run the focused tests:

```bash
./gradlew :spring-boot:application-event-demo:test
```

`CustomEventPublisherTest` verifies the HTTP publishing path with `WebTestClient`. `AspectFlowEventEmitterTest` verifies that the aspect emits an `AspectEvent` whose message is created from the returned `OperationParams.id`.

## References

- [Spring Application Events](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-events)
- [Spring + Coroutines event handling](https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html)

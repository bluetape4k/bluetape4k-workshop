# Spring Application Event Demo

[English](README.md) | 한국어

이 모듈은 WebFlux 기반 Spring Boot 애플리케이션에서 Spring Application Event를 발행하는 두 가지 실전 방식을 보여 줍니다.

- `CustomEventPublisher`를 통해 컨트롤러에서 직접 발행합니다.
- `@AspectEventEmitter`와 AOP advice를 통해 선언적으로 발행합니다.

## 아키텍처

![Spring Application Event Demo architecture](../../docs/images/readme-diagrams/spring-boot-application-event-demo-readme-architecture-01.png)

두 경로는 모두 Spring `ApplicationEventPublisher`에서 만납니다. 직접 발행 경로는 `CustomEvent`를 명시적으로 만들고, AOP 경로는 어노테이션이 붙은 서비스 메서드를 가로채서 설정된 SpEL 표현식을 평가한 뒤 `AspectEvent`를 만듭니다.

## 직접 CustomEvent 발행 흐름

![Direct CustomEvent publishing flow](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-01.png)

`CustomEventController`는 `GET /event?message=...`를 제공하고 `CustomEventPublisher.publish(message)`를 두 번 호출합니다. 이벤트는 세 가지 리스너 스타일로 소비됩니다.

- `CustomEventListener`: `ApplicationListener<CustomEvent>` 구현체입니다.
- `AnnotatedCustomEventListener`: 일반 `@EventListener` 핸들러입니다.
- `AnnotatedCoroutineCustomEventListener`: suspend 작업을 Reactor `mono(Dispatchers.IO)`로 감싼 리스너입니다.

## AOP AspectEvent 발행 흐름

![AOP AspectEvent publishing flow](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-02.png)

`MyEventService.someOperation()`은 도메인 작업에만 집중합니다. `AspectEventPublisherAspect`는 이벤트 발행 관심사를 처리합니다.

1. `@AspectEventEmitter`가 붙은 메서드를 가로챕니다.
2. `joinPoint.proceed()`로 대상 메서드를 실행합니다.
3. 반환값을 기준으로 어노테이션의 `params` SpEL을 평가합니다.
4. 설정된 이벤트 타입을 만들고 Spring을 통해 발행합니다.

## 사용법

애플리케이션을 실행하고 직접 발행 엔드포인트를 호출합니다.

```bash
./gradlew :spring-boot:application-event-demo:bootRun
curl "http://localhost:8080/event?message=hello"
```

집중 테스트를 실행합니다.

```bash
./gradlew :spring-boot:application-event-demo:test
```

`CustomEventPublisherTest`는 `WebTestClient`로 HTTP 발행 경로를 검증합니다. `AspectFlowEventEmitterTest`는 aspect가 반환된 `OperationParams.id`에서 메시지를 만들어 `AspectEvent`를 발행하는지 검증합니다.

## 참고

- [Spring Application Events](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-events)
- [Spring + Coroutines event handling](https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html)

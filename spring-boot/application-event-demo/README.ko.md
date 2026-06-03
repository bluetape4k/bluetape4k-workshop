# Spring Application Event Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Application Event Demo**를 실행 가능한 Spring Boot 애플리케이션 기능 워크숍 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Spring Application Event Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-application-event-demo-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지를 기준으로 삼습니다.

## 시퀀스 다이어그램

![Spring Application Event Demo sequence diagram](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-01.png)

Reactor와 코루틴으로 Spring Application Event를 비동기 발행하고 수신하는 패턴을 보여 줍니다.

## AOP 기반 이벤트 흐름

![AOP diagram](../../docs/images/readme-diagrams/spring-boot-application-event-demo-sequence-02.png)

## 두 가지 이벤트 발행 방식

### 1. 직접 발행(`custom/`)

`ApplicationEventPublisher`를 직접 주입하고 컴포넌트에서 이벤트를 발행합니다.

```kotlin
@Component
class CustomEventPublisher(private val publisher: ApplicationEventPublisher) {
    fun publish(message: String) = publisher.publishEvent(CustomEvent(this, message))
}
```

리스너는 일반 동기 방식과 코루틴 기반 비동기 방식으로 모두 제공됩니다.
- `CustomEventListener` — `@EventListener`로 이벤트를 동기 수신합니다.
- `AnnotatedCoroutineCustomEventListener` — `@EventListener`와 suspend 함수로 이벤트를 비동기 수신합니다.

### 2. AOP 기반 발행(`aspect/`)

메서드가 실행될 때 AOP를 통해 이벤트를 자동 발행합니다.

```kotlin
@AspectEventEmitter  // Automatically publishes an event when a method with this annotation executes
fun doSomething(): Result { ... }
```

- `AspectEventPublisherAspect` — Around Advice로 이벤트를 캡처하고 발행합니다.
- `AspectEventListener` — 동기 이벤트 수신자입니다.
- `CoroutineAspectEventListener` — 코루틴 기반 비동기 이벤트 수신자입니다.

## 참고

- [Spring Application Events](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-events)
- [Spring + Coroutines event handling](https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html)

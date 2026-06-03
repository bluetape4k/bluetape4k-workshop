# Spring Modulith Events Deep Dive

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Modulith Events Deep Dive**를 실행 가능한 Spring Modulith 이벤트 경계 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 구성, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 관찰에 초점을 맞춥니다.

## 시퀀스 다이어그램

![events-deep-dive 시퀀스 다이어그램](../../docs/images/readme-diagrams/spring-modulith-events-deep-dive-readme-sequence-01.png)

기본 애플리케이션 이벤트에서 트랜잭션 이벤트 발행과 모듈 경계 검증으로 이어지는 Spring Modulith 이벤트 발행 예제입니다.

## 아키텍처

![Spring Modulith Events Deep Dive Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-modulith-events-deep-dive-readme-architecture-01.png)

## 이 모듈에서 확인할 내용

- `ApplicationEventPublisher`와 `OrderCompleted`를 사용하는 quickstart 이벤트 발행.
- Spring Data repository 기반 완료 흐름.
- 주문 완료 주변의 트랜잭션 이벤트 발행 동작.
- 직접 inventory 호출 방식과 모듈 이벤트 방식의 before/after 아키텍처 비교.
- 모듈 구조와 통합 동작을 검증하는 Modulith 테스트.

## 실행

```bash
./gradlew :spring-modulith-events-deep-dive:test
```

## 사용하는 bluetape4k 기능

| 모듈 | 기능 | 사용 방식 |
|---|---|---|
| `bluetape4k-logging` | `KLogging()` | `OrderManagement`와 모든 이벤트 listener의 lazy-lambda 구조화 로깅 |
| `bluetape4k-junit5` | JUnit 5 extensions | 테스트 기반 지원과 `@ApplicationModuleTest` 통합 |

## bluetape4k Before / After

### 이벤트 기반 컴포넌트의 `KLogging()`

```kotlin
// Before — SLF4J directly
private val log = LoggerFactory.getLogger(OrderManagement::class.java)
log.info("Completing order. order=" + order)

// After — KLogging() companion object (lazy, zero-cost interpolation)
companion object : KLogging()
log.info { "Completing order. order=$order" }
```

### 이벤트 listener 패턴: Spring vs Modulith

```kotlin
// Before — plain Spring @EventListener (no transactional guarantee)
@EventListener
fun on(event: Order.OrderCompleted) {
    log.info { "Received event: $event" }
}

// After — @TransactionalEventListener (executes after TX commit)
@TransactionalEventListener
fun on(event: Order.OrderCompleted) {
    log.info { "Received event: $event" }
}
```

## 운영 노트

- `@TransactionalEventListener`는 외부 트랜잭션이 커밋된 뒤 실행됩니다. listener가 실패해도 원래 트랜잭션은 롤백되지 않습니다.
- 모듈 경계 의미를 강제하려면 plain `@EventListener` 대신 `@ApplicationModuleListener`(Spring Modulith)를 사용합니다.
- `ApplicationModuleTest`는 어떤 모듈도 다른 모듈의 internal type에 의존하지 않는지 검증합니다.

## 소스 맵

- `a/fundamentals/quickstart`는 `OrderManagement`에서 이벤트를 직접 발행합니다.
- `a/fundamentals/springdata`는 완료된 주문을 Spring Data로 저장합니다.
- `b/transactions`는 트랜잭션 이벤트 발행을 보여줍니다.
- `c/architecture/before`는 주문 완료와 inventory update가 직접 결합된 구조입니다.
- `d/architecture/after`는 order와 inventory 동작을 모듈 경계로 분리합니다.
- `src/test/kotlin/.../events`에는 Modulith 검증과 통합 테스트가 있습니다.

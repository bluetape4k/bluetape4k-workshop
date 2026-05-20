# Spring Modulith Events Deep Dive

[English](README.md) | [한국어](README.ko.md)

기본 애플리케이션 이벤트부터 트랜잭션 이벤트 발행, 모듈 경계 검증까지 단계적으로 확인하는 Spring Modulith 이벤트 예제입니다.

## 아키텍처

![Spring Modulith events architecture](../../docs/images/readme-diagrams/spring-modulith-events-deep-dive-diagram-01.png)

## 이 모듈에서 확인할 내용

- `ApplicationEventPublisher`와 `OrderCompleted`를 사용하는 quickstart 이벤트 발행.
- Spring Data repository 기반 주문 완료 흐름.
- 주문 완료 주변의 트랜잭션 이벤트 발행 동작.
- 직접 inventory 호출 방식과 모듈 이벤트 방식의 before/after 아키텍처 비교.
- 모듈 구조와 통합 동작을 검증하는 Spring Modulith 테스트.

## 실행

```bash
./gradlew :spring-modulith-events-deep-dive:test
```

## 소스 맵

- `a/fundamentals/quickstart`는 `OrderManagement`에서 이벤트를 직접 발행합니다.
- `a/fundamentals/springdata`는 완료된 주문을 Spring Data로 저장합니다.
- `b/transactions`는 트랜잭션 이벤트 발행을 보여줍니다.
- `c/architecture/before`는 주문 완료와 inventory update가 직접 결합된 구조입니다.
- `d/architecture/after`는 order와 inventory 동작을 모듈 경계로 분리합니다.
- `src/test/kotlin/.../events`에는 Modulith 검증과 통합 테스트가 있습니다.

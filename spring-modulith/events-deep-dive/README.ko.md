# Spring Modulith Events Deep Dive

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Modulith Events Deep Dive** 모듈을 실행 가능한 Spring Modulith 이벤트 경계 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 흐름 다이어그램

1. `spring-modulith-events-deep-dive` 예제에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 처리는 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

기본 애플리케이션 이벤트부터 트랜잭션 이벤트 발행, 모듈 경계 검증까지 단계적으로 확인하는 Spring Modulith 이벤트 예제입니다.

## 아키텍처

![Spring Modulith Events Deep Dive Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/spring-modulith-events-deep-dive-readme-architecture-01.png)

![events deep dive Sequence Flow diagram](../../docs/images/readme-diagrams/spring-modulith-events-deep-dive-diagram-01.png)

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

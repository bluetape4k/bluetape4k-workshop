# Spring Boot Application Event Demo 생태계 리뷰

날짜: 2026-07-05
모듈: `:spring-boot-application-event-demo`
브랜치: `refactor/spring-boot-application-event-demo-ecosystem-patterns`

## 범위

- 직접 `ApplicationEventPublisher` 흐름과 AOP event-emitter 흐름을 보존한다.
- `ApplicationListener` 브리지의 운영 코드에서 `runBlocking`을 제거한다.
- 공개 이벤트 페이로드 data class를 bluetape4k Kotlin/직렬화 계약에 맞춘다.

## 7-Tier 리뷰

| Tier | 관점 | 판정 | 근거 |
|---|---|---|---|
| 1 | 정확성 | PASS | 직접 이벤트 경로와 aspect 이벤트 경로가 같은 이벤트 메시지를 계속 발행한다. |
| 2 | API / UX | PASS | `GET /event?message=...`, `@AspectEventEmitter`, listener class 이름은 변경 없다. |
| 3 | 아키텍처 | PASS | 이 모듈은 명시적 이벤트 발행과 aspect 기반 발행을 계속 분리해서 보여준다. |
| 4 | 동시성 | PASS | `CustomEventListener`는 발행자 스레드를 `runBlocking`으로 막지 않고 컴포넌트가 소유한 코루틴 스코프를 사용한다. |
| 5 | 회복성 | PASS | `CustomEvent.message`와 aspect 작업 인자는 bluetape4k `requireNotBlank`로 공백이 아닌 입력을 검증한다. |
| 6 | 테스트 | PASS | `./gradlew :spring-boot-application-event-demo:test --console=plain --max-workers=1`가 통과했다. |
| 7 | 유지보수성 | PASS | 직렬화 가능한 이벤트 DTO는 `serialVersionUID`를 정의하고, 변경된 KDoc/주석은 공개 기여자용 영어 정책을 따른다. |

## P0/P1 게이트

- P0: 0
- P1: 0
- Deferred: 동기 데모 listener는 코루틴 listener와 대비하기 위해 블로킹 작업 시뮬레이션을 유지한다.

## DoD 상태

- `git diff --check`: PASS
- 타깃 테스트: `:spring-boot-application-event-demo:test`: PASS
- 생태계 헬퍼: `bluetape4k-core` 검증과 기존 bluetape4k logging/test 헬퍼를 유지했다.

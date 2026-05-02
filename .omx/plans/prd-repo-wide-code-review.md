# PRD: Repository-Wide Code Review

## Task Statement

- 저장소 하위 모듈 전반에 대해 코드 리뷰를 수행한다.
- 기존 동작을 유지하고, 검증이 없는 동작은 테스트로 고정한다.
- 최선안에 해당하는 최소 diff 수정만 반영한다.
- 수정 후 회귀 검증 근거를 수집한다.

## Desired Outcome

- 공개 API의 즉시 실패 경로, coroutine lifecycle 누수, flaky 테스트, 검증 누락을 우선 제거한다.
- 수정은 모듈별로 독립적이고 방어 가능해야 한다.
- 최종 결과는 테스트/빌드 근거와 함께 보고된다.

## Scope

- 전체 Gradle 하위 모듈.
- 우선순위:
    1. 공개 API `TODO`/미구현 경로
    2. WebFlux/MVC controller/handler의 불필요한 자체 `CoroutineScope`
    3. flaky 통합 테스트와 설정 계약 누락

## Constraints

- 사용자 기존 변경은 되돌리지 않는다.
- 최소 diff 원칙을 유지한다.
- 라이브러리/예제 저장소 특성상 공개 API 계약과 안정성을 우선한다.
- 검증 없이 완료를 주장하지 않는다.

## Acceptance Criteria

- 수정된 모든 공개 API가 실제 구현 또는 명시적 테스트 계약을 가진다.
- 수정된 controller/handler는 불필요한 독립 coroutine scope를 보유하지 않는다.
- 수정된 모듈은 관련 테스트가 통과한다.
- 최종 보고에는 severity 기반 findings와 검증 명령 결과가 포함된다.

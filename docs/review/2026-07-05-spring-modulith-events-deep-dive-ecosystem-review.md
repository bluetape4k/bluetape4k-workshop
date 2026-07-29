# spring-modulith-events-deep-dive 생태계 리뷰

날짜: 2026-07-05
모듈: `:spring-modulith-events-deep-dive`
범위: `spring-modulith/events-deep-dive`
브랜치: `refactor/spring-modulith-events-deep-dive-ecosystem-patterns`

## Workflow 게이트

- 작업 유형: Type B Fast Track, module-scoped example refactor.
- Skills: `bluetape4k-workflow`, `bluetape4k-code-patterns`.
- helper-first 근거: `repo-status`, `repo-test-summary`, `worktree-new`, `worktree-list`.
- GNO orientation: 이 wave와 같은 workshop ecosystem query에서 이 모듈과 충돌하는 선행 규칙은 찾지 못했다.
- CodeGraph: graph stats는 있었지만 stale 상태였다(`Last updated: 2026-06-03T10:01:01`). representative test file의 `file_summary`가 0을 반환해 현재 source `rg`와 파일 읽기로 최신성을 보강했다.

## 검토한 변경

- quickstart `Order` Serializable data class에 명시적 `serialVersionUID`를 추가했다.
- field-level `@MockkBean`과 `uninitialized()`를 class-level `@MockkBean(types = ...)`와 constructor injection으로 바꿨다.
- `before.Application` 예제에서 stale cross-package `after.Application` import를 제거했다.
- 변경된 `companion object`, inheritance, Serializable declaration의 Kotlin spacing을 정규화했다.

## 생태계 재사용

- Spring Modulith 예제 구조와 `@IntegrationTest` constructor autowiring을 보존했다.
- 기존 bluetape4k `Uuid.V7` ID generation을 보존했다.
- `bluetape4k-assertions` test와 기존 MockK/SpringMockK 사용을 보존했다.

## 7-Tier 리뷰

| Tier | 판정 | 근거 |
|---|---|---|
| Performance | PASS | style/test wiring 변경뿐이며 hot path는 변경하지 않았다. |
| Stability | PASS | mock repository는 sibling test에서 이미 쓰는 constructor-injection pattern을 따른다. |
| Security | PASS | 새 input, auth, persistence, serialization trust boundary는 없다. |
| Operator/Ops | PASS | infrastructure나 workflow 동작은 변경하지 않았다. |
| Developer/API | PASS | Serializable과 Kotlin style rule을 맞췄다. |
| User/caller | PASS | example 동작은 변경 없다. |
| Evidence integrity | PASS | native reviewer P3 stale-import finding을 고친 뒤 다시 테스트했다. |

## Reviewer 발견 사항

- P0/P1: 0.
- P3 repaired: `c.architecture.before.Application`에서 stale `after.Application` import를 제거했다.

## 검증

- `Thread.sleep`, `!!`, `uninitialized(`, compact `companion object:`, raw JUnit assertion, raw `GenericContainer`, deprecated Exposed import에 대한 `rg` pattern scan: PASS.
- `git diff --check`: PASS.
- 초기 타깃 검증: `repo-test-summary -- ./gradlew :spring-modulith-events-deep-dive:test --console=plain --max-workers=1`: PASS, 10 tests, `BUILD SUCCESSFUL in 10s`.
- P3 repair 이후 follow-up: 같은 명령 PASS, 10 tests, `BUILD SUCCESSFUL in 5s`.
- IntelliJ diagnostics는 이 session에서 사용할 수 없어 타깃 Gradle compile/test와 static scan을 fallback으로 사용했다.

## 잔여 위험

- 이 module slice에서 알려진 잔여 위험은 없다.

# spring-data-redis-examples 생태계 리뷰

날짜: 2026-07-05
모듈: `:spring-data-redis-examples`
범위: `spring-data/redis-examples`
브랜치: `refactor/spring-data-redis-examples-ecosystem-patterns`

## Workflow 게이트

- 작업 유형: Type B Fast Track, module-scoped example refactor.
- Skills: `bluetape4k-workflow`, `bluetape4k-code-patterns`.
- helper-first 근거: `repo-status`, `repo-test-summary`, `worktree-new`, `worktree-list`.
- GNO orientation: `gno query "bluetape4k-workshop spring-data redis examples spring modulith ddd order audit events deep dive ecosystem patterns" -c bluetape4k-docs --fast --no-rerank`.
- CodeGraph: graph stats는 있었지만 stale 상태였다(`Last updated: 2026-06-03T10:01:01`). `file_summary`는 `RedisApplication.kt`를 찾았고, 최신성 보강을 위해 현재 source `rg`와 파일 읽기를 사용했다.

## 검토한 변경

- active production Redis connection factory에 대해 `RedisApplication` field `uninitialized()` injection을 constructor injection으로 바꿨다.
- `RedisServer.Launcher.redis` host/port/url을 `@DynamicPropertySource`로 등록하는 `RedisTestSupport`를 추가했다.
- Spring test placeholder injection을 constructor injection과 shared Redis dynamic property로 바꿨다.
- Redis example Serializable DTO와 fixture에 명시적 `serialVersionUID`를 추가했다.
- test 전용 `!!` precondition을 `shouldNotBeNull()`로 바꿨다.
- stream listener의 fixed sleep과 unbounded `take()` 사용을 bounded `poll(Duration)` assertion으로 바꿨다.
- 변경된 `companion object` declaration의 Kotlin spacing을 정규화했다.

## 생태계 재사용

- `bluetape4k-testcontainers`의 `RedisServer.Launcher.redis`를 보존했다.
- `bluetape4k.spring.redis.serializer`의 Redis serializer를 보존했다.
- nullable test precondition에는 `bluetape4k-assertions`를 사용했다.
- 모듈에 이미 있던 `runSuspendIO` 같은 coroutine test helper를 보존했다.

## 7-Tier 리뷰

| Tier | 판정 | 근거 |
|---|---|---|
| Performance | PASS | hot path 동작은 변경하지 않았다. bounded stream wait로 unbounded blocking을 피한다. |
| Stability | PASS | `RedisTestSupport`가 Spring Boot Redis property binding을 안정화하고 타깃 테스트가 통과했다. |
| Security | PASS | 새 external input, secret, auth, deserialization trust boundary는 없다. |
| Operator/Ops | PASS | Testcontainers Redis launcher가 infra boundary로 남아 있고 raw container 생성은 없다. |
| Developer/API | PASS | constructor injection과 Serializable contract가 Kotlin/Spring 스타일을 개선한다. |
| User/caller | PASS | example 동작과 README-facing semantics는 변경 없다. |
| Evidence integrity | PASS | native reviewer가 P1/P2를 찾았고, 둘 다 PR 전에 고쳤다. |

## Reviewer 발견 사항

- P1 repaired: `RedisTestSupport.kt`가 이제 branch diff에 포함된다(`A spring-data/redis-examples/src/test/kotlin/io/bluetape4k/workshop/redis/RedisTestSupport.kt`).
- P2 repaired: `CapturingStreamListener.take()`를 제거했다. sync stream test는 bounded `poll(RECORD_TIMEOUT)`를 사용한다.
- P3 deferred: `ReactiveStreamApiTest`의 기존 `println` 호출은 이번 동작 변경 범위가 아니다.
- P0/P1 final: 0.

## 검증

- `Thread.sleep`, `!!`, `uninitialized(`, compact `companion object:`, raw JUnit assertion, raw `GenericContainer`, deprecated Exposed import에 대한 `rg` pattern scan: Korean prose comment의 `!!!`를 제외하고 PASS.
- `git diff --check`: PASS.
- `repo-test-summary -- ./gradlew :spring-data-redis-examples:test --console=plain --max-workers=1`: PASS, 39 tests executed, 1 skipped, `BUILD SUCCESSFUL in 16s`.
- IntelliJ diagnostics는 이 session에서 사용할 수 없었다. 타깃 Gradle compile/test와 static scan을 fallback으로 사용했다.

## 잔여 위험

- Lettuce가 shutdown 중 `Connection closed` log를 남기지만 Gradle은 exit 0이고 모든 테스트가 통과한다. 실패 테스트나 CI warning policy가 되기 전까지 shutdown noise로 다룬다.

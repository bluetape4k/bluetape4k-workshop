# exposed-mvc-jdbc 생태계 코드 리뷰

모듈: `:exposed-mvc-jdbc`
브랜치: `refactor/exposed-mvc-jdbc-ecosystem-patterns`
날짜: 2026-07-05

## 범위

이 문서는 Spring MVC + Exposed JDBC example을 request validation, explicit not-found boundary, bluetape4k concurrency test helper, Kotlin style, regression evidence 기준으로 점검한 7-Tier review와 remediation 결과다.

## 7-Tier 결과

| Tier | 상태 | 근거 |
|---|---|---|
| API와 validation boundary | PASS | author email과 product name은 이제 persistence 전에 Bean Validation으로 blank input을 거부한다. |
| Correctness와 data integrity | PASS | book creation은 insert 전에 author 존재를 확인해 FK failure에 의존하지 않고 기존 not-found path를 반환한다. |
| Spring MVC error behavior | PASS | invalid author/product input은 400을 반환하고, 존재하지 않는 book author는 기존 exception handler를 통해 404를 반환한다. |
| bluetape4k 생태계 사용 | PASS | concurrency regression은 이제 `MultithreadingTester`를 사용하고, test는 `!!` 없이 bluetape4k assertion을 사용한다. |
| Kotlin/Exposed style과 safety | PASS | 수정된 code에는 `!!`, raw `Executors`/`CountDownLatch`, deprecated Exposed import, boolean assertion anti-pattern이 없다. |
| Test와 regression coverage | PASS | blank author email, nonexistent book author, blank product name을 추가하고 concurrent stock race coverage를 보존했다. |
| Documentation과 maintainability | PASS | review artifact는 module evidence와 열린 P0/P1/P2/P3 finding이 없음을 기록한다. |

## 발견 사항

- P0: 0
- P1: 0
- P2: 0
- P3: 0

## 검증

- `repo-test-summary -- ./gradlew :exposed-mvc-jdbc:compileKotlin :exposed-mvc-jdbc:compileTestKotlin :exposed-mvc-jdbc:cleanTest :exposed-mvc-jdbc:test --no-build-cache --warning-mode all --console=plain --max-workers=1`
  - PASS, 14개 test.
- `MAX_WORKERS=1 repo-test-summary -- ./scripts/smoke-validate.sh data-access-full`
  - PASS, Gradle exit 0.
- `repo-test-summary -- ./scripts/smoke-validate.sh stale-check`
  - PASS, active module 101개, stale reference 없음, 깨진 image link 없음.
- `git diff --check`
  - PASS.
- 금지된 assertion style, deprecated Exposed import, `runCatching`, `!!`, raw `Executors`, `CountDownLatch`, `Thread.sleep`에 대한 static scan
  - PASS, 수정된 path에서 hit 없음.
- Native code-reviewer 재리뷰
  - APPROVE, P0/P1/P2/P3 = 0.

이 Codex surface에서는 IntelliJ diagnostics를 사용할 수 없어 Gradle compile/test를 fallback diagnostics evidence로 사용했다. full data-access
smoke는 test 성공 후 Redis shutdown noise를 출력했지만 command exit code는 0이었다.

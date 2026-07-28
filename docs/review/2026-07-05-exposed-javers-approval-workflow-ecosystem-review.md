# exposed-javers-approval-workflow 생태계 코드 리뷰

모듈: `:exposed-javers-approval-workflow`
브랜치: `refactor/exposed-javers-approval-workflow-ecosystem-patterns`
날짜: 2026-07-05

## 범위

이 문서는 approval workflow example module을 bluetape4k 생태계 사용, Kotlin style, JaVers JSON handling, validation boundary, Exposed update safety, regression coverage 기준으로 점검한 7-Tier review와 remediation 결과다.

## 7-Tier 결과

| Tier | 상태 | 근거 |
|---|---|---|
| API와 domain boundary | PASS | proposal 및 policy id는 이제 `requirePositiveNumber`를 사용한다. 유효하지 않은 policy는 persistence 또는 JaVers commit 전에 거부된다. |
| Correctness와 state transition | PASS | approval/rejection은 이제 JaVers commit과 current-policy upsert 전에 conditional pending-state update를 사용한다. |
| Persistence와 Exposed 사용 | PASS | stale in-memory state check 대신 Exposed v1 top-level `eq`/`and` import와 conditional `update`를 사용한다. |
| bluetape4k 생태계 사용 | PASS | hand-rolled JSON escaping 대신 `JaversCodecs.String`과 `javers.jsonConverter`를 사용하고, validation에는 bluetape4k support extension을 사용한다. |
| Kotlin style과 safety | PASS | 수정된 code에는 `!!`, suspend call 주변 `runCatching`, deprecated Exposed import, boolean-style assertion anti-pattern이 없다. |
| Test와 regression coverage | PASS | single-use approval, invalid policy, invalid lookup id, money scale validation, special-character snapshot JSON 커버리지를 추가했다. |
| Documentation과 maintainability | PASS | review artifact는 module decision, 남은 P3 follow-up, verification evidence를 기록한다. |

## 발견 사항

- P0: 0
- P1: 0
- P2: 0
- P3: 1

P3 follow-up: duplicate-approval regression test는 true concurrent race test가 아니라 순차 test다. conditional database update는 review에서 발견한 code race를 해결한다. module에 명시적인 concurrent proof가 필요하면 나중에 `MultithreadingTester` contention test를 추가한다.

## 검증

- `repo-test-summary -- ./gradlew :exposed-javers-approval-workflow:compileKotlin :exposed-javers-approval-workflow:compileTestKotlin :exposed-javers-approval-workflow:cleanTest :exposed-javers-approval-workflow:test --no-build-cache --warning-mode all --console=plain --max-workers=1`
  - PASS, 10개 test.
- `MAX_WORKERS=1 repo-test-summary -- ./scripts/smoke-validate.sh data-access`
  - PASS.
- `repo-test-summary -- ./scripts/smoke-validate.sh stale-check`
  - PASS, active module 101개, stale reference 없음, 깨진 image link 없음.
- `git diff --check`
  - PASS.
- 금지된 assertion style, deprecated Exposed import, `runCatching`, `!!`에 대한 static scan
  - PASS, 수정된 source/test path에서 hit 없음.
- Native code-reviewer 재리뷰
  - APPROVE, P0/P1/P2 = 0.

이 Codex surface에서는 IntelliJ diagnostics를 사용할 수 없어 Gradle compile/test를 fallback diagnostics evidence로 사용했다.
